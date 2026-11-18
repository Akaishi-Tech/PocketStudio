package com.hcgstudio.pocketstudio

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.ic_studio), null, Modifier.size(32.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Welcome(w: Workspace, newProject: () -> Unit, open: () -> Unit, importZip: () -> Unit, sdk: () -> Unit, licenses: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand()
            Spacer(Modifier.weight(1f))
            OutlinedButton(sdk, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Symbol(R.drawable.studio_inventory_2, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sdk_manager))
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
                    WelcomeButtons(!w.busy, newProject, open, importZip)
                } else Column(Modifier.widthIn(max = 260.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeButtons(!w.busy, newProject, open, importZip, Modifier.fillMaxWidth())
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
private fun WelcomeButtons(enabled: Boolean, newProject: () -> Unit, open: () -> Unit, importZip: () -> Unit, modifier: Modifier = Modifier) {
    Button(newProject, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_add, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_project))
    }
    FilledTonalButton(open, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_folder_open, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_folder))
    }
    FilledTonalButton(importZip, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Symbol(R.drawable.studio_upload_file, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_zip))
    }
}

@Composable
internal fun NewProjectDialog(
    name: String, packageName: String, setName: (String) -> Unit, setPackageName: (String) -> Unit,
    error: String?, busy: Boolean, dismiss: () -> Unit, chooseLocation: () -> Unit
) {
    val nameError = Project.projectNameError(name.trim())
    val packageError = Project.packageNameError(packageName.trim())
    val showNameError = name.isNotEmpty() && nameError != null
    val showPackageError = packageName.isNotEmpty() && packageError != null
    val ready = !busy && nameError == null && packageError == null
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.new_project)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, setName, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.project_name)) }, isError = showNameError,
                    supportingText = if (showNameError) { { Text(nameError.orEmpty()) } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(packageName, setPackageName, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.package_name)) }, isError = showPackageError,
                    supportingText = if (showPackageError) { { Text(packageError.orEmpty()) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (ready) chooseLocation() }))
                if (error != null) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(chooseLocation, enabled = ready) { Text(stringResource(R.string.choose_location)) } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
    )
}

private data class ToolItem(val tool: Tool, @get:StringRes val label: Int, val icon: Int)
private val tools = listOf(
    ToolItem(Tool.Project, R.string.project, R.drawable.studio_account_tree),
    ToolItem(Tool.Editor, R.string.editor, R.drawable.studio_code),
    ToolItem(Tool.Codex, R.string.agents_tab_label, R.drawable.studio_chat),
    ToolItem(Tool.Devices, R.string.debug, R.drawable.studio_bug_report),
    ToolItem(Tool.Output, R.string.output, R.drawable.studio_terminal)
)

