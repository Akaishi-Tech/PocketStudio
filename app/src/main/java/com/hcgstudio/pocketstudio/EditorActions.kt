package com.hcgstudio.pocketstudio

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class StudioAction(
    val id: String,
    val label: String,
    @get:StringRes val group: Int,
    val shortcut: String = "",
    val enabled: Boolean = true,
    val run: () -> Unit
) {
    fun matches(event: KeyEvent): Boolean {
        if (shortcut.isEmpty() || event.action != KeyEvent.ACTION_DOWN) return false
        val tokens = shortcut.split('+')
        if ((event.isCtrlPressed || event.isMetaPressed) != ("Ctrl" in tokens) ||
            event.isAltPressed != ("Alt" in tokens) || event.isShiftPressed != ("Shift" in tokens)) return false
        val last = tokens.last()
        val androidKey = when (last) {
            "/" -> "SLASH"
            "-" -> "MINUS"
            "[" -> "LEFT_BRACKET"
            "]" -> "RIGHT_BRACKET"
            "Up", "Down", "Left", "Right" -> "DPAD_" + last.uppercase(Locale.ROOT)
            "Backspace" -> "DEL"
            "Delete" -> "FORWARD_DEL"
            "Home" -> "MOVE_HOME"
            "End" -> "MOVE_END"
            "Esc" -> "ESCAPE"
            else -> last.uppercase(Locale.ROOT)
        }
        return event.keyCode == KeyEvent.keyCodeFromString("KEYCODE_" + androidKey)
    }
}

@Composable
internal fun ActionPalette(actions: List<StudioAction>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = actions.filter {
        listOf(it.label, uiString(it.group), it.shortcut, it.id).any { value -> value.contains(query.trim(), true) }
    }
    var index by remember(query) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(index) { if (index in filtered.indices) list.animateScrollToItem(index) }
    fun execute(action: StudioAction) {
        if (action.enabled) { onDismiss(); action.run() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.find_action)) }, confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.action_or_shortcut)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                        val event = it.nativeKeyEvent
                        if (event.action != KeyEvent.ACTION_DOWN || filtered.isEmpty()) false else when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> { index = (index + 1).coerceAtMost(filtered.lastIndex); true }
                            KeyEvent.KEYCODE_DPAD_UP -> { index = (index - 1).coerceAtLeast(0); true }
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { filtered.getOrNull(index)?.let(::execute); true }
                            else -> false
                        }
                    })
                LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    itemsIndexed(filtered, key = { _, action -> action.id }) { n, action ->
                        Surface(color = if (index == n) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = action.enabled) { execute(action) }
                                .defaultMinSize(minHeight = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val ink = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                                Text(action.label, Modifier.weight(1f), color = ink)
                                if (action.shortcut.isNotEmpty()) Text(action.shortcut, color = ink, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (filtered.isEmpty()) item { Text(stringResource(R.string.no_matching_actions), Modifier.padding(vertical = 16.dp)) }
                }
            }
        })
}

