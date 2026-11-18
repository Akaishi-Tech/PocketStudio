package com.hcgstudio.pocketstudio

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun GitRemotesTab(session: GitSession) {
    var model by remember(session) { mutableStateOf<GitRemoteList?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GitRemoteRow?>(null) }
    var removing by remember { mutableStateOf<GitRemoteRow?>(null) }
    var transferring by remember { mutableStateOf<Pair<GitRemoteRow, GitTransfer>?>(null) }
    var tracking by remember { mutableStateOf<GitRemoteRow?>(null) }
    val sshFiles = remember(session) { mutableMapOf<String, GitSshFiles>() }
    var feedback by remember(session) { mutableStateOf("") }

    LaunchedEffect(session, session.revision) {
        try { model = session.read(GitRemoteOperations::list) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { session.error(e) }
    }
    val loaded = model
    if (loaded == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item {
            TextButton(onClick = { adding = true }, enabled = !session.busy) {
                Symbol(R.drawable.studio_add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.git_remote_add))
            }
            if (feedback.isNotBlank()) Text(feedback, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        items(loaded.remotes, key = { it.name }) { remote ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(remote.name, style = MaterialTheme.typography.titleSmall)
                    remote.urls.forEach { url ->
                        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.git_remote_options, remote.name), !session.busy) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; editing = remote })
                        DropdownMenuItem(text = { Text(stringResource(R.string.git_remote_upstream)) }, enabled = loaded.branch != null,
                            onClick = { menu = false; tracking = remote })
                        DropdownMenuItem(text = { Text(stringResource(R.string.git_remote_force_lease)) },
                            enabled = loaded.branch != null && remote.tips.isNotEmpty(),
                            onClick = { menu = false; feedback = ""; transferring = remote to GitTransfer.ForcePush })
                        DropdownMenuItem(text = { Text(stringResource(R.string.git_remote_remove)) }, onClick = { menu = false; removing = remote })
                    }
                }
            }
            Row {
                listOf(GitTransfer.Fetch, GitTransfer.Pull, GitTransfer.Push).forEach { action ->
                    TextButton({ feedback = ""; transferring = remote to action },
                        enabled = !session.busy && (action == GitTransfer.Fetch || loaded.branch != null)) {
                        Text(stringResource(action.label))
                    }
                }
            }
            HorizontalDivider()
        }
    }
    if (adding || editing != null) {
        RemoteEditDialog(session, editing, if (loaded.remotes.isEmpty()) "origin" else "") { adding = false; editing = null }
    }
    removing?.let { remote ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.git_remote_remove_named, remote.name)) },
            confirmButton = { TextButton({
                removing = null
                session.mutate { GitRemoteOperations.remove(it, remote.name) }
            }, enabled = !session.busy) { Text(stringResource(R.string.git_remote_remove)) } },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    transferring?.let { (remote, action) ->
        RemoteTransferDialog(session, loaded, remote, action, sshFiles.getOrPut(remote.name) { GitSshFiles() }, onDismiss = { transferring = null }, onSuccess = {
            feedback = uiString(when (action) {
                GitTransfer.Fetch -> R.string.git_remote_fetched
                GitTransfer.Pull -> R.string.git_remote_pulled
                GitTransfer.Push, GitTransfer.ForcePush -> R.string.git_remote_pushed
            }, remote.name)
            transferring = null
        })
    }
    tracking?.let { remote ->
        val local = loaded.branch
        if (local != null) {
            var target by remember { mutableStateOf(loaded.trackingBranch.takeIf { loaded.trackingRemote == remote.name } ?: local) }
            val close = { tracking = null }
            AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.git_remote_upstream_for, local)) },
                text = {
                    Column {
                        BranchField(remote, target, !session.busy) { target = it }
                        if (loaded.trackingRemote != null) TextButton({
                            session.mutate(onSuccess = close) { GitRemoteOperations.track(it, local, remote.name, null) }
                        }, enabled = !session.busy) { Text(stringResource(R.string.git_remote_untrack)) }
                    }
                },
                confirmButton = { TextButton({
                    session.mutate(onSuccess = close) { GitRemoteOperations.track(it, local, remote.name, target.trim()) }
                }, enabled = target.isNotBlank() && !session.busy) { Text(stringResource(R.string.done)) } },
                dismissButton = { TextButton(close) { Text(stringResource(R.string.cancel)) } })
        }
    }
}

