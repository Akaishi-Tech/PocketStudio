package com.hcgstudio.pocketstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun LinuxTerminalPane(w: Workspace, modifier: Modifier = Modifier) {
    val p = w.project ?: return
    val shell = w.linuxShell
    var command by rememberSaveable(p.root.uri.toString()) { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(shell.text) { scroll.scrollTo(scroll.maxValue) }
    DisposableEffect(p) { onDispose { if (w.project === p) w.action { w.refreshAfterExternalRun(p) } } }
    Column(modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (shell.running) {
                TextButton({ shell.send("\u0003") }) { Text("Ctrl+C") }
                TextButton({ shell.send("\u0004") }) { Text("Ctrl+D") }
                TextButton(shell::stop) { Text(stringResource(R.string.linux_stop_shell)) }
            } else Button({ shell.start(p) }) { Text(stringResource(R.string.linux_start_shell)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            SelectionContainer { Text(shell.text, fontFamily = LocalEditorFontFamily.current) }
        }
        Row {
            OutlinedTextField(command, { command = it }, Modifier.weight(1f), singleLine = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (shell.running) { shell.send(command + "\n"); command = "" }
                }), label = { Text(stringResource(R.string.linux_shell_input)) })
            TextButton({ shell.send(command + "\n"); command = "" }, enabled = shell.running) {
                Text(stringResource(R.string.linux_send))
            }
        }
    }
}