@Composable
internal fun editorActions(state: StudioEditorState?): List<StudioAction> {
    val context = LocalContext.current
    val settings = LocalStudioSettings.current
    val clipboardLabel = androidx.compose.ui.res.stringResource(R.string.source_code)
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var dialog by remember(state) { mutableStateOf("") }
    val actions = mutableListOf<StudioAction>()
    fun action(id: String, label: String, group: Int, key: String = "", available: Boolean = true, execute: StudioEditorState.() -> Unit) {
        actions += StudioAction("editor." + id, label, group, key, state != null && available) {
            if (state != null) try { state.execute() }
            catch (e: IllegalArgumentException) { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() }
        }
    }
    val edit = R.string.edit
    val nav = R.string.navigate
    val code = R.string.code
    val refactor = R.string.refactor
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    fun copy(text: String) { clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text)) }
    action("undo", uiString(R.string.undo), edit, "Ctrl+Z", state?.canUndo == true) { undo() }
    action("redo", uiString(R.string.redo), edit, "Ctrl+Shift+Z", state?.canRedo == true) { redo() }
    action("cut", uiString(R.string.cut), edit, "Ctrl+X") {
        copy(if (start == end) linesText() else selected)
        if (start == end) deleteLines() else insert("")
    }
    action("copy", uiString(R.string.copy), edit, "Ctrl+C") { copy(if (start == end) linesText() else selected) }
    action("paste", uiString(R.string.paste), edit, "Ctrl+V") {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) insert(clip.getItemAt(0).coerceToText(context).toString())
    }
    action("selectAll", uiString(R.string.select_all), edit, "Ctrl+A") { select(0, text.length) }
    action("selectLine", uiString(R.string.select_line), edit) { selectLine() }
    action("prevWord", uiString(R.string.previous_word), nav, "Ctrl+Left") { word(false) }
    action("nextWord", uiString(R.string.next_word), nav, "Ctrl+Right") { word(true) }
    action("selectPrevWord", uiString(R.string.select_word_to_the_left), edit, "Ctrl+Shift+Left") { word(false, extend = true) }
    action("selectNextWord", uiString(R.string.select_word_to_the_right), edit, "Ctrl+Shift+Right") { word(true, extend = true) }
    action("deletePrevWord", uiString(R.string.delete_previous_word), edit, "Ctrl+Backspace") { word(false, delete = true) }
    action("deleteNextWord", uiString(R.string.delete_next_word), edit, "Ctrl+Delete") { word(true, delete = true) }
    action("surround", uiString(R.string.surround_selection), code, "Ctrl+Alt+T", state != null && state.start != state.end) { dialog = "surround" }
    action("expandSelection", uiString(R.string.extend_selection), edit, "Ctrl+W") { expand(false) }
    action("shrinkSelection", uiString(R.string.shrink_selection), edit, "Ctrl+Shift+W") { expand(true) }
    action("duplicate", uiString(R.string.duplicate_line_or_selection), edit, "Ctrl+D") { duplicate() }
    action("deleteLine", uiString(R.string.delete_line), edit, "Ctrl+Y") { deleteLines() }
    action("moveUp", uiString(R.string.move_line_up), edit, "Alt+Shift+Up") { moveLines(false) }
    action("moveDown", uiString(R.string.move_line_down), edit, "Alt+Shift+Down") { moveLines(true) }
    action("join", uiString(R.string.join_lines), edit, "Ctrl+Shift+J") { joinLines() }
    action("split", uiString(R.string.split_line), edit, "Ctrl+Enter") { newLine(false, settings = settings) }
    action("startLine", uiString(R.string.insert_line_below), edit, "Shift+Enter") { newLine(true, settings = settings) }
    action("lineAbove", uiString(R.string.insert_line_above), edit, "Ctrl+Alt+Enter") { newLine(false, true, settings) }
    action("case", uiString(R.string.toggle_case), edit, "Ctrl+Shift+U") { changeCase() }
    action("indent", uiString(R.string.indent), code, "Tab") { indent(false, settings) }
    action("unindent", uiString(R.string.unindent), code, "Shift+Tab") { indent(true, settings) }
    action("lineComment", uiString(R.string.toggle_line_comment), code, "Ctrl+/") { commentLines() }
    action("blockComment", uiString(R.string.toggle_block_comment), code, "Ctrl+Shift+/") { blockComment() }
    action("complete", uiString(R.string.code_completion), code, "Ctrl+Space") { dialog = "complete" }
    action("format", uiString(R.string.reformat_java_file), code, "Ctrl+Alt+L", state?.isJava == true) { dialog = "format" }
    action("imports", uiString(R.string.organize_java_imports), code, "Ctrl+Alt+O", state?.isJava == true) { dialog = "imports" }
    action("find", uiString(R.string.find), edit, "Ctrl+F") {
        if (selected.isNotEmpty() && selected.length <= 256) find = selected
        dialog = "find"
    }
    action("replace", uiString(R.string.replace), edit, "Ctrl+R") {
        if (selected.isNotEmpty() && selected.length <= 256) find = selected
        dialog = "replace"
    }
    action("nextMatch", uiString(R.string.next_match), nav, "F3") {
        if (find.isEmpty()) dialog = "find" else if (!match(true)) message(uiString(R.string.no_matches))
    }
    action("previousMatch", uiString(R.string.previous_match), nav, "Shift+F3") {
        if (find.isEmpty()) dialog = "find" else if (!match(false)) message(uiString(R.string.no_matches))
    }
    action("goToLine", uiString(R.string.go_to_line_and_column), nav, "Ctrl+G") { dialog = "line" }
    action("fileStart", uiString(R.string.go_to_start_of_file), nav, "Ctrl+Home") { select(0) }
    action("fileEnd", uiString(R.string.go_to_end_of_file), nav, "Ctrl+End") { select(text.length) }
    val local = state?.let { it.isKotlin || it.isJava } == true
    val java = state?.isJava == true
    val selection = state?.let { it.start != it.end } == true
    for ((id, label, key) in listOf(
        Triple("rename", uiString(R.string.rename_symbol), "Shift+F6"),
        Triple("copyMethod", uiString(R.string.copy_private_method), ""),
        Triple("moveMethod", uiString(R.string.move_private_static_method), ""),
        Triple("deleteMethod", uiString(R.string.safe_delete_private_method), ""),
        Triple("inline", uiString(R.string.inline_local_variable), "Ctrl+Alt+N"),
        Triple("variable", uiString(R.string.extract_local_variable), "Ctrl+Alt+V"),
        Triple("method", uiString(R.string.extract_method), "Ctrl+Alt+M"),
        Triple("field", uiString(R.string.extract_field), "Ctrl+Alt+F"),
        Triple("constant", uiString(R.string.extract_constant), "Ctrl+Alt+C"),
        Triple("parameter", uiString(R.string.extract_parameter), "Ctrl+Alt+P"),
        Triple("signature", uiString(R.string.change_method_signature), "Ctrl+F6")
    )) {
        val applicable = when (id) {
            "rename", "inline" -> local
            "variable" -> local && selection
            "signature", "copyMethod", "moveMethod", "deleteMethod" -> java
            else -> java && selection
        }
        action(id, label, refactor, key, applicable) { dialog = id }
    }
    for ((id, label, key) in listOf(
        Triple("structure", uiString(R.string.file_structure), "Ctrl+F12"),
        Triple("declaration", uiString(R.string.go_to_declaration_in_file), "Ctrl+B"),
        Triple("usages", uiString(R.string.find_usages_in_file), "Alt+F7")
    )) action(id, label, nav, key, java) { dialog = id }
    action("refactorThis", uiString(R.string.refactor_here), refactor, "Ctrl+Alt+Shift+T", local) { dialog = "refactorThis" }
    if (state != null && dialog.isNotEmpty()) {
        val close = { dialog = "" }
        when (dialog) {
            "find", "replace" -> FindDialog(state, dialog == "replace", close)
            "line" -> GoDialog(state, close)
            "complete" -> CompletionDialog(state, close)
            "surround" -> ActionPalette(listOf(
                Triple(uiString(R.string.parentheses), "(", ")"), Triple(uiString(R.string.brackets), "[", "]"), Triple(uiString(R.string.braces), "{", "}"),
                Triple(uiString(R.string.double_quotes), "\"", "\""), Triple(uiString(R.string.single_quotes), "'", "'")
            ).map { (label, before, after) -> StudioAction("surround" + before, label, R.string.code) {
                state.surround(before, after)
            } }, close)
            "structure", "declaration", "usages" -> LocationsDialog(state, dialog, actions.first { it.id == "editor." + dialog }.label, close)
            "refactorThis" -> ActionPalette(actions.filter { it.group == refactor && it.id != "editor.refactorThis" }, close)
            else -> key(dialog) {
                RefactorDialog(state, dialog, actions.first { it.id == "editor." + dialog }.label, close)
            }
        }
    }
    return actions
}

