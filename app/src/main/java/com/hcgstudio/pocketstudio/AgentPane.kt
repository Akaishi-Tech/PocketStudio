package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import com.hcgstudio.pocketstudio.agent.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AgentPane(w: Workspace, openAccount: () -> Unit, modifier: Modifier = Modifier) {
    val agents = w.agents
    val c = agents.session
    val colors = MaterialTheme.colorScheme
    BackHandler(c?.detail == true && agents.approval == null) { c?.back() }
    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (c?.detail == true) 4.dp else 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (c?.detail == true) StudioIconButton(R.drawable.studio_arrow_back, uiString(R.string.back_to_chats), onClick = c::back)
            Text(if (c?.detail == true) c.title.ifBlank { uiString(R.string.new_chat) }
                else agents.selected?.label ?: stringResource(R.string.agents_tab_label), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (agents.plugins.size > 1 && c?.detail != true) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    StudioIconButton(R.drawable.studio_expand_more, stringResource(R.string.agent_select_provider),
                        !agents.working && c?.accountBusy != true) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        agents.plugins.forEach { plugin ->
                            DropdownMenuItem(text = { Text(plugin.label) }, onClick = { expanded = false; agents.select(plugin) })
                        }
                    }
                }
            }
            if (c != null) {
                if (!c.detail && c.usable) StudioIconButton(R.drawable.studio_add, uiString(R.string.new_chat), !c.working, c::newChat)
                StudioIconButton(R.drawable.studio_account, uiString(R.string.account_and_usage), onClick = openAccount)
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
        (agents.error ?: c?.error)?.let { message ->
            Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = colors.onErrorContainer, maxLines = 6, overflow = TextOverflow.Ellipsis)
                StudioIconButton(R.drawable.studio_close, uiString(R.string.dismiss_message)) { agents.dismissError() }
            }
        }
        when {
            !agents.checked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            c == null -> CenterAction(R.drawable.studio_chat, stringResource(R.string.agent_no_provider)) {
                Button(openAccount) { Text(stringResource(R.string.agent_open_store)) }
            }
            else -> ChatBrowser(c, !w.running && !w.busy, openAccount)
        }
    }
}

@Composable
private fun ChatBrowser(c: AgentSession, projectIdle: Boolean, openAccount: () -> Unit) {
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && c.detail && c.chats.isNotEmpty()) {
                Column(Modifier.width(224.dp).fillMaxHeight()) {
                    if (c.usable) TextButton(c::newChat, enabled = !c.working,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Symbol(R.drawable.studio_add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.new_chat))
                    }
                    ChatList(c, listState, compact = true)
                }
                VerticalDivider()
            }
            StudioPageTransition(c.detail, { if (it && !wide) 1 else 0 }, Modifier.weight(1f).fillMaxHeight()) { detail ->
                when {
                    detail -> AgentConversation(c, projectIdle, openAccount)
                    !c.usable && c.chats.isEmpty() -> CenterAction(R.drawable.studio_chat, uiString(R.string.connect_account)) {
                        Button(openAccount) { Text(stringResource(R.string.agent_sign_in)) }
                    }
                    c.chats.isEmpty() && !c.loading && !c.working -> CenterAction(R.drawable.studio_chat, uiString(R.string.no_chats_yet)) {
                        Button({ if (c.usable) c.newChat() else openAccount() }) { Text(stringResource(R.string.new_chat)) }
                    }
                    else -> Column {
                        if (c.loading || (c.working && c.chats.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ChatList(c, listState)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentToolApproval(c: Agents) {
    c.approval?.let { req ->
        AlertDialog(onDismissRequest = { req.decision.complete(false) }, title = { Text(req.title) },
            text = { SelectionContainer { Text(req.description, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton({ req.decision.complete(true) }) { Text(stringResource(R.string.allow_once)) } },
            dismissButton = { TextButton({ req.decision.complete(false) }) { Text(stringResource(R.string.decline)) } })
    }
}

@Composable
private fun ChatList(c: AgentSession, state: LazyListState, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
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
private fun AgentConversation(c: AgentSession, projectIdle: Boolean, settings: () -> Unit) {
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
                        ChatKind.Assistant -> if (first.text.isNotBlank()) AgentMarkdown(first.text)
                        ChatKind.Status -> Text(first.text, style = MaterialTheme.typography.labelMedium,
                            color = if (first.failed) colors.error else colors.onSurfaceVariant)
                        ChatKind.UsageLimit -> Surface(shape = MaterialTheme.shapes.medium, color = colors.errorContainer) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(first.text, style = MaterialTheme.typography.titleMedium)
                                ManageAgentUsage(c, primary = true)
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
                if (c.offersModelSelection) AgentModelControls(c)
                OutlinedTextField(c.input, { c.input = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.agent_message)) }, maxLines = 5,
                    trailingIcon = {
                        if (c.working) FilledIconButton(c::stop) { Symbol(R.drawable.studio_stop, uiString(R.string.stop_response)) }
                        else FilledIconButton(c::send, enabled = c.input.isNotBlank() && !c.loading &&
                            !c.accountBusy && !c.modelsLoading && (!c.offersModelSelection || c.selectedModel != null) && projectIdle) {
                            Symbol(R.drawable.studio_send, uiString(R.string.send))
                        }
                    })
            }
        }
    }
}

@Composable
private fun AgentModelControls(c: AgentSession) {
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
    val summary = if (items.size == 1) items.first().text else categories.take(3).joinToString(uiString(R.string.list_separator)) +
        if (categories.size > 3) uiString(R.string.and_more_total, items.size) else " · ${items.size}"
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = canExpand) { expanded = !expanded }
            .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (items.any { it.command }) R.drawable.studio_terminal else R.drawable.studio_article,
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
                        Symbol(if (item.command) R.drawable.studio_terminal else R.drawable.studio_description,
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