@Composable
internal fun Workbench(w: Workspace, actions: List<StudioAction>, tree: Boolean, setTree: (Boolean) -> Unit, className: String, openEntry: (Project.Node) -> Unit) {
    val p = w.project ?: return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val desktop = maxWidth >= 940.dp
        val sideWidth = if (desktop) 260.dp else if (maxWidth >= 760.dp) 220.dp else 182.dp
        val height = maxHeight
        fun select(tool: Tool) {
            if (tool == Tool.Project && wide) setTree(w.tool == Tool.Codex || !tree)
            w.tool = if (wide && w.tool == tool && tool in listOf(Tool.Devices, Tool.Output, Tool.Codex)) Tool.Editor else tool
        }
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            if (w.tool != Tool.Codex) {
                WorkspaceBar(w, actions, desktop)
                HorizontalDivider(color = colors.outlineVariant)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (wide) {
                    Column(Modifier.width(60.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        tools.filter { it.tool != Tool.Editor }.forEach { item ->
                            val selected = if (item.tool == Tool.Project) tree && w.tool != Tool.Codex else w.tool == item.tool
                            val action = { select(item.tool) }
                            if (selected) FilledTonalIconButton(action) { Symbol(item.icon, stringResource(item.label)) }
                            else StudioIconButton(item.icon, stringResource(item.label), onClick = action)
                        }
                        Spacer(Modifier.weight(1f))
                        StudioIconButton(R.drawable.studio_inventory_2, uiString(R.string.sdk_manager), onClick = actions.first { it.id == "sdk" }.run)
                    }
                    VerticalDivider(color = colors.outlineVariant)
                    if (tree && w.tool != Tool.Codex) {
                        ProjectPane(w, actions, Modifier.width(sideWidth).fillMaxHeight(), openEntry)
                        VerticalDivider(color = colors.outlineVariant)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (w.tool == Tool.Codex) CodexPane(w, p)
                    if ((wide && w.tool != Tool.Codex) || w.tool == Tool.Editor) EditorPane(w, className, Modifier.fillMaxWidth().weight(1f)) {
                        if (wide) setTree(true)
                        w.tool = Tool.Project
                    }
                    if (!wide && w.tool == Tool.Project) ProjectPane(w, actions, Modifier.fillMaxSize(), openEntry)
                    if (w.tool == Tool.Devices || w.tool == Tool.Output) {
                        if (wide) HorizontalDivider()
                        val panel = if (wide) Modifier.fillMaxWidth().height((height * .39f).coerceIn(120.dp, 370.dp)) else Modifier.fillMaxSize()
                        if (w.tool == Tool.Devices) DeviceToolWindow(w.devices, className, w.active?.editor?.line ?: 1, panel)
                        else OutputPane(w, actions, panel)
                    }
                }
            }
            if (!wide) NavigationBar(containerColor = colors.surfaceContainerLow, windowInsets = WindowInsets(0,0,0,0)) {
                tools.forEach { item -> NavigationBarItem(w.tool == item.tool, { select(item.tool) },
                    icon = { Symbol(item.icon, stringResource(item.label)) }, label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }, alwaysShowLabel = true) }
            }
            else if (w.tool != Tool.Codex) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().height(28.dp).background(colors.surfaceContainerLow).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val file = w.active
                    Text(file?.node?.path ?: p.root.name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (file != null) Text("${file.editor.line}:${file.editor.column}     UTF-8", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBar(w: Workspace, actions: List<StudioAction>, desktop: Boolean) {
    val groups = actions.map { it.group }.distinct()
    var menu by remember { mutableStateOf<Int?>(null) }
    val theme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(if (desktop) 52.dp else 56.dp).background(theme.surfaceContainerLow).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (desktop) {
            Image(painterResource(R.drawable.ic_studio), null, Modifier.padding(horizontal = 8.dp).size(28.dp))
            // Anchor each group beneath its label, so the bar also works with a mouse.
            groups.forEach { group ->
                Box {
                    TextButton({ menu = group }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text(stringResource(group), maxLines = 1) }
                    ActionsMenu(menu == group, { menu = null }, actions.filter { it.group == group })
                }
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Box {
                StudioIconButton(R.drawable.studio_menu, uiString(R.string.main_menu)) { menu = R.string.main_menu }
                DropdownMenu(menu == R.string.main_menu, { menu = null }) {
                    groups.forEach { g -> DropdownMenuItem({ Text(stringResource(g)) }, { menu = g }, trailingIcon = { Symbol(R.drawable.studio_chevron_right, null) }) }
                }
                ActionsMenu(menu in groups, { menu = null }, actions.filter { it.group == menu })
            }
        }
        Text(w.project?.root?.name.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (desktop) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
        val build = actions.first { it.id == "build" }
        if (w.running) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
        else StudioIconButton(R.drawable.studio_build, build.label, build.enabled, build.run)
        if (w.devices.connected) {
            val run = actions.first { it.id == "run" }
            StudioIconButton(R.drawable.studio_play_arrow, run.label, run.enabled, run.run)
        }
        StudioIconButton(R.drawable.studio_search, uiString(R.string.find_action), onClick = actions.first { it.id == "palette" }.run)
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
        Row(Modifier.fillMaxWidth().clickable { w.selectedDir = p.root; w.selectedNode = p.root }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Symbol(R.drawable.studio_folder_open, null, Modifier.size(19.dp), colors.primary)
            Text(p.root.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                    .padding(start = (6 + minOf(level, 6) * 12).dp, end = 6.dp).heightIn(min = 38.dp),
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
            val scroll = rememberScrollState()
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll).background(colors.surfaceContainerLow)) {
                w.tabs.forEach { tab ->
                    val active = tab === doc
                    Column(Modifier.background(if (active) colors.surface else colors.surfaceContainerLow)) {
                        Row(Modifier.height(44.dp).clickable { w.select(tab) }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Symbol(R.drawable.studio_code, null, Modifier.size(15.dp), colors.primary)
                            Text(tab.node.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                            if (tab.dirty) {
                                Symbol(R.drawable.studio_fiber_manual_record, stringResource(R.string.unsaved_changes),
                                    Modifier.padding(start = 4.dp).size(10.dp), colors.primary)
                            }
                            IconButton({ w.closeTab(tab) }, modifier = Modifier.size(40.dp)) { Symbol(R.drawable.studio_close, uiString(R.string.close_format, tab.node.name), Modifier.size(14.dp)) }
                        }
                        if (active) HorizontalDivider(thickness = 2.dp, color = colors.primary)
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            StudioEditor(doc.editor, Modifier.fillMaxSize(), w.devices.markersFor(className)) { line -> w.devices.toggleBreakpoint(className, line) }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(showProject) { Symbol(R.drawable.studio_folder_open, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.select_a_project_file)) }
        }
    }
}
@Composable
private fun OutputPane(w: Workspace, actions: List<StudioAction>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.background(colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(colors.surfaceContainerLow).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.build_output), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(actions.first { it.id == "sdk" }.run) { Text(stringResource(R.string.sdk_manager)) }
            if (w.apk != null) {
                val export = actions.first { it.id == "exportApk" }
                StudioIconButton(R.drawable.studio_download, export.label, export.enabled, export.run)
            }
        }
        if (w.apk != null) {
            val install = actions.first { it.id == "openApk" }
            FilledTonalButton(install.run, Modifier.padding(start = 12.dp, top = 4.dp), enabled = install.enabled) {
                Symbol(R.drawable.studio_android, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(install.label)
            }
        }
        if (w.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val emptyOutput = stringResource(R.string.no_output_yet)
        val lines = remember(w.output, emptyOutput) { w.output.trimEnd().ifBlank { emptyOutput }.lines() }
        val diagnostics = remember { Regex("""([\w.-]+/src/(?:main|debug|test|androidTest)/[^:()]+?\.(?:java|kt|xml|aidl))(?::|\s+\(at line )(\d+)""") }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            items(lines) { line ->
                val match = diagnostics.find(line)
                if (match != null) Text(line, Modifier.clickable { w.diagnostic(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1) },
                    color = colors.primary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                else SelectionContainer { Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp) }
            }
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