@Composable
private fun FindDialog(state: StudioEditorState, replace: Boolean, close: () -> Unit) {
    var status by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun next(forward: Boolean) { if (state.match(forward)) close() else status = uiString(R.string.no_matches) }
    AlertDialog(onDismissRequest = close, title = { Text(if (replace) uiString(R.string.replace) else uiString(R.string.find)) },
        confirmButton = { TextButton(onClick = { next(true) }, enabled = state.find.isNotEmpty()) { Text(stringResource(R.string.find_next)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.find, { state.find = it; status = "" }, singleLine = true, label = { Text(stringResource(R.string.find_text)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                        if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) { next(!it.nativeKeyEvent.isShiftPressed); true } else false
                    })
                if (replace) OutlinedTextField(state.replacement, { state.replacement = it }, singleLine = true,
                    label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.matchCase, onClick = { state.matchCase = !state.matchCase }, label = { Text(stringResource(R.string.match_case)) })
                    FilterChip(selected = state.wholeWords, onClick = { state.wholeWords = !state.wholeWords }, label = { Text(stringResource(R.string.whole_words)) })
                }
                if (replace) Row {
                    TextButton(onClick = { if (state.replaceMatch()) status = uiString(R.string.replaced_one_match) else status = uiString(R.string.no_matches) }, enabled = state.find.isNotEmpty()) { Text(stringResource(R.string.replace_one)) }
                    TextButton(onClick = { val count = state.replaceAll(); status = uiString(R.string.replacements, count) }, enabled = state.find.isNotEmpty()) { Text(stringResource(R.string.replace_all)) }
                } else TextButton(onClick = { next(false) }, enabled = state.find.isNotEmpty()) { Text(stringResource(R.string.find_previous)) }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        })
}

