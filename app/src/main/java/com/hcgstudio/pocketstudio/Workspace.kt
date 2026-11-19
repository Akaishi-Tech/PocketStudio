package com.hcgstudio.pocketstudio

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.ContextCompat
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment
import com.hcgstudio.pocketstudio.plugin.ProjectStarter
import com.hcgstudio.pocketstudio.plugin.ProjectWizardHost
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal enum class Tool { Editor, Project, Devices, Output, Agents }

internal class OpenFile(
    val node: Project.Node,
    savedText: String,
    initial: String,
    changed: (String) -> Unit
) {
    var saved by mutableStateOf(savedText)
    val editor = StudioEditorState(initial, node.name, onTextChanged = changed)
    val dirty get() = editor.text != saved
}

internal class Workspace(val app: Application, state: SavedStateHandle) : AndroidViewModel(app), ProjectWizardHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = Mutex()
    private val prefs = app.getSharedPreferences("studio", 0)
    private val saves = Mutex()
    private val settingsPrefs = StudioSettings.preferences(app)
    var settings by mutableStateOf(StudioSettings.read(settingsPrefs))
        private set
    val updates = AppUpdates(app, scope)
    val devices = DeviceController(app)
    var androidEnvironment by mutableStateOf<AndroidEnvironment?>(null)
        private set
    val androidAvailable get() = androidEnvironment != null
    private var discovery: Job? = null
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == AndroidEnvironments.PACKAGE) refreshEnvironment()
        }
    }
    fun refreshEnvironment() {
        discovery?.cancel()
        discovery = scope.launch {
            androidEnvironment = try {
                withContext(Dispatchers.IO) { AndroidEnvironments.find(app) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e); null }
            catch (e: LinkageError) { report(e); null }
            if (!androidAvailable) devices.closeRemoteConnection()
            if (!androidAvailable && tool == Tool.Output) tool = Tool.Editor
        }
    }

    val agents = Agents(this, scope)
    var project by mutableStateOf<Project?>(null)
        private set
    var git by mutableStateOf<GitSession?>(null)
        private set
    var tool by mutableStateOf(Tool.Editor)
    val tabs = mutableStateListOf<OpenFile>()
    var active by mutableStateOf<OpenFile?>(null)
        private set
    val folders = mutableStateMapOf<String, List<Project.Node>>()
    var selectedNode by mutableStateOf<Project.Node?>(null)
    var selectedDir by mutableStateOf<Project.Node?>(null)
    val recent = mutableStateListOf<Pair<String, String>>()
    override var busy by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    val canSwitchProject get() = !busy && !running && !agents.working && git == null
    var output by mutableStateOf("")
        private set
    var apk by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    val newProjectVisible = state.getMutableStateFlow("newProjectVisible", false)
    override var creationError by mutableStateOf<String?>(null)
    private var openVersion = 0
    private val drafts = mutableMapOf<String, Job>()
    init {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, packageChanges, packages, ContextCompat.RECEIVER_EXPORTED)
        val entries = JSONArray(prefs.getString("recentProjects", "[]"))
        for (i in 0 until entries.length()) {
            entries.optJSONArray(i)?.let { recent.add(it.getString(0) to it.getString(1)) }
        }
    }

    fun updateSettings(value: StudioSettings) {
        val autoUpdateChanged = settings.autoUpdate != value.autoUpdate
        value.save(settingsPrefs)
        settings = value
        if (autoUpdateChanged) {
            if (value.autoUpdate) updates.check(automatic = true) else updates.disableAutomatic()
        }
    }

    fun reopenLastProject() {
        if (!settings.reopenLastProject || project != null) return
        // Older versions recorded recent projects but had no explicit closed-project marker.
        val location = prefs.getString("openProject", recent.firstOrNull()?.first)
        if (!location.isNullOrEmpty()) openProject(location.toUri())
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        storage.withLock { withContext(Dispatchers.IO) { block() } }

    suspend fun <T> projectOperation(selected: Project, block: suspend () -> T): T = io {
        check(project === selected) { app.getString(R.string.project_closed) }
        block()
    }

    fun report(e: Throwable) { message = e.localizedMessage ?: e.javaClass.simpleName }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
        }
    }
    private fun id(p: Project, path: String): String =
        MessageDigest.getInstance("SHA-256").digest((p.root.uri.toString() + "\n" + path).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun draft(p: Project, node: Project.Node) = AtomicFile(File(app.filesDir, "draft-" + id(p, node.path)))
    private fun persist(p: Project, d: OpenFile) {
        val text = d.editor.text
        val dirty = d.dirty
        action {
            io {
                val file = draft(p, d.node)
                if (!dirty) file.delete() else {
                    val stream = file.startWrite()
                    try { stream.write(text.toByteArray()); file.finishWrite(stream) }
                    catch (e: Exception) { file.failWrite(stream); throw e }
                }
            }
        }
    }

    private fun edited(p: Project, node: Project.Node) {
        drafts.remove(node.path)?.cancel()
        drafts[node.path] = scope.launch {
            delay(350)
            tabs.firstOrNull { it.node.path == node.path && project === p }?.let { persist(p, it) }
            drafts.remove(node.path)
        }
    }
    fun flushDrafts() {
        val p = project ?: return
        for (d in tabs) { drafts.remove(d.node.path)?.cancel(); persist(p, d) }
    }
    fun rememberProject(uri: Uri, name: String) {
        recent.removeAll { it.first == uri.toString() }
        recent.add(0, uri.toString() to name)
        while (recent.size > 12) recent.removeAt(recent.lastIndex)
        prefs.edit().putString("recentProjects",
            JSONArray().apply { recent.forEach { put(JSONArray().put(it.first).put(it.second)) } }.toString()).apply()
    }
    private fun reset() {
        agents.reset()
        openVersion++
        tabs.clear(); active = null; folders.clear(); selectedDir = null; selectedNode = null
        apk = null; output = ""; tool = Tool.Project
    }
    fun openProject(uri: Uri, import: Boolean = false, restoreFile: String? = null) {
        switchProject(restoreFile) {
            if (import) {
                val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                Project.importZip(app, uri, name ?: "Project")
            } else Project(app, uri)
        }
    }

    override fun projectNameError(name: String): String? = Project.projectNameError(name)

    override fun createProject(parent: Uri, name: String, starter: ProjectStarter) {
        switchProject(starter.entryPath, creating = true) {
            check(AndroidEnvironments.available(app)) { app.getString(R.string.android_environment_required) }
            if (parent.scheme == "content") {
                app.contentResolver.takePersistableUriPermission(parent,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Project.createProject(app, parent, name, starter)
        }
    }

    private fun switchProject(restoreFile: String?, creating: Boolean = false, load: () -> Project) {
        if (!canSwitchProject) return
        busy = true
        flushDrafts()
        action {
            try {
                val (next, roots) = io { val p = load(); p to p.list(p.root) }
                reset()
                project = next; selectedDir = next.root; folders[""] = roots
                val location = next.tree ?: next.root.uri
                devices.selectBreakpointProject(location.toString())
                rememberProject(location, next.root.name)
                prefs.edit { putString("openProject", location.toString()) }
                if (creating) newProjectVisible.value = false
                apk = resultFile(next).takeIf { it.isFile }
                val last = restoreFile ?: prefs.getString("tab:$location", null)
                if (!last.isNullOrBlank()) {
                    val node = io { try { next.resolve(last) } catch (_: java.io.FileNotFoundException) { null } }
                    if (node != null) openFile(node)
                }
            } catch (e: Exception) {
                if (!creating || e is CancellationException || !newProjectVisible.value) throw e
                creationError = e.localizedMessage ?: e.javaClass.simpleName
            } finally { busy = false }
        }
    }

    fun closeProject() {
        if (busy || running || agents.working || git != null) return
        flushDrafts()
        devices.selectBreakpointProject(null)
        reset(); project = null
        prefs.edit { putString("openProject", "") }
    }
    fun select(d: OpenFile) {
        active = d; selectedNode = d.node; tool = Tool.Editor
        project?.let { prefs.edit().putString("tab:${it.tree ?: it.root.uri}", d.node.path).apply() }
    }
    fun closeTab(d: OpenFile) {
        project?.let { persist(it, d) }
        tabs.remove(d)
        if (active === d) {
            active = null
            tabs.lastOrNull()?.let(::select) ?: project?.let {
                prefs.edit().remove("tab:${it.tree ?: it.root.uri}").apply()
            }
        }
    }
    fun openFile(node: Project.Node, line: Int = 0) {
        val p = project ?: return
        if (node.folder) { toggle(node); return }
        val version = ++openVersion
        val existing = tabs.firstOrNull { it.node.path == node.path }
        if (existing != null) {
            select(existing); if (line > 0) existing.editor.go(line); return
        }
        action {
            val (saved, body) = io {
                val original = p.read(node)
                val local = draft(p, node)
                original to if (local.baseFile.exists()) local.readFully().decodeToString() else original
            }
            if (project !== p || openVersion != version) return@action
            reveal(p, node)
            if (project !== p || openVersion != version) return@action
            val doc = OpenFile(node, saved, body) { edited(p, node) }
            tabs.add(doc); select(doc)
            if (line > 0) doc.editor.go(line)
        }
    }
    private suspend fun reveal(p: Project, node: Project.Node) {
        val ancestors = io {
            val result = linkedMapOf<String, List<Project.Node>>()
            var dir = p.root
            for (part in node.path.split('/').dropLast(1)) {
                val level = p.list(dir)
                result[dir.path] = level
                dir = level.firstOrNull { it.folder && it.name == part } ?: break
                result[dir.path] = p.list(dir)
            }
            result to dir
        }
        if (project === p) {
            folders.putAll(ancestors.first)
            selectedDir = ancestors.second
        }
    }

    fun changeNode(node: Project.Node, op: String, input: String) {
        val p = project ?: return
        if (busy || running || agents.working || git != null) return
        busy = true
        action {
            try {
                if (!saveDocuments()) return@action
                val affected = tabs.filter { it.node.path == node.path || it.node.path.startsWith(node.path + "/") }
                val after = io {
                    when (op) {
                        "deleteNode" -> { p.delete(node); null }
                        "renameNode" -> p.relocate(node, (node.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.plus("/") ?: "") + input, false)
                        else -> p.relocate(node, input, op == "copyNode")
                    }
                }
                if (op != "copyNode") {
                    affected.forEach { d -> drafts.remove(d.node.path)?.cancel(); tabs.remove(d) }
                    if (active in affected) { active = null; tabs.lastOrNull()?.let(::select) }
                }
                selectedNode = after
                folders.keys.removeAll { it == node.path || it.startsWith(node.path + "/") }
                refreshTree()
                if (after != null) {
                    reveal(p, after)
                    if (!after.folder) openFile(after)
                }
            } finally { busy = false }
        }
    }
    fun toggle(node: Project.Node) {
        selectedDir = node; selectedNode = node
        if (folders.remove(node.path) == null) refreshFolder(node)
    }
    private fun refreshFolder(node: Project.Node) {
        val p = project ?: return
        action { val items = io { p.list(node) }; if (project === p) folders[node.path] = items }
    }
    fun refreshTree() {
        val p = project ?: return
        val opened = folders.keys.toList()
        action {
            val loaded = io {
                opened.mapNotNull { path ->
                    try { path to p.list(p.resolve(path)) } catch (_: java.io.FileNotFoundException) { null }
                }.toMap()
            }
            if (project === p) { folders.clear(); folders.putAll(loaded); selectedDir = p.root }
        }
    }
    fun create(name: String, folder: Boolean) {
        val p = project ?: return
        val dir = selectedDir ?: p.root
        action {
            val node = io { p.create(dir, name, folder) }
            if (project === p) {
                refreshFolder(dir)
                if (!folder) openFile(node) else { selectedDir = node; refreshFolder(node) }
            }
        }
    }

    suspend fun saveDocuments(files: List<OpenFile> = tabs.toList()): Boolean {
        val p = project ?: return false
        return saves.withLock {
            try {
                for (d in files) {
                    if (project !== p) return@withLock false
                    if (!d.dirty || d !in tabs) continue
                    val original = d.editor.text
                    val baseline = d.saved
                    val options = settings
                    val cleaned = io {
                        val text = prepareForSave(original, options)
                        if (p.read(d.node) != baseline) throw java.io.IOException(app.getString(R.string.file_external_change, d.node.name))
                        p.write(d.node, text.toByteArray())
                        text
                    }
                    d.saved = cleaned
                    if (d.editor.text == original && cleaned != original) {
                        val selection = d.editor.value.selection
                        val start = savedOffset(original, cleaned, selection.start)
                        val end = savedOffset(original, cleaned, selection.end)
                        d.editor.replace(0, original.length, cleaned, TextRange(start, end))
                    }
                    persist(p, d)
                }
                project === p
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
                false
            }
        }
    }
    suspend fun reloadChanged(nodes: List<Project.Node>) {
        val p = project ?: return
        for (node in nodes) {
            val index = tabs.indexOfFirst { it.node.path == node.path }
            if (index < 0) continue
            val old = tabs[index]
            val text = io { p.read(node) }
            if (project !== p || old.dirty) continue
            val replacement = OpenFile(node, text, text) { edited(p, node) }
            replacement.editor.go(old.editor.line, old.editor.column)
            tabs[index] = replacement
            if (active === old) active = replacement
            persist(p, replacement)
        }
    }
    suspend fun refreshAfterExternalRun(p: Project) {
        if (project !== p) return
        refreshTree()
        for (old in tabs.toList()) {
            val current = io {
                try {
                    val node = p.resolve(old.node.path)
                    if (node.folder) null else node to p.read(node)
                } catch (_: java.io.FileNotFoundException) { null }
                catch (_: Project.UnsupportedText) { null }
                catch (_: java.nio.charset.CharacterCodingException) { null }
            }
            if (project !== p) return
            val index = tabs.indexOf(old)
            if (index < 0) continue
            if (old.dirty) {
                if (current == null || current.second != old.saved)
                    message = app.getString(R.string.draft_external_change, old.node.name)
                continue
            }
            if (current == null) {
                closeTab(old)
                continue
            }
            val (node, body) = current
            if (node.uri == old.node.uri && body == old.saved) continue
            val updated = OpenFile(node, body, body) { edited(p, node) }
            updated.editor.go(old.editor.line, old.editor.column)
            tabs[index] = updated
            if (active === old) active = updated
            persist(p, updated)
        }
    }

    private fun resultFile(p: Project) = File(File(app.filesDir, "outputs"), id(p, "") + ".apk")
    fun recordApk(file: File) {
        val p = project ?: return
        busy = true
        action {
            try {
                val result = io { copyResult(p, file) }
                apk = result
                tool = Tool.Output
                log(app.getString(R.string.selected_build_output, file.name))
            } finally { busy = false }
        }
    }
    private fun copyResult(p: Project, source: File): File {
        val result = resultFile(p)
        result.parentFile!!.mkdirs()
        val file = AtomicFile(result)
        val stream = file.startWrite()
        try { source.inputStream().use { it.copyTo(stream) }; file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        return result
    }

    fun log(line: String) {
        output = (output + line + "\n").takeLast(100000)
    }
    fun openGit(tab: GitTab = GitTab.Changes, file: String? = null, diff: Boolean = false) {
        val selected = project ?: return
        if (busy || running || agents.working || git != null) return
        busy = true
        action {
            try {
                if (!saveDocuments()) return@action
                val session = GitSession(this@Workspace, selected, tab, file, diff)
                git = session
                busy = false
                val editPath = session.untilClosed()
                if (editPath != null && project === selected) diagnostic(editPath, 0)
            } finally {
                git = null
                busy = false
            }
        }
    }

    fun build(runOnDevice: Boolean = false, debug: Boolean = false) {
        val p = project ?: return
        if (!androidAvailable || running || busy || agents.working || git != null) return
        val targetId = if (runOnDevice && devices.connected && !devices.busy) devices.connectionId else null
        running = true; output = ""; tool = Tool.Output
        action {
            try {
                if (!saveDocuments()) return@action
                val result = withContext(Dispatchers.IO) {
                    ProjectWorkspace.open(p).use { workspace ->
                        val built = AndroidEnvironments.require(app).build(workspace.directory.toFile(), selectedBuildSdk(p)) { msg -> scope.launch { log(msg) } }
                        copyResult(p, built)
                    }
                }
                apk = result; log(app.getString(R.string.build_succeeded_kib, result.length() / 1024))
                if (runOnDevice) {
                    if (targetId == devices.connectionId && devices.connected && !devices.busy) {
                        tool = Tool.Devices
                        devices.install(result, true, debug)
                    } else log(app.getString(R.string.the_target_device_changed_disconnected_or_became_busy))
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; log(e.localizedMessage ?: e.toString()) }
            catch (e: LinkageError) { log(e.toString()) }
            finally {
                running = false
                refreshAfterExternalRun(p)
            }
        }
    }
    fun diagnostic(path: String, line: Int) {
        val p = project ?: return
        action { val node = io { p.resolve(path) }; if (project === p) openFile(node, line) }
    }
    override fun onCleared() { app.unregisterReceiver(packageChanges); agents.reset(); devices.close(); scope.cancel(); super.onCleared() }
}