@Composable
private fun RemoteEditDialog(session: GitSession, previous: GitRemoteRow?, defaultName: String, close: () -> Unit) {
    var name by remember { mutableStateOf(previous?.name ?: defaultName) }
    var urls by remember { mutableStateOf(previous?.urls.orEmpty().joinToString("\n")) }
    var pushUrls by remember { mutableStateOf(previous?.pushUrls.orEmpty().joinToString("\n")) }
    var customPush by remember { mutableStateOf(pushUrls.isNotBlank()) }
    val enabled = !session.busy
    val dismiss = { if (enabled) close() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (previous == null) R.string.git_remote_add else R.string.git_remote_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previous == null) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    enabled = enabled, label = { Text(stringResource(R.string.name)) })
                OutlinedTextField(urls, { urls = it }, Modifier.fillMaxWidth(), maxLines = 3, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), label = { Text(stringResource(R.string.git_remote_url)) })
                if (customPush) OutlinedTextField(pushUrls, { pushUrls = it }, Modifier.fillMaxWidth(), maxLines = 3, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), label = { Text(stringResource(R.string.git_remote_push_url)) })
                else TextButton({ customPush = true }, enabled = enabled) { Text(stringResource(R.string.git_remote_push_url)) }
            }
        },
        confirmButton = { TextButton({
            val fetch = urls.lines().map(String::trim).filter(String::isNotEmpty)
            val push = pushUrls.lines().map(String::trim).filter(String::isNotEmpty)
            session.mutate(onSuccess = close) { GitRemoteOperations.save(it, name.trim(), fetch, push, previous) }
        }, enabled = enabled && name.isNotBlank() && urls.isNotBlank()) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(dismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RemoteTransferDialog(
    session: GitSession, model: GitRemoteList, remote: GitRemoteRow, action: GitTransfer, files: GitSshFiles,
    onDismiss: () -> Unit, onSuccess: () -> Unit
) {
    val tracked = remote.name == model.trackingRemote && model.trackingBranch != null
    var branch by remember { mutableStateOf(if (tracked) model.trackingBranch.orEmpty() else model.branch.orEmpty()) }
    var option by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf(!tracked) }
    val auth = remember { RemoteLogin() }
    val force = action == GitTransfer.ForcePush
    val targets = if (force || action == GitTransfer.Push) remote.pushUrls.ifEmpty { remote.urls } else remote.urls.take(1)
    val context = LocalContext.current
    val sshNeeded = targets.any(::isGitSshUrl)
    var passphrase by remember { mutableStateOf("") }
    val expected = remote.tips[branch.trim()]
    var confirming by remember { mutableStateOf(false) }
    val enabled = !session.busy
    val dismiss = { if (enabled) onDismiss() }
    val label = stringResource(action.label)
    fun submit() {
        val username = auth.username
        val password = auth.password
        val target = branch.trim()
        val setTracking = track
        val selectedOption = option
        val keyPassword = passphrase
        session.mutate(onSuccess = onSuccess) { git ->
            val identity = if (sshNeeded) files.load(context, keyPassword) else null
            identity.use {
                GitRemoteOperations.transfer(git, action, remote, model.branch, target, setTracking, selectedOption, username, password, it)
            }
        }
    }
    if (confirming && expected != null) {
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text(label) },
            text = { Text(stringResource(R.string.git_remote_lease_confirm, remote.name, branch.trim(), expected.take(10), model.branch.orEmpty())) },
            confirmButton = { TextButton({ confirming = false; submit() }) { Text(stringResource(R.string.git_remote_force_push)) } },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } })
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("$label · ${remote.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                targets.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (action != GitTransfer.Fetch) {
                    BranchField(remote, branch, enabled) { branch = it }
                    if (!tracked || branch != model.trackingBranch)
                        RemoteCheck(R.string.git_remote_set_upstream, track, enabled) { track = it }
                }
                val optionLabel = when (action) {
                    GitTransfer.Fetch -> R.string.git_remote_prune
                    GitTransfer.Pull -> R.string.git_remote_rebase_pull
                    GitTransfer.Push -> R.string.git_remote_push_tags
                    GitTransfer.ForcePush -> null
                }
                if (optionLabel != null) RemoteCheck(optionLabel, option, enabled) { option = it }
                if (sshNeeded) GitSshFields(files, passphrase, { passphrase = it }, enabled)
                if (targets.isNotEmpty() && targets.all { it.startsWith("https://") }) RemoteCredentials(auth, enabled)
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ if (force) confirming = true else submit() },
            enabled = enabled && (action == GitTransfer.Fetch || branch.isNotBlank()) && (!force || expected != null) && auth.valid && (!sshNeeded || files.ready)) {
            Text(if (force) stringResource(R.string.git_remote_review) else label)
        } },
        dismissButton = { TextButton(dismiss, enabled = enabled) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun BranchField(remote: GitRemoteRow, branch: String, enabled: Boolean, change: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(branch, change, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
            label = { Text(stringResource(R.string.git_remote_branch_at, remote.name)) },
            trailingIcon = if (remote.tips.keys.isEmpty()) null else { {
                StudioIconButton(R.drawable.studio_expand_more, stringResource(R.string.git_remote_choose_branch), enabled) { menu = true }
            } })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            remote.tips.keys.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; change(name) })
            }
        }
    }
}

