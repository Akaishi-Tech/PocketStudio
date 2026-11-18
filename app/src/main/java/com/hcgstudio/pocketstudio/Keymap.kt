package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp

internal class Keymap(private val prefs: SharedPreferences) {
    private val bindings = mutableStateMapOf<String, String>()
    init { for ((id, value) in prefs.all) if (value is String) bindings[id] = value }
    fun apply(action: StudioAction) = bindings[action.id]?.let { action.copy(shortcut = it) } ?: action
    fun bind(id: String, value: String?) {
        if (value == null) { bindings.remove(id); prefs.edit().remove(id).apply() }
        else { bindings[id] = value; prefs.edit().putString(id, value).apply() }
    }
}

private fun shortcutOf(e: KeyEvent): String? {
    if (KeyEvent.isModifierKey(e.keyCode)) return null
    val name = when (e.keyCode) {
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ->
            KeyEvent.keyCodeToString(e.keyCode).substringAfter("DPAD_").lowercase().replaceFirstChar { it.uppercaseChar() }
        else -> KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
    }
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    if (!ctrl && !e.isAltPressed && e.keyCode !in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return null
    return listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { e.isAltPressed }, "Shift".takeIf { e.isShiftPressed }, name).joinToString("+")
}

@Composable
internal fun KeymapDialog(actions: List<StudioAction>, defaults: List<StudioAction>, map: Keymap, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var id by remember { mutableStateOf<String?>(null) }
    var conflict by remember(id) { mutableStateOf("") }
    val active = actions.firstOrNull { it.id == id }
    if (active != null) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(id) { focus.requestFocus() }
        fun update(value: String?, effective: String) {
            val other = actions.firstOrNull { it.id != id && effective.isNotBlank() && it.shortcut.equals(effective, true) }
            if (other == null) { map.bind(active.id, value); id = null }
            else conflict = uiString(R.string.is_already_assigned_to, effective, other.label)
        }
        AlertDialog(onDismissRequest = { id = null }, title = { Text(active.label) },
            text = { Column(Modifier.onPreviewKeyEvent {
                val e = it.nativeKeyEvent
                if (e.action != KeyEvent.ACTION_DOWN || e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE) false
                else {
                    val combo = shortcutOf(e)
                    if (combo != null) update(combo, combo)
                    true
                }
            }.focusRequester(focus).focusable()) {
                Text(stringResource(R.string.press_a_new_key_combination_or_function_key))
                if (conflict.isNotEmpty()) Text(conflict, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton({ update(null, defaults.first { it.id == id }.shortcut) }) { Text(stringResource(R.string.restore_default)) }
                    TextButton({ update("", "") }) { Text(stringResource(R.string.remove_binding)) }
                }
            } },
            confirmButton = { TextButton({ id = null }) { Text(stringResource(R.string.back)) } })
        return
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.keyboard_shortcuts)) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.find_an_action_or_shortcut)) }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(actions.filter { listOf(uiString(it.group), it.label, it.shortcut, it.id).any { value -> value.contains(query.trim(), true) } },
                    key = { it.id }) { action ->
                    Row(Modifier.fillMaxWidth().clickable { id = action.id }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(action.label, Modifier.weight(1f))
                        Text(action.shortcut, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text(stringResource(R.string.done)) } })
}