@Composable
private fun GoDialog(state: StudioEditorState, close: () -> Unit) {
    var where by remember { mutableStateOf("") }
    val parts = where.split(':')
    val line = parts.first().trim().toIntOrNull() ?: 0
    val col = if (parts.size == 1) 1 else parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = line > 0 && col > 0 && parts.size <= 2
    fun go() { if (valid) { state.go(line, col); close() } }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.go_to_line_and_column)) },
        text = { OutlinedTextField(where, { where = it }, label = { Text(stringResource(R.string.line_or_line_column)) }, singleLine = true,
            modifier = Modifier.focusRequester(focus).onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) { go(); true } else false
            }) },
        confirmButton = { TextButton(onClick = ::go, enabled = valid) { Text(stringResource(R.string.go_to)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CompletionDialog(state: StudioEditorState, close: () -> Unit) {
    val range = state.prefix()
    val prefix = state.text.substring(range.min, range.max)
    val options = remember(state.text, prefix) {
        val words = Regex("""[\p{L}_][\p{L}\p{N}_]*""").findAll(state.text).map { it.value }
        val keywords = "abstract boolean break case catch class const continue data default do double else enum extends false final float for fun if implements import in int interface internal is long namespace new null object open override package private protected public return static String super suspend switch this throw throws true try val var void when while".split(' ')
        (words + keywords.asSequence()).filter { it != prefix && it.startsWith(prefix) }.distinct().sorted().take(100).toList()
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.code_completion)) }, confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
        text = {
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                if (options.isEmpty()) item { Text(stringResource(R.string.no_matching_identifiers)) }
                items(options) { token -> Text(token, Modifier.fillMaxWidth().clickable { state.complete(range, token); close() }
                    .padding(vertical = 12.dp), fontFamily = LocalStudioSettings.current.editorFont.family) }
            }
        })
}

