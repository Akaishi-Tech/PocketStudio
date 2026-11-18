package com.hcgstudio.pocketstudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.CheckoutCommand

@Composable
internal fun GitChangesTab(session: GitSession) {
    var changes by remember(session) { mutableStateOf<GitChanges?>(null) }
    var selected by remember(session) { mutableStateOf<GitChange?>(null) }
    var amend by remember(session) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(session, session.revision) {
        try {
            changes = session.read(GitChangesStore::load)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changes = null
            session.error(e)
        }
    }
    val data = changes
    if (data == null) {
        if (!session.busy && session.problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val busy = session.busy
    fun transfer(area: GitArea, files: List<GitChange>) {
        session.mutate { git ->
            if (area == GitArea.Staged) GitChangesStore.unstage(git, files)
            else GitChangesStore.stage(git, files)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (data.groups.values.all { it.isEmpty() }) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.git_change_clean), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                for ((area, files) in data.groups) {
                    if (files.isEmpty()) continue
                    item(key = area.name) {
                        val title = when (area) {
                            GitArea.Staged -> R.string.git_change_staged
                            GitArea.Unstaged -> R.string.git_change_unstaged
                            GitArea.Conflicts -> R.string.git_change_conflicts
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(title, files.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (area != GitArea.Conflicts && files.size > 1) {
                                TextButton({ transfer(area, files) }, enabled = !busy) {
                                    Text(stringResource(if (area == GitArea.Staged) R.string.git_change_unstage_all else R.string.git_change_stage_all))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    items(files, key = { area.name + "/" + it.path }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = entry }
                            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val kind = stringResource(entry.kind.label)
                            val color = when (entry.kind) {
                                GitChangeKind.Deleted, GitChangeKind.Conflict -> MaterialTheme.colorScheme.error
                                GitChangeKind.Added, GitChangeKind.Untracked -> MaterialTheme.colorScheme.primary
                                GitChangeKind.Modified -> MaterialTheme.colorScheme.tertiary
                            }
                            Text(entry.kind.code, Modifier.width(26.dp).clearAndSetSemantics { contentDescription = kind },
                                color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                            Text(entry.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            if (area == GitArea.Conflicts) {
                                Symbol(R.drawable.studio_chevron_right, null)
                            } else {
                                StudioIconButton(if (area == GitArea.Staged) R.drawable.git_changes_remove else R.drawable.studio_add,
                                    stringResource(if (area == GitArea.Staged) R.string.git_change_unstage_path else R.string.git_change_stage_path, entry.path),
                                    enabled = !busy) { transfer(area, listOf(entry)) }
                            }
                        }
                    }
                }
            }
        }
        if (data.canAmend || data.groups.getValue(GitArea.Staged).isNotEmpty() || data.canCommit) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (data.canAmend) TextButton({ amend = true }, enabled = !busy) {
                    Text(stringResource(R.string.git_change_amend))
                }
                Button({ amend = false }, enabled = !busy && data.canCommit) {
                    Text(stringResource(R.string.git_change_commit))
                }
            }
        }
    }
    selected?.let { entry ->
        GitChangeDialog(session, entry, close = { selected = null })
    }
    amend?.let { replace ->
        GitCommitDialog(session, data, replace, close = { amend = null })
    }
}

private enum class ChangeConfirmation { Discard, Ours, Theirs }

@Composable
private fun GitChangeDialog(session: GitSession, entry: GitChange, close: () -> Unit) {
    var preview by remember(entry) { mutableStateOf<GitChangesStore.Preview?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ChangeConfirmation?>(null) }
    var choosing by remember(entry) { mutableStateOf(false) }
    var selected by remember(preview) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(session, session.revision, entry) {
        try {
            preview = session.read { GitChangesStore.preview(it, entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close()
            session.error(e)
        }
    }
    val detail = preview
    val action = when (entry.area) {
        GitArea.Conflicts -> R.string.git_change_mark_resolved
        GitArea.Staged -> R.string.git_change_unstage
        GitArea.Unstaged -> R.string.git_change_stage
    }
    val ours = if (detail?.oursDeleted == true) R.string.git_change_ours_deleted else R.string.git_change_ours
    val theirs = if (detail?.theirsDeleted == true) R.string.git_change_theirs_deleted else R.string.git_change_theirs
    val plan = detail?.hunks
    if (choosing && plan != null) {
        AlertDialog(
            onDismissRequest = { if (!session.busy) choosing = false },
            title = { Text(entry.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(plan.hunks.size) { index ->
                        val checked = index in selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(checked, enabled = !session.busy,
                            role = Role.Checkbox, onValueChange = { selected = if (it) selected + index else selected - index }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, null, enabled = !session.busy)
                            Text(plan.hunks[index].patch.substringBefore('\n'), Modifier.padding(start = 8.dp),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                        }
                        GitPatch(plan.hunks[index].patch.substringAfter('\n'), Modifier.fillMaxWidth().heightIn(max = 160.dp))
                    }
                }
            },
            confirmButton = {
                TextButton({ session.mutate(close) { plan.apply(it, selected) } }, enabled = !session.busy && selected.isNotEmpty()) {
                    Text(stringResource(action))
                }
            },
            dismissButton = { TextButton({ choosing = false }, enabled = !session.busy) { Text(stringResource(R.string.cancel)) } }
        )
        return
    }
    val confirmation = confirm
    if (confirmation != null) {
        val label = when (confirmation) {
            ChangeConfirmation.Discard -> if (entry.kind == GitChangeKind.Untracked) R.string.delete else R.string.git_change_discard
            ChangeConfirmation.Ours -> ours
            ChangeConfirmation.Theirs -> theirs
        }
        AlertDialog(
            onDismissRequest = { if (!session.busy) confirm = null },
            title = { Text(stringResource(label)) },
            text = {
                Column {
                    Text(stringResource(when {
                        confirmation != ChangeConfirmation.Discard -> R.string.git_change_replace_conflict
                        entry.kind == GitChangeKind.Untracked -> R.string.git_change_delete_confirm
                        else -> R.string.git_change_discard_confirm
                    }, entry.path))
                    session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton({
                    session.mutate(close) { git ->
                        when (confirmation) {
                            ChangeConfirmation.Discard -> GitChangesStore.discard(git, entry)
                            ChangeConfirmation.Ours -> GitChangesStore.resolve(git, entry.path, CheckoutCommand.Stage.OURS)
                            ChangeConfirmation.Theirs -> GitChangesStore.resolve(git, entry.path, CheckoutCommand.Stage.THEIRS)
                        }
                    }
                }, enabled = !session.busy) { Text(stringResource(label)) }
            },
            dismissButton = {
                TextButton({ confirm = null }, enabled = !session.busy) { Text(stringResource(R.string.cancel)) }
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!session.busy) close() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.path, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                if (detail != null && (detail.editable || entry.kind != GitChangeKind.Added)) {
                    Box {
                        StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.git_change_file_actions), !session.busy) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            if (plan != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_change_select_hunks)) },
                                onClick = { menu = false; selected = emptySet(); choosing = true }
                            )
                            if (detail.editable) DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                onClick = { menu = false; close(); session.edit(entry.path) }
                            )
                            if (entry.kind != GitChangeKind.Untracked && entry.kind != GitChangeKind.Added) DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_file_history)) },
                                onClick = { menu = false; close(); session.filePath = entry.path }
                            )
                            if (entry.kind == GitChangeKind.Untracked) DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_change_ignore)) },
                                onClick = { menu = false; session.mutate(close) { GitChangesStore.ignore(it, entry) } }
                            )
                            if (entry.area == GitArea.Unstaged) DropdownMenuItem(
                                text = { Text(stringResource(if (entry.kind == GitChangeKind.Untracked) R.string.delete else R.string.git_change_discard)) },
                                onClick = { menu = false; confirm = ChangeConfirmation.Discard }
                            )
                            if (entry.area == GitArea.Conflicts) {
                                DropdownMenuItem(text = { Text(stringResource(ours)) },
                                    onClick = { menu = false; confirm = ChangeConfirmation.Ours })
                                DropdownMenuItem(text = { Text(stringResource(theirs)) },
                                    onClick = { menu = false; confirm = ChangeConfirmation.Theirs })
                            }
                        }
                    }
                }
            }
        },
        text = {
            if (detail == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column {
                if (entry.area == GitArea.Conflicts) Text(stringResource(R.string.git_change_conflict_sides),
                    Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium)
                if (detail.text.isEmpty()) Text(stringResource(R.string.git_change_no_diff))
                else GitPatch(detail.text, Modifier.fillMaxWidth().heightIn(max = 460.dp))
                session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                session.mutate(close) { git ->
                    when (entry.area) {
                        GitArea.Conflicts -> GitChangesStore.resolve(git, entry.path, null)
                        GitArea.Staged -> GitChangesStore.unstage(git, listOf(entry))
                        GitArea.Unstaged -> GitChangesStore.stage(git, listOf(entry))
                    }
                }
            }, enabled = detail != null && !session.busy) { Text(stringResource(action)) }
        },
        dismissButton = { TextButton(close, enabled = !session.busy) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun GitCommitDialog(session: GitSession, changes: GitChanges, amend: Boolean, close: () -> Unit) {
    val expectedHead = remember { changes.head }
    var message by rememberSaveable { mutableStateOf(if (amend) changes.previousMessage else changes.mergeMessage) }
    var name by rememberSaveable { mutableStateOf(changes.name) }
    var email by rememberSaveable { mutableStateOf(changes.email) }
    var editIdentity by rememberSaveable { mutableStateOf(name.isBlank() || email.isBlank()) }
    val action = if (amend) R.string.git_change_amend else R.string.git_change_commit
    AlertDialog(
        onDismissRequest = { if (!session.busy) close() },
        title = { Text(stringResource(if (amend) R.string.git_change_amend_title else R.string.git_change_commit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), enabled = !session.busy,
                    label = { Text(stringResource(R.string.git_change_message)) }, minLines = 2, maxLines = 6)
                if (editIdentity) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !session.busy, singleLine = true,
                        label = { Text(stringResource(R.string.name)) })
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), enabled = !session.busy, singleLine = true,
                        label = { Text(stringResource(R.string.git_change_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                } else {
                    TextButton({ editIdentity = true }, enabled = !session.busy) {
                        Text("$name <$email>", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                session.mutate(close) { GitChangesStore.commit(it, expectedHead, message, name, email, amend) }
            }, enabled = !session.busy && message.isNotBlank() && name.isNotBlank() && email.isNotBlank()) {
                Text(stringResource(action))
            }
        },
        dismissButton = { TextButton(close, enabled = !session.busy) { Text(stringResource(R.string.cancel)) } }
    )
}