@Composable
private fun RemoteCheck(label: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Checkbox, onValueChange = change).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

private class RemoteLogin {
    var expanded by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    val valid get() = !expanded || (username.isNotBlank() && password.isNotEmpty())
}

@Composable
private fun RemoteCredentials(auth: RemoteLogin, enabled: Boolean) {
    if (!auth.expanded) {
        TextButton({ auth.expanded = true }, enabled = enabled) { Text(stringResource(R.string.git_remote_auth)) }
    } else {
        OutlinedTextField(auth.username, { auth.username = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
            label = { Text(stringResource(R.string.git_remote_username)) })
        OutlinedTextField(auth.password, { auth.password = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.git_remote_token)) })
        TextButton({ auth.expanded = false; auth.username = ""; auth.password = "" }, enabled = enabled) {
            Text(stringResource(R.string.git_remote_anonymous))
        }
    }
}

@Composable
internal fun GitCloneDialog(onDismiss: () -> Unit, onCloned: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val auth = remember { RemoteLogin() }
    val files = remember { GitSshFiles() }
    var passphrase by remember { mutableStateOf("") }
    val sshNeeded = remember(url) { isGitSshUrl(url) }
    var job by remember { mutableStateOf<Job?>(null) }
    val cloned by rememberUpdatedState(onCloned)
    val enabled = job == null
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(stringResource(R.string.git_clone_action)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), label = { Text(stringResource(R.string.git_remote_url)) })
                if (url.trim().startsWith("https://", ignoreCase = true)) RemoteCredentials(auth, enabled)
                if (sshNeeded) GitSshFields(files, passphrase, { passphrase = it }, enabled)
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({
            val remote = url.trim()
            val username = if (remote.startsWith("https://")) auth.username else ""
            val password = if (remote.startsWith("https://")) auth.password else ""
            val keyPassword = passphrase
            error = ""
            job = scope.launch {
                var created: File? = null
                try {
                    runInterruptible(Dispatchers.IO) {
                        val identity = if (sshNeeded) files.load(context, keyPassword) else null
                        identity.use {
                            created = GitRemoteOperations.clone(File(context.filesDir, "imports"), remote, username, password, it)
                        }
                    }
                    cloned(requireNotNull(created))
                    created = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.localizedMessage ?: e.javaClass.simpleName
                } finally {
                    created?.parentFile?.let { container ->
                        withContext(NonCancellable + Dispatchers.IO) { Project.removeTree(container.toPath()) }
                    }
                    job = null
                }
            }
        }, enabled = enabled && url.isNotBlank() && (!sshNeeded || files.ready) && (!url.trim().startsWith("https://") || auth.valid)) { Text(stringResource(R.string.git_clone)) } },
        dismissButton = { TextButton({ if (enabled) onDismiss() else job?.cancel() }) { Text(stringResource(R.string.cancel)) } })
}
