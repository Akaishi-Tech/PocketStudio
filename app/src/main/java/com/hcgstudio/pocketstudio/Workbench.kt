package com.hcgstudio.pocketstudio

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import android.net.Uri
import kotlinx.coroutines.*

@Composable
internal fun Symbol(id: Int, description: String?, modifier: Modifier = Modifier.size(22.dp), tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), description, modifier, tint)
}
@Composable
internal fun StudioIconButton(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) { Symbol(icon, label) }
}
@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.ic_studio), null, Modifier.size(32.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Welcome(w: Workspace, newProject: () -> Unit, open: () -> Unit, importZip: () -> Unit, clone: () -> Unit, sdk: () -> Unit,
                     licenses: () -> Unit, settings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val showSdkLabel = maxWidth >= 360.dp * (LocalDensity.current.fontScale * LocalStudioSettings.current.uiFontSize / 14f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Brand(Modifier.weight(1f))
                if (showSdkLabel) {
                    OutlinedButton(sdk, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Symbol(R.drawable.studio_inventory_2, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sdk_manager))
                    }
                } else StudioIconButton(R.drawable.studio_inventory_2, stringResource(R.string.sdk_manager), onClick = sdk)
                StudioIconButton(R.drawable.studio_settings, stringResource(R.string.settings), onClick = settings)
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 640.dp
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = if (wide) 38.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(30.dp), color = colors.primaryContainer, modifier = Modifier.size(84.dp)) {
                    Box(contentAlignment = Alignment.Center) { Symbol(R.drawable.studio_folder_open, null, Modifier.size(44.dp), colors.onPrimaryContainer) }
                }
                Spacer(Modifier.height(24.dp))
                if (wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeButtons(w.canSwitchProject, newProject, open, importZip, clone)
                } else Column(Modifier.widthIn(max = 260.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeButtons(w.canSwitchProject, newProject, open, importZip, clone, Modifier.fillMaxWidth())
                }
                TextButton(licenses) { Text(androidx.compose.ui.res.stringResource(R.string.open_source_licenses)) }
                if (w.recent.isNotEmpty()) {
                    Spacer(Modifier.height(36.dp))
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                        Text(stringResource(R.string.recent_projects), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        w.recent.forEach { (uri, name) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !w.busy) { w.openProject(Uri.parse(uri)) }
                                .padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Symbol(R.drawable.studio_folder, null, Modifier.size(22.dp), colors.primary)
                                Text(name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Symbol(R.drawable.studio_chevron_right, null, Modifier.size(18.dp), colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeButtons(enabled: Boolean, newProject: () -> Unit, open: () -> Unit, importZip: () -> Unit, clone: () -> Unit, modifier: Modifier = Modifier) {
    Button(newProject, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_add, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_project))
    }
    FilledTonalButton(open, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_folder_open, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_folder))
    }
    FilledTonalButton(importZip, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_upload_file, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_zip))
    }
    FilledTonalButton(clone, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_git_branch, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.git_clone_action))
    }
}

private data class ToolItem(val tool: Tool, @get:StringRes val label: Int, val icon: Int)
private val tools = listOf(
    ToolItem(Tool.Project, R.string.project, R.drawable.studio_account_tree),
    ToolItem(Tool.Editor, R.string.editor, R.drawable.studio_code),
    ToolItem(Tool.Agents, R.string.agents_tab_label, R.drawable.studio_chat),
    ToolItem(Tool.Devices, R.string.debug, R.drawable.studio_bug_report),
    ToolItem(Tool.Output, R.string.output, R.drawable.studio_terminal)
)

