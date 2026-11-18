package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.hcgstudio.pocketstudio.codex.CodexRuntime
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CodexPane(w: Workspace, openAccount: () -> Unit) {
    val c = w.codex
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var updateDismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        c.refreshInstallation()
    }
    val manageLegacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        c.refreshInstallation()
    }
    fun startInstall() {
        val intent = c.installIntent ?: return
        try { installer.launch(intent) } catch (e: Exception) { c.error = e.localizedMessage }
        c.installIntent = null
    }
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) startInstall()
        else { c.installIntent = null; c.error = uiString(R.string.allow_pocket_studio_to_install_the_codex_runtime) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) c.refreshInstallation(true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (c.installIntent != null && c.legacyInstalled) {
        AlertDialog(
            onDismissRequest = { c.installIntent = null },
            title = { Text(stringResource(R.string.codex_migrate_runtime_title)) },
            text = { Text(stringResource(R.string.codex_migrate_runtime_message)) },
            confirmButton = { TextButton(onClick = {
                try {
                    manageLegacy.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", CodexRuntime.PACKAGE, null)))
                } catch (e: Exception) { c.installIntent = null; c.error = e.localizedMessage }
            }) { Text(stringResource(R.string.codex_open_runtime_settings)) } },
            dismissButton = { TextButton(onClick = { c.installIntent = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    LaunchedEffect(c.installIntent, c.legacyInstalled) {
        if (c.installIntent != null && !c.legacyInstalled) {
            try {
                if (context.packageManager.canRequestPackageInstalls()) startInstall()
                else allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", context.packageName, null)))
            } catch (e: Exception) { c.installIntent = null; c.error = e.localizedMessage }
        }
    }
    BackHandler(c.detail && c.approval == null) { c.back() }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (c.detail) 4.dp else 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (c.detail) StudioIconButton(R.drawable.studio_arrow_back, uiString(R.string.back_to_chats)) { c.back() }
            Text(if (c.detail) c.title.ifBlank { uiString(R.string.new_chat) } else stringResource(R.string.agents_tab_label), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!c.detail && c.version != null && c.usable)
                StudioIconButton(R.drawable.studio_add, uiString(R.string.new_chat), !c.working) { c.newChat() }
            if (!c.detail && c.version != null) RuntimeMenu(c)
            StudioIconButton(R.drawable.studio_account, uiString(R.string.account_and_usage), onClick = openAccount)
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
        c.error?.let { message ->
            Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = colors.onErrorContainer, maxLines = 6, overflow = TextOverflow.Ellipsis)
                StudioIconButton(R.drawable.studio_close, uiString(R.string.dismiss_message)) { c.error = null }
            }
        }
        val update = c.update
        if (update != null && updateDismissed != update.sha256 && !c.downloading) {
            Row(Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.codex_is_available, update.version), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(c::download, enabled = !c.working) { Text(stringResource(R.string.update)) }
                StudioIconButton(R.drawable.studio_close, uiString(R.string.update_later)) { updateDismissed = update.sha256 }
            }
        }
        when {
            !c.checked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            c.downloading -> CenterAction(R.drawable.studio_download, uiString(R.string.downloading_codex)) {
                if (c.downloadProgress in 0.001f..0.999f)
                    LinearProgressIndicator(progress = { c.downloadProgress }, modifier = Modifier.width(200.dp))
                else LinearProgressIndicator(Modifier.width(200.dp))
            }
            c.version == null -> CenterAction(R.drawable.studio_download, uiString(R.string.download_codex_runtime)) {
                Text(stringResource(R.string.download_and_install_the_runtime_to_use_codex), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Button(c::download) { Text(stringResource(R.string.download_codex)) }
            }
            !c.usable && (!c.detail && c.chats.isEmpty()) -> CenterAction(R.drawable.studio_chat, uiString(R.string.connect_to_codex)) {
                Button(openAccount) { Text(stringResource(R.string.agent_sign_in)) }
            }
            c.detail -> BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 680.dp && c.chats.isNotEmpty()) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(224.dp).fillMaxHeight()) {
                            if (c.usable) TextButton(c::newChat, enabled = !c.working,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Symbol(R.drawable.studio_add, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.new_chat))
                            }
                            ChatList(c, Modifier.fillMaxSize(), compact = true)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f)) { CodexConversation(c, !w.running && !w.busy, openAccount) }
                    }
                } else CodexConversation(c, !w.running && !w.busy, openAccount)
            }
            c.chats.isEmpty() && !c.loading -> CenterAction(R.drawable.studio_chat, uiString(R.string.no_chats_yet)) {
                Button({ if (c.usable) c.newChat() else openAccount() }) { Text(stringResource(R.string.new_chat)) }
            }
            else -> {
                if (c.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                ChatList(c, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun CodexToolApproval(c: CodexController) {
    c.approval?.let { req ->
        AlertDialog(onDismissRequest = { req.decision.complete(false) }, title = { Text(req.title) },
            text = { SelectionContainer { Text(req.description, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton({ req.decision.complete(true) }) { Text(stringResource(R.string.allow_once)) } },
            dismissButton = { TextButton({ req.decision.complete(false) }) { Text(stringResource(R.string.decline)) } })
    }
}

@Composable
private fun RuntimeMenu(c: CodexController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.codex_runtime)) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            Text("Codex ${c.version}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall)
            c.updateError?.let { Text(stringResource(R.string.update_check_failed_format, it),
                Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            DropdownMenuItem(text = { Text(stringResource(R.string.check_for_updates)) },
                onClick = { expanded = false; c.refreshInstallation(true) })
            if (c.update != null && !c.downloading) DropdownMenuItem(text = { Text(stringResource(R.string.update_runtime)) },
                enabled = !c.working, onClick = { expanded = false; c.download() })
        }
    }
}

@Composable
private fun ChatList(c: CodexController, modifier: Modifier, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(c.chats, key = { it.id }) { chat ->
            Row(Modifier.fillMaxWidth().background(
                if (compact && chat.id == c.chatId) colors.secondaryContainer else colors.surface
            ).clickable(enabled = !c.working || chat.id == c.chatId) { c.open(chat) }
                .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(R.drawable.studio_chat, null, Modifier.size(21.dp), colors.onSurfaceVariant)
                Text(chat.title.ifBlank { stringResource(R.string.new_chat) }, Modifier.weight(1f).padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c.working && chat.id == c.chatId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else if (!compact) Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(chat.updated * 1000)),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CenterAction(icon: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Symbol(icon, null, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

private fun groupActivities(lines: List<ChatLine>): List<List<ChatLine>> {
    val out = mutableListOf<MutableList<ChatLine>>()
    for (line in lines) {
        if (line.kind == ChatKind.Activity && out.lastOrNull()?.lastOrNull()?.kind == ChatKind.Activity) out.last().add(line)
        else out.add(mutableListOf(line))
    }
    return out
}

@Composable
private fun CodexConversation(c: CodexController, projectIdle: Boolean, settings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val groups by remember(c) { derivedStateOf { groupActivities(c.lines) } }
    val scroll = rememberLazyListState()
    var follow by remember(c.chatId) { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) follow = !scroll.canScrollForward
    }
    LaunchedEffect(groups.size, c.lines.lastOrNull()?.text?.length, c.working) {
        if (follow && groups.isNotEmpty()) scroll.scrollToItem(groups.lastIndex, Int.MAX_VALUE / 4)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (c.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().weight(1f)) {
            if (groups.isEmpty() && !c.loading)
                Text(stringResource(R.string.what_would_you_like_to_do_in_this), Modifier.align(Alignment.Center).padding(20.dp),
                    style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(groups, key = { it.first().id }) { group ->
                    val first = group.first()
                    when (first.kind) {
                        ChatKind.Activity -> Activities(group)
                        ChatKind.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = colors.secondaryContainer) {
                                SelectionContainer { Text(first.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                        ChatKind.Assistant -> if (first.text.isNotBlank()) CodexMarkdown(first.text)
                        ChatKind.Status -> Text(first.text, style = MaterialTheme.typography.labelMedium,
                            color = if (first.failed) colors.error else colors.onSurfaceVariant)
                        ChatKind.PlanUsageLimit -> Surface(shape = MaterialTheme.shapes.medium, color = colors.errorContainer) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(first.text, style = MaterialTheme.typography.titleMedium)
                                ManageChatGptUsage(c, primary = true)
                            }
                        }
                    }
                }
            }
        }
        if (c.working) LinearProgressIndicator(Modifier.widthIn(max = 840.dp).fillMaxWidth().height(2.dp))
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (!c.usable) Button(settings) { Text(stringResource(R.string.connect_account)) }
            else {
                CodexModelControls(c)
                OutlinedTextField(c.input, { c.input = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.message_codex)) }, maxLines = 5,
                    trailingIcon = {
                        if (c.working) FilledIconButton(c::stop) { Symbol(R.drawable.studio_stop, uiString(R.string.stop_response)) }
                        else FilledIconButton(c::send, enabled = c.input.isNotBlank() && !c.loading &&
                            !c.accountBusy && !c.modelsLoading && c.selectedModel != null && projectIdle) {
                            Symbol(R.drawable.studio_send, uiString(R.string.send))
                        }
                    })
            }
        }
    }
}

@Composable
private fun CodexModelControls(c: CodexController) {
    val model = c.selectedModel
    val enabled = !c.working && !c.accountBusy && !c.modelsLoading
    val issue = c.modelsError ?: if (!c.modelsLoading && c.models.isEmpty())
        stringResource(R.string.no_models_available_for_this_account) else null
    if (issue != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(issue, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            StudioIconButton(R.drawable.studio_refresh, stringResource(R.string.refresh_models), enabled, c::refreshModels)
        }
    }
    if (c.modelsLoading) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.loading_models), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
        }
    } else if (c.models.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            val modelName = model?.displayName ?: stringResource(R.string.choose_model)
            ComposerMenu(modelName, stringResource(R.string.model, modelName), enabled) { close ->
                c.models.forEach { option ->
                    ComposerMenuItem(option.displayName, option.id == model?.id, option.id.takeIf { it != option.displayName }) {
                        c.chooseModel(option.id)
                        close()
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.refresh_models)) }, onClick = {
                    close()
                    c.refreshModels()
                }, leadingIcon = { Symbol(R.drawable.studio_refresh, null) })
            }
            if (model != null && model.efforts.isNotEmpty()) {
                val effort = c.effort
                val label = stringResource(R.string.reasoning_effort_value, if (effort == null)
                    stringResource(R.string.automatic) else effortLabel(effort))
                ComposerMenu(label, label, enabled) { close ->
                    model.efforts.forEach { value ->
                        ComposerMenuItem(effortLabel(value), value == effort) {
                            c.chooseEffort(value)
                            close()
                        }
                    }
                }
            }
            if (model?.supportsFast == true) {
                Row(Modifier.heightIn(min = 48.dp).toggleable(value = c.fast, enabled = enabled,
                    role = Role.Switch, onValueChange = c::setFast).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fast_mode), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = c.fast, onCheckedChange = null, enabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun ComposerMenu(label: String, description: String, enabled: Boolean, content: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.widthIn(max = 220.dp).semantics { contentDescription = description },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Symbol(R.drawable.studio_expand_more, null, Modifier.size(16.dp)) })
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun ComposerMenuItem(label: String, current: Boolean, detail: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(text = {
        Column {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, onClick = onClick, modifier = Modifier.semantics { selected = current },
        trailingIcon = { if (current) Symbol(R.drawable.studio_check, null, Modifier.size(18.dp)) })
}

@Composable
private fun effortLabel(value: String): String = when (value) {
    "none" -> stringResource(R.string.effort_none)
    "minimal" -> stringResource(R.string.effort_minimal)
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    "ultra" -> stringResource(R.string.effort_ultra)
    else -> value
}

@Composable
private fun Activities(items: List<ChatLine>) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(items.first().id) { mutableStateOf(false) }
    val single = items.singleOrNull()
    val canExpand = single == null || single.detail.isNotBlank()
    val categories = items.map { it.category }.distinct()
    val summary = if (items.size == 1) items.first().text else categories.take(3).joinToString(uiString(R.string.list_separator)) { uiString(it) } +
        if (categories.size > 3) uiString(R.string.and_more_total, items.size) else " · ${items.size}"
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = canExpand) { expanded = !expanded }
            .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (R.string.run_build in categories || R.string.run_command in categories) R.drawable.studio_terminal else R.drawable.studio_article,
                null, Modifier.size(18.dp), colors.onSurfaceVariant)
            Text(summary, Modifier.weight(1f).padding(horizontal = 9.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = if (items.any { it.failed }) colors.error else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            if (canExpand) Symbol(if (expanded) R.drawable.studio_expand_more else R.drawable.studio_chevron_right,
                if (expanded) uiString(R.string.collapse_activity) else uiString(R.string.expand_activity), Modifier.size(18.dp), colors.onSurfaceVariant)
        }
        if (expanded) Row(Modifier.padding(start = 8.dp).height(IntrinsicSize.Min)) {
            VerticalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(start = 12.dp)) {
                if (single != null) SelectionContainer {
                    Text(single.detail, Modifier.padding(vertical = 8.dp), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                } else items.forEach { item ->
                    var show by rememberSaveable(item.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = item.detail.isNotEmpty()) { show = !show }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(if (item.category == R.string.run_build || item.category == R.string.run_command) R.drawable.studio_terminal else R.drawable.studio_description,
                            null, Modifier.size(16.dp), colors.onSurfaceVariant)
                        Text(item.text + if (item.failed) uiString(R.string.failed) else "", Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (show) SelectionContainer { Text(item.detail, Modifier.padding(start = 24.dp, bottom = 8.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