@Composable
private fun RefactorDialog(state: StudioEditorState, kind: String, label: String, close: () -> Unit) {
    val settings = LocalStudioSettings.current
    val original = remember { state.text }
    val from = remember { state.start }
    val to = remember { state.end }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val needsName = kind !in setOf("inline", "format", "imports", "deleteMethod")
    fun plan() {
        scope.launch {
            busy = true
            error = ""
            try {
                val after = withContext(Dispatchers.Default) {
                    if (state.isJava) {
                        val refactor = JavaRefactor(original, from, to)
                        if (kind == "format") refactor.format(settings.indentSize, settings.useTabs)
                        else refactor.run(kind, name.trim(), "")
                    }
                    else KotlinLocalRefactor(original, from, to).run(kind, name.trim())
                }
                if (after == original) error = uiString(R.string.the_file_already_matches_the_requested_changes) else preview = after
            } catch (e: IllegalArgumentException) {
                error = e.message ?: uiString(R.string.this_action_is_unavailable_here)
            } catch (e: RuntimeException) {
                error = e.message ?: uiString(R.string.unable_to_parse_the_code_here)
            } finally { busy = false }
        }
    }
    val hint = when (kind) {
        "rename" -> if (state.isJava) uiString(R.string.rename_a_local_variable_parameter_or_non_overloaded) else uiString(R.string.rename_a_local_variable_declared_directly_in_this)
        "inline" -> uiString(R.string.replace_references_to_the_local_variable_with_its)
        "copyMethod" -> uiString(R.string.copy_the_selected_private_method_in_the_current)
        "moveMethod" -> uiString(R.string.move_a_private_static_method_that_uses_primitive)
        "deleteMethod" -> uiString(R.string.check_static_calls_in_this_file_before_deleting)
        "method" -> uiString(R.string.move_the_selected_expression_to_a_new_method)
        "variable" -> uiString(R.string.extract_the_selected_expression_before_the_current_statement)
        "field", "constant" -> uiString(R.string.extract_the_selected_literal_into_the_current_class)
        "parameter" -> uiString(R.string.add_the_literal_as_a_private_method_parameter)
        "signature" -> uiString(R.string.reorder_the_private_method_parameters_and_update_its)
        "imports" -> uiString(R.string.sort_and_deduplicate_java_imports)
        else -> uiString(R.string.format_the_current_java_file)
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (preview == null) label else uiString(R.string.preview_changes)) },
        confirmButton = {
            TextButton(enabled = !busy && (!needsName || name.isNotBlank()), onClick = {
                val after = preview
                if (after == null) plan()
                else if (state.text != original) { preview = null; error = uiString(R.string.the_file_changed_reopen_this_action) }
                else {
                    state.replace(0, original.length, after, TextRange(from.coerceAtMost(after.length)))
                    close()
                }
            }) { Text(if (preview == null) uiString(R.string.preview) else uiString(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        text = {
            val after = preview
            if (after == null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hint)
                    if (needsName) OutlinedTextField(name, { name = it; error = "" }, singleLine = true,
                        label = { Text(when (kind) { "signature" -> uiString(R.string.parameter_order_comma_separated); "moveMethod" -> uiString(R.string.target_class); else -> uiString(R.string.new_name) }) }, modifier = Modifier.fillMaxWidth())
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            } else {
                val changes = remember(after) {
                    val old = original.lines()
                    val new = after.lines()
                    var prefix = 0
                    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
                    var tail = 0
                    while (tail < minOf(old.size, new.size) - prefix && old[old.lastIndex - tail] == new[new.lastIndex - tail]) tail++
                    old.subList(prefix, old.size - tail).mapIndexed { i, s -> "− " + (prefix + i + 1) + "  " + s } +
                        new.subList(prefix, new.size - tail).mapIndexed { i, s -> "+ " + (prefix + i + 1) + "  " + s }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(changes) { row ->
                        Text(row, fontFamily = LocalStudioSettings.current.editorFont.family, style = MaterialTheme.typography.bodySmall,
                            color = if (row.startsWith("−")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        })
}

@Composable
private fun LocationsDialog(state: StudioEditorState, kind: String, label: String, close: () -> Unit) {
    var locations by remember { mutableStateOf<List<JavaRefactor.Location>?>(null) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val original = remember { state.text }
    val start = remember { state.start }
    val end = remember { state.end }
    LaunchedEffect(state, kind) {
        try {
            val result = withContext(Dispatchers.Default) { JavaRefactor(original, start, end).locations(kind) }
            if (kind == "declaration" && result.size == 1 && state.text == original) {
                state.select(result.first().start, result.first().end)
                close()
            } else locations = result
        } catch (e: RuntimeException) { error = e.message ?: uiString(R.string.unable_to_locate_declaration) }
    }
    AlertDialog(onDismissRequest = close, title = { Text(label) }, confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
        text = {
            Column {
                val found = locations
                when {
                    error.isNotEmpty() -> Text(error)
                    found == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    found.isEmpty() -> Text(stringResource(R.string.no_matches))
                    else -> {
                        OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text(stringResource(R.string.filter)) }, modifier = Modifier.fillMaxWidth())
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(found.filter { it.label.contains(filter, true) }) { location ->
                                Text(location.label, Modifier.fillMaxWidth().clickable {
                                    if (state.text == original) state.select(location.start, location.end)
                                    close()
                                }.padding(vertical = 12.dp), fontFamily = LocalStudioSettings.current.editorFont.family)
                            }
                        }
                    }
                }
            }
        })
}
