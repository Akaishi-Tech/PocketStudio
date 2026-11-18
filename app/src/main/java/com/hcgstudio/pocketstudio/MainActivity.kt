package com.hcgstudio.pocketstudio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var workspace: Workspace
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        workspace = ViewModelProvider(this)[Workspace::class.java]
        savedInstanceState?.getString("location")?.let {
            if (workspace.project == null) workspace.openProject(Uri.parse(it), restoreFile = savedInstanceState.getString("document"))
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(background = Color(0xff111512), primary = Color(0xff8ed5b7), secondary = Color(0xffb3ccbf),
                surface = Color(0xff111512), surfaceContainer = Color(0xff1e2421), primaryContainer = Color(0xff15513c),
                onPrimaryContainer = Color(0xffa5f2cc), secondaryContainer = Color(0xff30453a), onSecondaryContainer = Color(0xffd6ecdf))
            else lightColorScheme(background = Color(0xfffbfcf8), primary = Color(0xff25694e), onPrimary = Color.White,
                secondary = Color(0xff506459), tertiary = Color(0xff805333), surface = Color(0xfffbfcf8),
                surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff4f6f1), surfaceContainer = Color(0xffecf0ea),
                surfaceContainerHigh = Color(0xffe7ebe5), primaryContainer = Color(0xffbcebd0), onPrimaryContainer = Color(0xff0d452d),
                secondaryContainer = Color(0xffdbe8dd), onSecondaryContainer = Color(0xff1c3426),
                onSurface = Color(0xff18211b), onSurfaceVariant = Color(0xff47534a), outlineVariant = Color(0xffc6cfc6))
            MaterialTheme(colorScheme = colors) {
                StudioApp(workspace)
            }
        }
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    workspace.report(e)
                }
            }
        }
    }
    override fun onStop() { workspace.flushDrafts(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        workspace.project?.let { outState.putString("location", (it.tree ?: it.root.uri).toString()) }
        outState.putString("document", workspace.active?.node?.path)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun StudioApp(w: Workspace) {
    val context = w.app
    val p = w.project
    val active = w.active
    var dialog by rememberSaveable(p?.root?.uri?.toString()) { mutableStateOf("") }
    var gradleTask by rememberSaveable { mutableStateOf("assembleDebug") }
    var showTree by rememberSaveable { mutableStateOf(true) }
    val newProjectVisible by w.newProjectVisible.collectAsState()
    val newProject = {
        w.creationError = null
        w.newProjectVisible.value = true
    }
    val snack = remember { SnackbarHostState() }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                w.openProject(uri)
            } catch (e: Exception) { w.report(e) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { w.openProject(it, import = true) } }
    val openApk = rememberOpenApk(w::report)
    fun openEntry(node: Project.Node) {
        if (!node.folder && node.name.endsWith(".apk", ignoreCase = true)) {
            w.selectedNode = node
            openApk(node.uri)
        } else w.openFile(node)
    }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            val path = exportPath; val body = exportText
            w.action {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.unable_to_write_document))
                    out.use { stream ->
                        if (path != null) File(path).inputStream().use { it.copyTo(stream) }
                        else stream.write((body ?: error(context.getString(R.string.the_document_is_closed))).toByteArray())
                    }
                }
                w.message = context.getString(R.string.exported)
            }
        }
    }
    fun exportFile(apk: Boolean) {
        val name = if (apk) "${p?.root?.name}-debug.apk" else active?.node?.name ?: return
        exportPath = if (apk) w.apk?.path ?: return else null
        exportText = if (apk) null else active?.editor?.text
        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (apk) "application/vnd.android.package-archive" else "text/plain").putExtra(Intent.EXTRA_TITLE, name))
    }
    val openTree = { open.launch(null) }
    val importZip = { import.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
    fun savedDialog(id: String) { w.action { if (w.saveDocuments()) dialog = id } }
    val canEdit = p != null && !w.busy
    val idle = canEdit && !w.running && !w.codex.working
    val editorVisible = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600 || w.tool == Tool.Editor
    val editor = active?.editor
    val className = remember(editor?.text, active?.node?.name) {
        val name = active?.node?.name.orEmpty().substringBeforeLast('.')
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(editor?.text.orEmpty())?.groupValues?.get(1)
        if (pkg == null) name else "$pkg.$name"
    }
    fun act(id: String, label: String, group: Int, shortcut: String = "", enabled: Boolean = true, run: () -> Unit) =
        StudioAction(id, label, group, shortcut, enabled, run)
    val keymap = remember { Keymap(context.getSharedPreferences("studio-keymap", 0)) }
    val defaults = buildList {
        add(act("newProject", context.getString(R.string.new_project), R.string.file, enabled = !w.running && !w.busy, run = newProject))
        add(act("open", context.getString(R.string.open_folder), R.string.file, "Ctrl+O", !w.running && !w.busy, openTree))
        add(act("import", context.getString(R.string.import_zip), R.string.file, enabled = !w.running && !w.busy, run = importZip))
        add(act("newFile", context.getString(R.string.new_file), R.string.file, "Alt+Insert", canEdit) { dialog = "file" })
        add(act("newDir", context.getString(R.string.new_folder), R.string.file, enabled = canEdit) { dialog = "folder" })
        add(act("save", context.getString(R.string.save_file), R.string.file, "Ctrl+S", active != null) { w.action { if (w.saveDocuments(listOfNotNull(active))) w.message = context.getString(R.string.saved_label) } })
        add(act("saveAll", context.getString(R.string.save_all), R.string.file, "Ctrl+Shift+S", canEdit) { w.action { if (w.saveDocuments()) w.message = context.getString(R.string.all_files_saved) } })
        val node = w.selectedNode
        val nodeEnabled = idle && node != null && node.path.isNotEmpty()
        add(act("copyNode", context.getString(R.string.copy_file_or_folder), R.string.file, "F5", nodeEnabled) { dialog = "copyNode" })
        add(act("moveNode", context.getString(R.string.move_file_or_folder), R.string.file, "F6", nodeEnabled) { dialog = "moveNode" })
        add(act("renameNode", context.getString(R.string.rename_file_or_folder), R.string.file, enabled = nodeEnabled) { dialog = "renameNode" })
        add(act("deleteNode", context.getString(R.string.delete_file_or_folder), R.string.file, "Alt+Delete", nodeEnabled) { dialog = "deleteNode" })
        add(act("exportText", context.getString(R.string.export_current_file), R.string.file, enabled = active != null) { exportFile(false) })
        add(act("closeTab", context.getString(R.string.close_tab), R.string.file, "Ctrl+F4", active != null) { active?.let(w::closeTab) })
        add(act("close", context.getString(R.string.close_project), R.string.file, enabled = idle, run = w::closeProject))
    } + editorActions(if (editorVisible) editor else null) + buildList {
        add(act("findFile", context.getString(R.string.go_to_file), R.string.navigate, "Ctrl+Shift+N", canEdit) { dialog = "findFile" })
        add(act("findProject", context.getString(R.string.find_in_files), R.string.edit, "Ctrl+Shift+F", canEdit) { savedDialog("findProject") })
        add(act("replaceProject", context.getString(R.string.replace_in_files), R.string.edit, "Ctrl+Shift+R", canEdit) { savedDialog("replaceProject") })
        fun switchTab(offset: Int) {
            if (w.tabs.isNotEmpty()) w.select(w.tabs[Math.floorMod(w.tabs.indexOf(active) + offset, w.tabs.size)])
        }
        add(act("nextTab", context.getString(R.string.next_tab), R.string.navigate, "Alt+Right", w.tabs.size > 1) { switchTab(1) })
        add(act("prevTab", context.getString(R.string.previous_tab), R.string.navigate, "Alt+Left", w.tabs.size > 1) { switchTab(-1) })
        add(act("project", context.getString(R.string.project_files), R.string.view, "Alt+1", canEdit) { showTree = true; w.tool = Tool.Project })
        add(act("devices", context.getString(R.string.devices_and_debugging), R.string.view, "Alt+5", canEdit) { w.tool = Tool.Devices })
        add(act("output", context.getString(R.string.build_output), R.string.view, "Alt+4", canEdit) { w.tool = Tool.Output })
        add(act("preview", context.getString(R.string.preview_layout), R.string.view, enabled = active?.node?.path?.contains(Regex("(^|/)res/layout[^/]*/[^/]+\\.xml$")) == true) { dialog = "preview" })
        add(act("build", context.getString(R.string.build_debug_apk), R.string.build, "Ctrl+F9", idle) { w.command("build") })
        add(act("run", context.getString(R.string.build_and_run_on_device), R.string.build, "Shift+F10", idle && w.devices.connected && !w.devices.busy) { w.command("build", true) })
        add(act("openApk", context.getString(R.string.install_apk_on_this_device), R.string.build, enabled = idle && w.apk != null) {
            w.apk?.let { openApk(Uri.fromFile(it)) }
        })
        add(act("exportApk", context.getString(R.string.export_apk), R.string.build, enabled = idle && w.apk != null) { exportFile(true) })
        add(act("install", context.getString(R.string.install_on_device), R.string.build, enabled = idle && w.apk != null && w.devices.connected && !w.devices.busy) {
            w.tool = Tool.Devices; w.apk?.let { w.devices.install(it) }
        })
        for (task in listOf("assembleDebug", "test", "lint", "tasks", "")) {
            add(act("gradle-$task", if (task.isEmpty()) context.getString(R.string.custom_gradle_tasks) else "Gradle · $task", R.string.build, enabled = idle) {
                gradleTask = task; savedDialog("gradle")
            })
        }
        add(act("attach", context.getString(R.string.attach_debugger), R.string.debug, "Ctrl+Alt+F5", w.devices.connected) { w.tool = Tool.Devices; w.devices.processes() })
        add(act("breakpoint", context.getString(R.string.toggle_line_breakpoint), R.string.debug, "Ctrl+F8", w.devices.attached && editor != null) { w.devices.toggleBreakpoint(className, editor!!.line) })
        add(act("step", context.getString(R.string.step_over), R.string.debug, "F8", w.devices.paused, w.devices::stepOver))
        add(act("resume", context.getString(R.string.resume_execution), R.string.debug, "F9", w.devices.paused, w.devices::resume))
        val devices = w.devices
        fun deviceAction(id: String, label: String, enabled: Boolean, run: () -> Unit) {
            add(act(id, label, R.string.debug, enabled = canEdit && enabled) { w.tool = Tool.Devices; run() })
        }
        deviceAction("localDevice", context.getString(R.string.debug_this_device), !devices.connected && !devices.busy, devices::localDevice)
        deviceAction("usb", context.getString(R.string.connect_usb), !devices.connected && !devices.busy, devices::chooseUsb)
        deviceAction("wifiPair", context.getString(R.string.pair_over_wi_fi), !devices.connected && !devices.busy) { devices.network(DeviceController.Connection.PAIR) }
        deviceAction("wifiConnect", context.getString(R.string.connect_paired_device), !devices.connected && !devices.busy) { devices.network(DeviceController.Connection.WIRELESS) }
        deviceAction("tcpConnect", context.getString(R.string.connect_over_adb_tcp), !devices.connected && !devices.busy) { devices.network(DeviceController.Connection.TCP) }
        deviceAction("disconnect", context.getString(R.string.disconnect_device), devices.connected, devices::disconnect)
        deviceAction("deviceShell", context.getString(R.string.target_device_shell), devices.connected && !devices.busy, devices::shell)
        deviceAction("logcat", if (devices.logging) context.getString(R.string.stop_logcat) else context.getString(R.string.start_logcat), devices.connected, devices::toggleLog)
        deviceAction("logFilter", context.getString(R.string.filter_logs), devices.connected, devices::filterLog)
        deviceAction("inspect", context.getString(R.string.inspect_screen), devices.connected && !devices.busy, devices::inspect)
        deviceAction("launch", context.getString(R.string.launch_target_app), devices.connected && !devices.busy, devices::requestLaunch)
        deviceAction("classes", context.getString(R.string.loaded_classes), devices.attached) { devices.classes(className, editor?.line ?: 1) }
        deviceAction("stack", context.getString(R.string.call_stack), devices.paused, devices::stack)
        deviceAction("locals", context.getString(R.string.local_variables), devices.paused, devices::locals)
        deviceAction("detach", context.getString(R.string.detach_debugger), devices.attached, devices::detach)
        for ((cmd, label) in mapOf("status" to context.getString(R.string.working_tree_status), "diff" to context.getString(R.string.view_diff), "log" to context.getString(R.string.commit_history)))
            add(act("git-$cmd", label, R.string.git, enabled = idle) { w.command(cmd) })
        add(act("licenses", context.getString(R.string.open_source_licenses), R.string.tools) { dialog = "licenses" })
        add(act("keymap", context.getString(R.string.keyboard_shortcuts), R.string.tools) { dialog = "keymap" })
        add(act("sdk", context.getString(R.string.sdk_manager), R.string.tools) { dialog = "sdk" })
        add(act("palette", context.getString(R.string.find_action), R.string.tools, "Ctrl+Shift+A") { dialog = "palette" })
    }
    val actions = defaults.map(keymap::apply)
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(p) { rootFocus.requestFocus() }
    val notice = w.message
    LaunchedEffect(notice) { if (notice != null) { snack.showSnackbar(notice); if (w.message == notice) w.message = null } }
    BackHandler(p != null && dialog.isEmpty() && !newProjectVisible) {
        if (w.tool != Tool.Editor && active != null) w.tool = Tool.Editor
        else if (idle) w.closeProject()
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { key ->
            val event = key.nativeKeyEvent
            val action = if (dialog.isEmpty() && !newProjectVisible) actions.firstOrNull { it.enabled && it.matches(event) } else null
            if (action == null) false else { action.run(); true }
        }.focusRequester(rootFocus).focusable(),
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (p != null) Workbench(w, actions, showTree, { showTree = it }, className, ::openEntry)
            else Welcome(w, newProject, openTree, importZip, { dialog = "sdk" }, { dialog = "licenses" })
            if (w.busy && !newProjectVisible) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    val dismiss = { dialog = "" }
    when (dialog) {
        "licenses" -> LicensePage(dismiss)
        "keymap" -> KeymapDialog(actions, defaults, keymap, dismiss)
        "sdk" -> SdkManager(dismiss)
        "palette" -> ActionPalette(actions, dismiss)
        "file", "folder" -> if (p != null) NameDialog(dialog == "folder", w.selectedDir?.path.orEmpty().ifEmpty { p.root.name }, dismiss) { name ->
            w.create(name, dialog == "folder"); dismiss()
        }
        "copyNode", "moveNode", "renameNode", "deleteNode" -> w.selectedNode?.let { node ->
            NodeChangeDialog(node, dialog, dismiss) { value -> w.changeNode(node, dialog, value); dismiss() }
        }
        "findFile" -> if (p != null) FindFile(p, dismiss) { openEntry(it); dismiss() }
        "findProject", "replaceProject" -> if (p != null) ProjectSearchDialog(p, onOpen = { node, line -> dismiss(); w.openFile(node, line) },
            onDismiss = dismiss, replace = dialog == "replaceProject", beforeReplace = { w.saveDocuments() }, onChanged = w::reloadChanged)
        "preview" -> if (p != null && active != null) LayoutPreviewDialog(p, active.editor.text, active.node.path, dismiss)
        "gradle" -> if (p != null) GradleTaskDialog(p, gradleTask, dismiss, { w.tool = Tool.Output; w.log(it) }, w::recordApk) { w.action { w.refreshAfterExternalRun(p) } }
    }
    if (newProjectVisible) CreateProject(w)
    DeviceDialogs(w.devices)
}

@Composable
private fun CreateProject(w: Workspace) {
    var name by rememberSaveable { mutableStateOf("") }
    var customPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val packageName = customPackage ?: remember(name) { suggestedPackage(name) }
    var parent by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { parent = it }
    LaunchedEffect(parent, w.busy) {
        val location = parent ?: return@LaunchedEffect
        if (!w.busy) {
            parent = null
            w.createProject(location, name.trim(), packageName.trim())
        }
    }
    NewProjectDialog(name, packageName,
        setName = { name = it; w.creationError = null },
        setPackageName = { customPackage = it; w.creationError = null },
        error = w.creationError, busy = w.busy || parent != null,
        dismiss = { w.newProjectVisible.value = false },
        chooseLocation = { w.creationError = null; picker.launch(null) })
}

private fun suggestedPackage(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "com.example.myapp"
    val slug = trimmed.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(240)
    val suffix = slug.ifEmpty { "app" + trimmed.hashCode().toUInt().toString(16) }
    val candidate = "com.example.$suffix"
    return if (Project.packageNameError(candidate) == null) candidate else "com.example.app$suffix"
}
