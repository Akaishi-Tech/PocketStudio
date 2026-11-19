package com.hcgstudio.pocketstudio

import com.hcgstudio.pocketstudio.plugin.RemoteConnection

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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hcgstudio.pocketstudio.plugin.PluginDiscovery
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var workspace: Workspace
    private var notificationPage by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationPage = intent.action
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        workspace = ViewModelProvider(this)[Workspace::class.java]
        savedInstanceState?.getString("location")?.let {
            if (workspace.project == null) workspace.openProject(Uri.parse(it), restoreFile = savedInstanceState.getString("document"))
        }
        if (savedInstanceState == null) workspace.reopenLastProject()
        if (savedInstanceState == null) notificationPage = intent.action
        setContent {
            StudioTheme(workspace.settings) {
                StudioApp(workspace, notificationPage) { notificationPage = null }
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
    override fun onResume() {
        super.onResume()
        workspace.refreshEnvironment()
        workspace.agents.setActivityResumed(true)
        if (workspace.settings.autoUpdate) workspace.updates.check(automatic = true)
    }
    override fun onPause() {
        workspace.agents.setActivityResumed(false)
        super.onPause()
    }
    override fun onStop() {
        workspace.flushDrafts()
        if (!isChangingConfigurations && workspace.settings.saveOnBackground) {
            workspace.action { workspace.saveDocuments() }
        }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        workspace.project?.let { outState.putString("location", (it.tree ?: it.root.uri).toString()) }
        outState.putString("document", workspace.active?.node?.path)
        super.onSaveInstanceState(outState)
    }
}

private enum class StudioPage(val depth: Int) {
    Welcome(0), Workbench(1), Settings(2), NewProject(2), Git(2), AgentAccount(2)
}

@Composable
private fun StudioApp(w: Workspace, notificationPage: String?, consumeNotification: () -> Unit) {
    val context = w.app
    val p = w.project
    val active = w.active
    var dialog by rememberSaveable(p?.root?.uri?.toString()) { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAgentAccount by rememberSaveable(p?.root?.uri?.toString()) { mutableStateOf(false) }
    var gradleTask by rememberSaveable { mutableStateOf("assembleDebug") }
    var showTree by rememberSaveable { mutableStateOf(true) }
    val newProjectVisible by w.newProjectVisible.collectAsState()
    LaunchedEffect(notificationPage, p) {
        if (p != null && (notificationPage == OPEN_AGENT || notificationPage == OPEN_DEBUG)) {
            showSettings = false
            showAgentAccount = false
            dialog = ""
            w.tool = if (notificationPage == OPEN_AGENT) Tool.Agents else Tool.Devices
            consumeNotification()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val usesSessionNotifications = w.tool == Tool.Devices || (w.tool == Tool.Agents && w.agents.session != null)
    LaunchedEffect(usesSessionNotifications) {
        if (Build.VERSION.SDK_INT >= 33 && usesSessionNotifications &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val prefs = context.getSharedPreferences("studio", 0)
            if (!prefs.getBoolean("requestedSessionNotifications", false)) {
                prefs.edit().putBoolean("requestedSessionNotifications", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
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
        if (p?.linux == null && !node.folder && node.name.endsWith(".apk", ignoreCase = true)) {
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
    val idle = canEdit && !w.running && !w.agents.working
    val editorVisible = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600 || w.tool == Tool.Editor
    val editor = active?.editor
    val className = remember(editor?.text, active?.node?.name) {
        val fileName = active?.node?.name.orEmpty()
        if (!fileName.endsWith(".java") && !fileName.endsWith(".kt")) return@remember ""
        val name = fileName.substringBeforeLast('.')
        val pkg = Regex("""(?m)^\s*package\s+([\w.`]+)""").find(editor?.text.orEmpty())?.groupValues?.get(1)?.replace("`", "")
        if (pkg == null) name else "$pkg.$name"
    }
    fun act(id: String, label: String, group: Int, shortcut: String = "", enabled: Boolean = true, run: () -> Unit) =
        StudioAction(id, label, group, shortcut, enabled, run)
    val keymap = remember { Keymap(context.getSharedPreferences("studio-keymap", 0)) }
    val defaults = buildList {
        add(act("newProject", context.getString(R.string.new_project), R.string.file, enabled = w.canSwitchProject, run = newProject))
        add(act("open", context.getString(R.string.open_folder), R.string.file, "Ctrl+O", !w.running && !w.busy, openTree))
        add(act("import", context.getString(R.string.import_zip), R.string.file, enabled = !w.running && !w.busy, run = importZip))
        add(act("settings", context.getString(R.string.settings), R.string.file, "Ctrl+Alt+S") { showSettings = true })
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
        if (p?.linux != null)
            add(act("terminal", context.getString(R.string.linux_terminal), R.string.view, "Alt+4", canEdit) { w.tool = Tool.Output })
        if (p?.linux == null) add(act("devices", context.getString(R.string.devices_and_debugging), R.string.view, "Alt+5", canEdit) { w.tool = Tool.Devices })
        if (w.androidAvailable) {
            add(act("output", context.getString(R.string.build_output), R.string.view, "Alt+4", canEdit) { w.tool = Tool.Output })
            add(act("preview", context.getString(R.string.preview_layout), R.string.view, enabled = active?.node?.path?.contains(Regex("(^|/)res/layout[^/]*/[^/]+\\.xml$")) == true) { dialog = "preview" })
            add(act("build", context.getString(R.string.build_debug_apk), R.string.build, "Ctrl+F9", idle) { w.build() })
            fun runOnDevice(debug: Boolean) {
                if (w.devices.connected) w.build(runOnDevice = true, debug = debug)
                else w.tool = Tool.Devices
            }
            add(act("run", context.getString(R.string.run_app), R.string.build, "Shift+F10", idle && !w.devices.busy) { runOnDevice(false) })
            add(act("debugRun", context.getString(R.string.debug_run), R.string.debug, "Shift+F9", idle && !w.devices.busy) { runOnDevice(true) })
            add(act("buildSdk", context.getString(R.string.build_sdk), R.string.build, enabled = idle) { dialog = "buildSdk" })
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
        }
        add(act("attach", context.getString(R.string.attach_debugger), R.string.debug, "Ctrl+Alt+F5", w.devices.connected) { w.tool = Tool.Devices; w.devices.processes() })
        add(act("breakpoint", context.getString(R.string.toggle_line_breakpoint), R.string.debug, "Ctrl+F8", className.isNotEmpty() && editorVisible) {
            editor?.let { w.devices.toggleBreakpoint(className, it.line) }
        })
        add(act("step", context.getString(R.string.step_over), R.string.debug, "F8", w.devices.paused, w.devices::stepOver))
        add(act("resume", context.getString(R.string.resume_execution), R.string.debug, "F9", w.devices.paused, w.devices::resume))
        val devices = w.devices
        fun deviceAction(id: String, label: String, enabled: Boolean, run: () -> Unit) {
            add(act(id, label, R.string.debug, enabled = canEdit && enabled) { w.tool = Tool.Devices; run() })
        }
        deviceAction("localDevice", context.getString(R.string.debug_this_device), !devices.connected && !devices.busy, devices::localDevice)
        if (w.androidAvailable) {
            deviceAction("usb", context.getString(R.string.connect_usb), !devices.connected && !devices.busy, devices::chooseUsb)
            deviceAction("wifiPair", context.getString(R.string.pair_over_wi_fi), !devices.connected && !devices.busy) { devices.network(RemoteConnection.PAIR) }
            deviceAction("wifiConnect", context.getString(R.string.connect_paired_device), !devices.connected && !devices.busy) { devices.network(RemoteConnection.WIRELESS) }
            deviceAction("tcpConnect", context.getString(R.string.connect_over_adb_tcp), !devices.connected && !devices.busy) { devices.network(RemoteConnection.TCP) }
        }
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
        add(act("git-status", context.getString(R.string.git_tab_changes), R.string.git, "Alt+9", idle) { w.openGit() })
        add(act("git-diff", context.getString(R.string.view_diff), R.string.git, enabled = idle) { w.openGit(diff = true) })
        val gitFile = (if (w.tool == Tool.Project) w.selectedNode else active?.node)?.takeUnless { it.folder }
        add(act("git-file", context.getString(R.string.git_file_history), R.string.git, enabled = idle && gitFile != null) {
            gitFile?.let { w.openGit(GitTab.History, file = it.path) }
        })
        add(act("git-log", context.getString(R.string.commit_history), R.string.git, enabled = idle) { w.openGit(GitTab.History) })
        add(act("git-branches", context.getString(R.string.git_tab_branches), R.string.git, enabled = idle) { w.openGit(GitTab.Branches) })
        add(act("git-remotes", context.getString(R.string.git_tab_remotes), R.string.git, enabled = idle) { w.openGit(GitTab.Remotes) })
        add(act("git-clone", context.getString(R.string.git_clone_action), R.string.git, enabled = idle) { dialog = "gitClone" })
        add(act("licenses", context.getString(R.string.open_source_licenses), R.string.tools) { dialog = "licenses" })
        add(act("keymap", context.getString(R.string.keyboard_shortcuts), R.string.tools) { dialog = "keymap" })
        if (w.androidAvailable) add(act("sdk", context.getString(R.string.sdk_manager), R.string.tools) { dialog = "sdk" })
        add(act("palette", context.getString(R.string.find_action), R.string.tools, "Ctrl+Shift+A") { dialog = "palette" })
    }
    val actions = defaults.map(keymap::apply)
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(p, showSettings, newProjectVisible) {
        if (!showSettings && !newProjectVisible) rootFocus.requestFocus()
    }
    val notice = w.message
    LaunchedEffect(notice) { if (notice != null) { snack.showSnackbar(notice); if (w.message == notice) w.message = null } }
    BackHandler(p != null && dialog.isEmpty() && w.git == null && !newProjectVisible && !showAgentAccount && !showSettings) {
        if (active == null) showTree = true
        w.tool = if (active != null) Tool.Editor else Tool.Project
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { key ->
            val event = key.nativeKeyEvent
            val action = if (dialog.isEmpty() && w.git == null && !newProjectVisible && !showAgentAccount && !showSettings) actions.firstOrNull { it.enabled && it.matches(event) } else null
            if (action == null) false else { action.run(); true }
        }.focusRequester(rootFocus).focusable(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).union(WindowInsets.ime),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snack, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (p != null && (w.tool == Tool.Agents || showAgentAccount)) {
                LaunchedEffect(p) { w.agents.enter(p) }
            }
            val workbenchState = key(p?.root?.uri) { rememberSaveableStateHolder() }
            val git = w.git
            val page = when {
                newProjectVisible -> StudioPage.NewProject
                git != null -> StudioPage.Git
                showSettings -> StudioPage.Settings
                p != null && showAgentAccount -> StudioPage.AgentAccount
                p != null -> StudioPage.Workbench
                else -> StudioPage.Welcome
            }
            val pageModifier = if (page == StudioPage.Workbench) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            StudioPageTransition(page, StudioPage::depth, Modifier.fillMaxSize().then(pageModifier)) { destination ->
                when (destination) {
                    StudioPage.Welcome -> Welcome(w, newProject, openTree, importZip, { dialog = "gitClone" },
                        { dialog = "plugins" }, { dialog = "licenses" }, { showSettings = true })
                    StudioPage.Workbench -> workbenchState.SaveableStateProvider("workbench") {
                        Workbench(w, actions, showTree, { showTree = it }, className, ::openEntry) {
                            if (w.agents.session == null) dialog = "agentPlugins"
                            else { w.agents.dismissError(); showAgentAccount = true }
                        }
                    }
                    StudioPage.Settings -> SettingsPage(LocalStudioSettings.current, w::updateSettings, { showSettings = false },
                        { dialog = "keymap" }, if (w.androidAvailable) ({ dialog = "sdk" }) else null, { w.updates.check(automatic = false) }, { dialog = "plugins" })
                    StudioPage.NewProject -> NewProjectPage(w) { dialog = "environmentPlugins" }
                    StudioPage.Git -> git?.let { GitPage(it) }
                    StudioPage.AgentAccount -> if (w.agents.session == null) {
                        LaunchedEffect(Unit) { showAgentAccount = false }
                    } else AgentAccountPage(checkNotNull(w.agents.session)) {
                        showAgentAccount = false
                        w.tool = Tool.Agents
                    }
                }
            }
            if (w.busy && !newProjectVisible) LinearProgressIndicator(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)))
        }
    }
    val dismiss = { dialog = "" }
    if (!w.androidAvailable && dialog in listOf("sdk", "buildSdk", "gradle", "preview")) {
        LaunchedEffect(dialog) { dismiss() }
    }
    when (dialog) {
        "gitClone" -> GitCloneDialog(dismiss) { directory -> dismiss(); w.openProject(Uri.fromFile(directory)) }
        "licenses" -> LicensePage(dismiss)
        "keymap" -> KeymapDialog(actions, defaults, keymap, dismiss)
        "plugins", "agentPlugins", "environmentPlugins" -> PluginStorePage(w.devices, initialCategory = when (dialog) {
            "agentPlugins" -> PluginDiscovery.AGENT_CATEGORY
            "environmentPlugins" -> PluginDiscovery.DEVELOPMENT_ENVIRONMENT_CATEGORY
            else -> null
        }, dismiss = dismiss)
        "sdk" -> w.androidEnvironment?.ui()?.SdkManager(dismiss)
        "buildSdk" -> if (w.androidAvailable && p != null) BuildSdkDialog(w, dismiss)
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
        "preview" -> if (p != null && active != null) w.androidEnvironment?.let { LayoutPreviewDialog(it, p, active.editor.text, active.node.path, dismiss) }
        "gradle" -> if (p != null) w.androidEnvironment?.ui()?.GradleDialog(gradleTask, { LocalGradle(p) }, dismiss, { w.tool = Tool.Output; w.log(it) }, w::recordApk) { w.action { w.refreshAfterExternalRun(p) } }
    }
    if (p != null) {
        if (w.tool == Tool.Agents || showAgentAccount) AgentToolApproval(w.agents)
    }
    DeviceDialogs(w.devices, w.androidEnvironment)
    AppUpdateDialog(w.updates)
}