@Composable
internal fun Workbench(w: Workspace, actions: List<StudioAction>, tree: Boolean, setTree: (Boolean) -> Unit, className: String, openEntry: (Project.Node) -> Unit, openAgentAccount: () -> Unit) {
    val p = w.project ?: return
    val colors = MaterialTheme.colorScheme
    val settings = LocalStudioSettings.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val desktop = maxWidth >= 940.dp * (LocalDensity.current.fontScale * settings.uiFontSize / 14f).coerceAtLeast(1f)
        val sideWidth = if (desktop) 260.dp else if (maxWidth >= 760.dp) 220.dp else 182.dp
        val height = maxHeight
        fun select(tool: Tool) {
            if (tool == Tool.Project && wide) setTree(w.tool == Tool.Agents || !tree)
            w.tool = if (wide && w.tool == tool && tool in listOf(Tool.Devices, Tool.Output, Tool.Agents)) Tool.Editor else tool
        }
        val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        val outerInsets = if (wide) Modifier.windowInsetsPadding(bottomInset) else Modifier
        Column(Modifier.fillMaxSize().background(colors.surface).then(outerInsets)) {
            if (w.tool == Tool.Agents) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
            } else {
                WorkspaceBar(w, actions, desktop)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (wide) {
                    Column(Modifier.width(60.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        tools.filter { it.tool != Tool.Editor }.forEach { item ->
                            val selected = if (item.tool == Tool.Project) tree && w.tool != Tool.Agents else w.tool == item.tool
                            val action = { select(item.tool) }
                            if (selected) FilledTonalIconButton(action) { Symbol(item.icon, stringResource(item.label)) }
                            else StudioIconButton(item.icon, stringResource(item.label), onClick = action)
                        }
                        Spacer(Modifier.weight(1f))
                        StudioIconButton(R.drawable.studio_inventory_2, uiString(R.string.sdk_manager), onClick = actions.first { it.id == "sdk" }.run)
                        StudioIconButton(R.drawable.studio_settings, stringResource(R.string.settings), onClick = actions.first { it.id == "settings" }.run)
                    }
                    VerticalDivider(color = colors.outlineVariant)
                    if (tree && w.tool != Tool.Agents) {
                        ProjectPane(w, actions, Modifier.width(sideWidth).fillMaxHeight(), openEntry)
                        VerticalDivider(color = colors.outlineVariant)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val tool = w.tool
                    val mainPane = if (wide && tool != Tool.Agents) Tool.Editor else tool
                    StudioPageTransition(mainPane, { 0 }, Modifier.fillMaxWidth().weight(1f)) { pane ->
                        when (pane) {
                            Tool.Editor -> EditorPane(w, className, Modifier.fillMaxSize()) {
                                if (wide) setTree(true)
                                w.tool = Tool.Project
                            }
                            Tool.Project -> ProjectPane(w, actions, Modifier.fillMaxSize(), openEntry)
                            Tool.Agents -> AgentPane(w, openAgentAccount)
                            Tool.Devices -> DeviceToolWindow(w.devices, className, w.active?.editor?.line ?: 1)
                            Tool.Output -> OutputPane(w, actions, Modifier.fillMaxSize())
                        }
                    }
                    if (wide) {
                        val bottomPane = tool.takeIf { it == Tool.Devices || it == Tool.Output }
                        StudioPageTransition(bottomPane, { 0 }, Modifier.fillMaxWidth()) { pane ->
                            if (pane != null) Column {
                                HorizontalDivider()
                                val panel = Modifier.fillMaxWidth().height((height * .39f).coerceIn(120.dp, 370.dp))
                                if (pane == Tool.Devices) DeviceToolWindow(w.devices, className, w.active?.editor?.line ?: 1, panel)
                                else OutputPane(w, actions, panel)
                            }
                        }
                    }
                }
            }
            if (settings.showStatusBar && ((wide && w.tool != Tool.Agents) || (w.tool == Tool.Editor && w.active != null))) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = if (settings.compactMode) 22.dp else 28.dp)
                    .background(colors.surfaceContainerLow).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val file = w.active
                    Text(file?.node?.path ?: p.root.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (file != null) Text("${file.editor.line}:${file.editor.column}     UTF-8", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            if (!wide) NavigationBar(modifier = Modifier.background(colors.surfaceContainerLow)
                .windowInsetsPadding(bottomInset).then(if (settings.compactMode) Modifier.height(56.dp) else Modifier),
                containerColor = colors.surfaceContainerLow, windowInsets = WindowInsets(0,0,0,0)) {
                tools.forEach { item -> NavigationBarItem(w.tool == item.tool, { select(item.tool) },
                    icon = { Symbol(item.icon, if (settings.compactMode) stringResource(item.label) else null) },
                    label = if (settings.compactMode) null else { { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceBar(w: Workspace, actions: List<StudioAction>, desktop: Boolean) {
    val colors = MaterialTheme.colorScheme
    val settings = LocalStudioSettings.current
    val projectName = w.project?.root?.name.orEmpty()
    val topInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    if (!desktop) {
        TopAppBar(
            title = { Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                if (settings.showMainToolbarActions) RunOptions(actions)
                Box {
                    var open by remember { mutableStateOf(false) }
                    StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.main_menu)) { open = true }
                    DropdownMenu(open, { open = false }) {
                        listOf("palette", "findFile", "saveAll", "git-status", "settings").forEach { id ->
                            val action = actions.first { it.id == id }
                            val label = if (id == "git-status") stringResource(R.string.git) else action.label
                            DropdownMenuItem({ Text(label) }, { open = false; action.run() }, enabled = action.enabled)
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            expandedHeight = if (settings.compactMode) 48.dp else 64.dp,
            windowInsets = topInset
        )
        return
    }

    var menu by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow).windowInsetsPadding(topInset)
        .heightIn(min = if (settings.compactMode) 48.dp else 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_studio), null, Modifier.padding(horizontal = 8.dp).size(28.dp))
        actions.map { it.group }.distinct().forEach { group ->
            Box {
                TextButton({ menu = group }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text(stringResource(group), maxLines = 1) }
                ActionsMenu(menu == group, { menu = null }, actions.filter { it.group == group })
            }
        }
        Text(projectName, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        if (settings.showMainToolbarActions) {
            RunOptions(actions)
            StudioIconButton(R.drawable.studio_search, stringResource(R.string.find_action), onClick = actions.first { it.id == "palette" }.run)
        }
    }
}

@Composable
private fun RunOptions(actions: List<StudioAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton({ open = true }) {
            Symbol(R.drawable.studio_play_arrow, stringResource(R.string.run_options))
        }
        DropdownMenu(open, { open = false }) {
            listOf("run" to R.drawable.studio_play_arrow, "debugRun" to R.drawable.studio_bug_report).forEach { (id, icon) ->
                val action = actions.first { it.id == id }
                DropdownMenuItem({ Text(action.label) }, { open = false; action.run() },
                    leadingIcon = { Symbol(icon, null) }, enabled = action.enabled)
            }
        }
    }
}

@Composable
private fun ActionsMenu(open: Boolean, close: () -> Unit, actions: List<StudioAction>) {
    DropdownMenu(open, close, modifier = Modifier.heightIn(max = 480.dp)) {
        actions.forEach { a ->
            DropdownMenuItem({ Text(a.label) }, { close(); a.run() }, enabled = a.enabled,
                trailingIcon = if (a.shortcut.isEmpty()) null else { { Text(a.shortcut, style = MaterialTheme.typography.labelSmall) } })
        }
    }
}

@Composable
private fun ProjectPane(w: Workspace, actions: List<StudioAction>, modifier: Modifier, openEntry: (Project.Node) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val compact = LocalStudioSettings.current.compactMode
    var add by remember { mutableStateOf(false) }
    val p = w.project ?: return
    Column(modifier.background(colors.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.project), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Box {
                StudioIconButton(R.drawable.studio_add, uiString(R.string.label_new)) { add = true }
                ActionsMenu(add, { add = false }, actions.filter { it.id == "newFile" || it.id == "newDir" })
            }
            StudioIconButton(R.drawable.studio_refresh, uiString(R.string.refresh_project), onClick = w::refreshTree)
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
        Row(Modifier.fillMaxWidth().padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { w.selectedDir = p.root; w.selectedNode = p.root }
                .heightIn(min = 48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(R.drawable.studio_folder_open, null, Modifier.size(19.dp), colors.primary)
                Text(p.root.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            val git = actions.first { it.id == "git-status" }
            StudioIconButton(R.drawable.studio_git_branch, stringResource(R.string.git), git.enabled, git.run)
        }
        val rows = buildList<Pair<Int, Project.Node>> {
            fun append(path: String, level: Int) {
                w.folders[path]?.forEach { node -> add(level to node); if (node.folder) append(node.path, level + 1) }
            }
            append("", 0)
        }
        if (rows.isEmpty()) Text(stringResource(R.string.this_folder_is_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn {
            items(rows, key = { it.second.path }) { (level, node) ->
                val expanded = node.folder && w.folders.containsKey(node.path)
                val selected = if (node.folder) w.selectedDir?.path == node.path else w.selectedNode?.path == node.path
                val icon = when {
                    expanded -> R.drawable.studio_folder_open
                    node.folder -> R.drawable.studio_folder
                    node.name.endsWith(".java") || node.name.endsWith(".kt") -> R.drawable.studio_code
                    node.name.endsWith(".gradle") || node.name.endsWith(".kts") -> R.drawable.studio_build
                    node.name.endsWith(".xml") -> R.drawable.studio_article
                    node.name.endsWith(".apk", ignoreCase = true) -> R.drawable.studio_android
                    else -> R.drawable.studio_description
                }
                Row(Modifier.fillMaxWidth().background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .clickable { openEntry(node) }
                    .padding(start = (6 + minOf(level, 6) * 12).dp, end = 6.dp).heightIn(min = if (compact) 30.dp else 38.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (node.folder) Symbol(if (expanded) R.drawable.studio_expand_more else R.drawable.studio_chevron_right, null, Modifier.size(16.dp))
                    else Spacer(Modifier.width(16.dp))
                    Symbol(icon, null, Modifier.size(17.dp), if (node.folder) colors.primary else colors.onSurfaceVariant)
                    Text(node.name, Modifier.padding(start = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EditorPane(w: Workspace, className: String, modifier: Modifier, showProject: () -> Unit) {
    val doc = w.active
    val colors = MaterialTheme.colorScheme
    Column(modifier.background(colors.surface)) {
        if (doc != null) {
            if (LocalStudioSettings.current.showEditorTabs) EditorTabs(w)
            StudioPageTransition(doc, { 0 }, Modifier.fillMaxSize()) { tab ->
                StudioEditor(tab.editor, Modifier.fillMaxSize(), w.devices.markersFor(className)) { line ->
                    if (className.isNotEmpty()) w.devices.toggleBreakpoint(className, line)
                }
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(showProject) { Symbol(R.drawable.studio_folder_open, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.select_a_project_file)) }
        }
    }
}
@Composable
private fun EditorTabs(w: Workspace) {
    val colors = MaterialTheme.colorScheme
    val settings = LocalStudioSettings.current
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow)
        .horizontalScroll(rememberScrollState()).selectableGroup()) {
        w.tabs.forEach { tab ->
            val active = tab === w.active
            Column(Modifier.width(IntrinsicSize.Max).background(if (active) colors.surface else colors.surfaceContainerLow)) {
                Row(Modifier.selectable(active, role = Role.Tab) { w.select(tab) }
                    .heightIn(min = if (settings.compactMode) 36.dp else 44.dp)
                    .padding(start = 12.dp, end = if (settings.showTabCloseButtons) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Symbol(R.drawable.studio_code, null, Modifier.size(15.dp), colors.primary)
                    Text(tab.node.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                    if (tab.dirty) {
                        Symbol(R.drawable.studio_fiber_manual_record, stringResource(R.string.unsaved_changes),
                            Modifier.padding(start = 4.dp).size(10.dp), colors.primary)
                    }
                    if (settings.showTabCloseButtons) {
                        IconButton({ w.closeTab(tab) }, modifier = Modifier.size(if (settings.compactMode) 32.dp else 40.dp)) {
                            Symbol(R.drawable.studio_close, stringResource(R.string.close_format, tab.node.name), Modifier.size(14.dp))
                        }
                    }
                }
                if (active) HorizontalDivider(thickness = 2.dp, color = colors.primary)
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}
@Composable
private fun OutputPane(w: Workspace, actions: List<StudioAction>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val hasApk = w.apk != null
    Column(modifier.background(colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.build_output), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hasApk) {
                val install = actions.first { it.id == "openApk" }
                TextButton(install.run, enabled = install.enabled) { Text(stringResource(R.string.install_apk)) }
            }
            Box {
                var open by remember { mutableStateOf(false) }
                StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.output_options)) { open = true }
                DropdownMenu(open, { open = false }) {
                    val entries = listOfNotNull("build", "buildSdk", "sdk", "exportApk".takeIf { hasApk })
                    entries.forEach { id ->
                        val action = actions.first { it.id == id }
                        DropdownMenuItem({ Text(action.label) }, { open = false; action.run() }, enabled = action.enabled)
                    }
                }
            }
        }
        Surface(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow) {
            Column {
                if (w.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (w.output.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!w.running) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Symbol(R.drawable.studio_terminal, null, Modifier.size(28.dp), colors.onSurfaceVariant)
                            Text(stringResource(R.string.no_output_yet), Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                } else ConsoleLog(w.output, w::diagnostic)
            }
        }
    }
}

@Composable
private fun ConsoleLog(output: String, openDiagnostic: (String, Int) -> Unit) {
    val lines = remember(output) { output.trimEnd('\n').lines() }
    val diagnostics = remember { Regex("""([\w.-]+/src/(?:main|debug|test|androidTest)/[^:()]+?\.(?:java|kt|xml|aidl))(?::|\s+\(at line )(\d+)""") }
    val settings = LocalStudioSettings.current
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
        fontSize = settings.consoleFontSize.sp, lineHeight = (settings.consoleFontSize * 1.5f).sp)
    val state = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.canScrollForward }.collect { (scrolling, canScrollForward) ->
            if (scrolling) follow = !canScrollForward
        }
    }
    LaunchedEffect(output) {
        if (follow && !state.isScrollInProgress) state.scrollToItem(lines.lastIndex)
    }
    val console = if (settings.consoleSoftWraps) Modifier else Modifier.horizontalScroll(rememberScrollState())
    LazyColumn(Modifier.fillMaxSize().then(console), state = state, contentPadding = PaddingValues(12.dp)) {
        items(lines) { line ->
            val match = diagnostics.find(line)
            if (match == null) SelectionContainer { Text(line, style = textStyle, softWrap = settings.consoleSoftWraps) }
            else Text(line, Modifier.clickable { openDiagnostic(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1) },
                color = MaterialTheme.colorScheme.primary, style = textStyle, softWrap = settings.consoleSoftWraps)
        }
    }
}

@Composable
internal fun NameDialog(folder: Boolean, path: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && name !in listOf(".", "..") && '/' !in name
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (folder) uiString(R.string.new_folder) else uiString(R.string.new_file)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, supportingText = { Text(path) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (valid) submit(name) })) },
        confirmButton = { TextButton({ submit(name) }, enabled = valid) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } })
}
@Composable
internal fun FindFile(p: Project, dismiss: () -> Unit, open: (Project.Node) -> Unit) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<Project.Node>()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        items = emptyList(); status = ""
        if (query.isBlank()) return@LaunchedEffect
        delay(140)
        try {
            items = runInterruptible(Dispatchers.IO) { p.search(query) }
            if (items.isEmpty()) status = uiString(R.string.no_matching_files)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.localizedMessage.orEmpty() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.go_to_file)) },
        text = { Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.file_name_or_path)) }, singleLine = true)
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(items, key = { it.path }) { file ->
                    Text(file.path, Modifier.fillMaxWidth().clickable { open(file) }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } }, confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
internal fun NodeChangeDialog(node: Project.Node, op: String, dismiss: () -> Unit, run: (String) -> Unit) {
    val delete = op == "deleteNode"
    val rename = op == "renameNode"
    val label = when (op) { "deleteNode" -> uiString(R.string.delete); "renameNode" -> uiString(R.string.rename); "moveNode" -> uiString(R.string.move); else -> uiString(R.string.copy) }
    val initial = if (rename) node.name else node.path
    var value by remember(node, op) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("$label ${node.name}") },
        text = { if (delete) Text(if (node.folder) uiString(R.string.permanently_delete_and_its_contents, node.path) else uiString(R.string.permanently_delete, node.path))
            else OutlinedTextField(value, { value = it }, singleLine = true,
                label = { Text(if (rename) uiString(R.string.new_name) else uiString(R.string.destination_path_relative_to_the_project_root)) }) },
        confirmButton = { TextButton({ run(value) }, enabled = delete || (value.isNotBlank() && value != initial && (!rename || '/' !in value))) { Text(label) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } })
}
