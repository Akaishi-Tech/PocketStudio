package com.hcgstudio.pocketstudio

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private data class Preference(@get:StringRes val title: Int, val options: List<Int> = emptyList(), val row: @Composable (String) -> Unit)
private data class SettingsGroup(@get:StringRes val title: Int, val entries: List<Preference>, val preview: Boolean = false)

@Composable
internal fun SettingsPage(
    settings: StudioSettings,
    onChange: (StudioSettings) -> Unit,
    dismiss: () -> Unit,
    onKeymap: () -> Unit,
    onSdk: (() -> Unit)?,
    onCheckUpdates: () -> Unit,
    onPlugins: () -> Unit
) {
    val groups = settingsGroups(settings, editorPalette(settings), onChange, onKeymap, onSdk, onCheckUpdates, onPlugins)
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val categoryListState = rememberLazyListState()
    val categoryState = rememberSaveableStateHolder()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var resetMenu by remember { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val sidebarWidth = (maxWidth * .32f).coerceIn(184.dp, 256.dp)
            val current = groups.firstOrNull { it.title == selected } ?: groups.firstOrNull()?.takeIf { wide }
            val back = {
                when {
                    searching -> {
                        keyboard?.hide()
                        query = ""
                        searching = false
                    }
                    !wide && selected != null -> selected = null
                    else -> dismiss()
                }
            }
            BackHandler(onBack = back)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIconButton(R.drawable.studio_arrow_back, stringResource(R.string.back), onClick = back)
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus)
                            .padding(end = 4.dp, top = 4.dp, bottom = 4.dp), singleLine = true,
                            placeholder = { Text(stringResource(R.string.settings_search)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            trailingIcon = if (query.isNotEmpty()) { {
                                StudioIconButton(R.drawable.studio_close, stringResource(R.string.settings_clear_search)) {
                                    query = ""
                                    focus.requestFocus()
                                }
                            } } else null)
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        Text(stringResource(if (wide) R.string.settings else current?.title ?: R.string.settings),
                            Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        StudioIconButton(R.drawable.studio_search, stringResource(R.string.settings_search)) { searching = true }
                        Box {
                            StudioIconButton(R.drawable.studio_more_vert, stringResource(R.string.settings_more)) { resetMenu = true }
                            DropdownMenu(resetMenu, { resetMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.settings_restore_all)) },
                                    enabled = settings != StudioSettings(), onClick = { resetMenu = false; confirmReset = true })
                            }
                        }
                    }
                }
                HorizontalDivider()
                StudioPageTransition(searching, { 0 }, Modifier.fillMaxSize()) { showResults ->
                    if (showResults) SettingsSearchResults(groups, settings, query)
                    else Row(Modifier.fillMaxSize()) {
                        if (wide) {
                            SettingsCategoryList(groups, current?.title, true, categoryListState, Modifier.width(sidebarWidth)) {
                                if (it == R.string.plugin_store) onPlugins() else selected = it
                            }
                            VerticalDivider()
                        }
                        key(wide) {
                            StudioPageTransition(current?.title, { if (it == null) 0 else 1 }, Modifier.fillMaxSize()) { title ->
                                if (title == null) {
                                    SettingsCategoryList(groups, null, false, categoryListState, Modifier.fillMaxWidth()) {
                                        if (it == R.string.plugin_store) onPlugins() else selected = it
                                    }
                                } else {
                                    val group = groups.first { it.title == title }
                                    categoryState.SaveableStateProvider(title) {
                                        SettingsContent(listOf(group), settings, headings = wide)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.settings_confirm_reset)) },
        text = { Text(stringResource(R.string.settings_reset_scope)) },
        confirmButton = { TextButton({ onChange(StudioSettings()); confirmReset = false }) {
            Text(stringResource(R.string.settings_restore))
        } },
        dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SettingsSearchResults(groups: List<SettingsGroup>, settings: StudioSettings, query: String) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val english = remember(context, configuration) {
        context.createConfigurationContext(Configuration(configuration).apply {
            setLocale(Locale.ENGLISH)
        }).resources
    }
    val term = query.trim()
    fun matchesLabel(@StringRes id: Int) =
        resources.getString(id).contains(term, ignoreCase = true) || english.getString(id).contains(term, ignoreCase = true)
    val matches = groups.mapNotNull { group ->
        val entries = if (matchesLabel(group.title)) group.entries
        else group.entries.filter { matchesLabel(it.title) || it.options.any(::matchesLabel) }
        group.copy(entries = entries, preview = false).takeIf { entries.isNotEmpty() }
    }
    if (matches.isEmpty()) Text(stringResource(R.string.no_matches),
        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else SettingsContent(matches, settings, headings = true)
}

@Composable
private fun SettingsCategoryList(
    groups: List<SettingsGroup>, selected: Int?, wide: Boolean, state: LazyListState,
    modifier: Modifier, onSelect: (Int) -> Unit
) {
    LazyColumn(modifier.fillMaxHeight().selectableGroup(), state = state, contentPadding = PaddingValues(8.dp)) {
        items(groups, key = { it.title }) { group ->
            val active = wide && selected == group.title
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .then(if (wide) Modifier.selectable(active, onClick = { onSelect(group.title) })
                    else Modifier.clickable(role = Role.Button) { onSelect(group.title) })
                .heightIn(min = if (wide) 48.dp else 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(group.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                if (!wide) Symbol(R.drawable.studio_chevron_right, null)
            }
        }
    }
}

@Composable
private fun SettingsContent(groups: List<SettingsGroup>, settings: StudioSettings, headings: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            groups.forEach { group ->
                if (headings) item(key = "group-${group.title}") {
                    Text(stringResource(group.title), Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 8.dp)
                        .semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (group.preview) item(key = "preview-${group.title}") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
                        val height = with(LocalDensity.current) {
                            (settings.editorFontSize * settings.lineSpacing).sp.toDp() * 5 + 24.dp
                        }
                        EditorPreview(settings, Modifier.fillMaxWidth().height(height.coerceIn(112.dp, 224.dp)))
                    }
                }
                items(group.entries, key = { it.title }) { entry -> entry.row(stringResource(entry.title)) }
            }
        }
    }
}

@Composable
private fun PreferenceRow(title: String, onClick: () -> Unit, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 54.dp)
        .padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.widthIn(max = 148.dp), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun ValueText(value: String) = Text(value, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun toggle(@StringRes title: Int, checked: Boolean, enabled: Boolean = true,
                   @StringRes summary: Int? = null, set: (Boolean) -> Unit) =
    Preference(title, listOfNotNull(summary)) { name ->
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = set)
            .heightIn(min = 54.dp).padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                if (summary != null) Text(stringResource(summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }

private fun <T> choice(@StringRes title: Int, value: T, choices: List<T>, label: (T) -> Int, set: (T) -> Unit) =
    Preference(title, choices.map(label)) { name ->
        var open by rememberSaveable { mutableStateOf(false) }
        PreferenceRow(name, { open = true }) { ValueText(stringResource(label(value))) }
        if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(name) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    choices.forEach { option ->
                        ChoiceRow(stringResource(label(option)), option == value) {
                            if (value != option) set(option)
                            open = false
                        }
                    }
                }
            }, confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } })
    }

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun fontLabel(font: StudioFont): String = when (font) {
    is StudioFont.Preset -> stringResource(font.label)
    is StudioFont.Installed -> font.name
}

private fun fontChoice(@StringRes title: Int, selected: StudioFont, set: (StudioFont) -> Unit) =
    Preference(title, StudioFont.builtIns.map { it.label } + R.string.settings_monospace_only) { name ->
        var open by rememberSaveable { mutableStateOf(false) }
        PreferenceRow(name, { open = true }) { ValueText(fontLabel(selected)) }
        if (open) {
            val fonts by produceState<List<StudioFontOption>?>(null) { value = availableStudioFonts() }
            var monoOnly by rememberSaveable { mutableStateOf(false) }
            val visible = remember(fonts, monoOnly) {
                val all = fonts ?: builtInFontOptions
                if (monoOnly) all.filter { it.isMonospace } else all
            }
            AlertDialog(onDismissRequest = { open = false }, title = { Text(name) },
                text = {
                    Column {
                        FilterChip(selected = monoOnly, onClick = { monoOnly = !monoOnly },
                            label = { Text(stringResource(R.string.settings_monospace_only)) })
                        if (fonts == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).selectableGroup()) {
                            items(visible, key = { it.font.id }) { (font) ->
                                ChoiceRow(fontLabel(font), selected == font) {
                                    set(font)
                                    open = false
                                }
                            }
                        }
                    }
                }, confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }

private fun numeric(@StringRes title: Int, current: Int, range: IntRange,
                    format: (Int) -> String = { it.toString() }, set: (Int) -> Unit) = Preference(title) { name ->
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceRow(name, { open = true }) { ValueText(format(current)) }
    if (open) {
        var input by rememberSaveable { mutableStateOf(current.toString()) }
        val parsed = input.toIntOrNull()?.takeIf { it in range }
        val apply = { parsed?.let { set(it); open = false }; Unit }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                        isError = input.isNotEmpty() && parsed == null, label = { Text("${format(range.first)} – ${format(range.last)}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { apply() }))
                    Slider((parsed ?: current).toFloat(), { input = it.roundToInt().toString() },
                        valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
                        modifier = Modifier.semantics { contentDescription = name })
                }
            }, confirmButton = { TextButton(apply, enabled = parsed != null) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun link(@StringRes title: Int, open: () -> Unit) = Preference(title) { name ->
    PreferenceRow(name, open) { Symbol(R.drawable.studio_chevron_right, null) }
}

private fun colorHex(rgb: Int) = "#%06X".format(Locale.ROOT, rgb and 0xffffff)

private fun colorEntry(role: EditorColorRole, color: Color, settings: StudioSettings, change: (StudioSettings) -> Unit) =
    Preference(role.label) { name ->
        var open by rememberSaveable { mutableStateOf(false) }
        PreferenceRow(name, { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(color)
                ValueText(colorHex(color.toArgb()))
            }
        }
        if (open) {
            ColorPicker(name, color, custom = role in settings.colorOverrides, dismiss = { open = false }) { rgb ->
                val updated = if (rgb == null) settings.colorOverrides - role else settings.colorOverrides + (role to rgb)
                change(settings.withColorOverrides(updated))
                open = false
            }
        }
    }

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(24.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun ColorPicker(name: String, color: Color, custom: Boolean, dismiss: () -> Unit, set: (Int?) -> Unit) {
    var hex by rememberSaveable { mutableStateOf(colorHex(color.toArgb())) }
    val digits = hex.trim().removePrefix("#")
    val rgb = digits.takeIf { it.length == 6 && it.all { char -> char in "0123456789abcdefABCDEF" } }
        ?.toInt(16)?.or(0xff000000.toInt())
    val shades = listOf(0xff24262a, 0xff787e87, 0xffd8dce2, 0xfffafafa,
        0xffcf6878, 0xffd69357, 0xff6da66f, 0xff4d9a9a, 0xff588acf, 0xff946cc2)
    AlertDialog(onDismissRequest = dismiss, title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hex, { hex = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.settings_hex)) }, placeholder = { Text("#RRGGBB") },
                    leadingIcon = { Swatch(rgb?.let { Color(it) } ?: color) }, isError = hex.isNotEmpty() && rgb == null,
                    supportingText = if (hex.isNotEmpty() && rgb == null) { { Text(stringResource(R.string.settings_invalid_hex)) } } else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { rgb?.let(set) }))
                FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
                    for (shade in shades) {
                        val swatch = colorHex(shade.toInt())
                        Box(Modifier.size(48.dp).selectable(rgb == shade.toInt(), role = Role.RadioButton,
                            onClick = { hex = swatch }).semantics { contentDescription = swatch },
                            contentAlignment = Alignment.Center) {
                            if (rgb == shade.toInt()) Box(Modifier.size(32.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
                            Swatch(Color(shade))
                        }
                    }
                }
                if (custom) TextButton({ set(null) }) { Text(stringResource(R.string.restore_default)) }
            }
        },
        confirmButton = { TextButton({ rgb?.let(set) }, enabled = rgb != null) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } })
}

@StringRes
private fun themeLabel(theme: StudioThemeMode) = when (theme) {
    StudioThemeMode.System -> R.string.settings_system_theme
    StudioThemeMode.Light -> R.string.settings_light
    StudioThemeMode.Dark -> R.string.settings_dark
    StudioThemeMode.HighContrast -> R.string.settings_contrast
}

private fun settingsGroups(
    s: StudioSettings, palette: Map<EditorColorRole, Color>, change: (StudioSettings) -> Unit,
    keymap: () -> Unit, sdk: (() -> Unit)?, checkUpdates: () -> Unit, plugins: () -> Unit
): List<SettingsGroup> {
    val sp: (Int) -> String = { "$it sp" }
    return listOfNotNull(
        SettingsGroup(R.string.settings_appearance, listOf(
            choice(R.string.settings_theme, s.theme, StudioThemeMode.entries, ::themeLabel) { change(s.copy(theme = it)) },
            fontChoice(R.string.settings_ui_font, s.uiFont) { change(s.copy(uiFont = it)) },
            numeric(R.string.settings_ui_size, s.uiFontSize, 11..20, sp) { change(s.copy(uiFontSize = it)) },
            toggle(R.string.settings_compact, s.compactMode) { change(s.copy(compactMode = it)) },
            toggle(R.string.settings_toolbar_actions, s.showMainToolbarActions) { change(s.copy(showMainToolbarActions = it)) },
            toggle(R.string.settings_tabs, s.showEditorTabs) { change(s.copy(showEditorTabs = it)) },
            toggle(R.string.settings_tab_close, s.showTabCloseButtons, enabled = s.showEditorTabs) { change(s.copy(showTabCloseButtons = it)) },
            toggle(R.string.settings_status, s.showStatusBar) { change(s.copy(showStatusBar = it)) }
        )),
        SettingsGroup(R.string.settings_editor_font, listOf(
            fontChoice(R.string.settings_font_family, s.editorFont) { change(s.copy(editorFont = it)) },
            numeric(R.string.settings_editor_size, s.editorFontSize, 10..32, sp) { change(s.copy(editorFontSize = it)) },
            toggle(R.string.settings_font_ligatures, s.fontLigatures) { change(s.copy(fontLigatures = it)) },
            numeric(R.string.settings_line_spacing, (s.lineSpacing * 100).roundToInt(), 100..200, { "$it%" }) {
                change(s.copy(lineSpacing = it / 100f))
            }
        ), preview = true),
        SettingsGroup(R.string.settings_colors, buildList {
            add(choice(R.string.settings_scheme, s.editorScheme, EditorScheme.entries, { it.label }) { change(s.copy(editorScheme = it)) })
            EditorColorRole.entries.forEach { add(colorEntry(it, palette.getValue(it), s, change)) }
            if (s.colorOverrides.isNotEmpty()) add(Preference(R.string.settings_reset_colors) { name ->
                var confirm by rememberSaveable { mutableStateOf(false) }
                TextButton({ confirm = true }, Modifier.padding(horizontal = 6.dp)) { Text(name) }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.settings_confirm_colors, stringResource(s.editorScheme.label))) },
                    confirmButton = { TextButton({ change(s.withColorOverrides(emptyMap())); confirm = false }) {
                        Text(stringResource(R.string.settings_restore))
                    } },
                    dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } })
            })
        }, preview = true),
        SettingsGroup(R.string.settings_editor_display, listOf(
            toggle(R.string.settings_line_numbers, s.showLineNumbers) { change(s.copy(showLineNumbers = it)) },
            toggle(R.string.settings_soft_wraps, s.softWraps) { change(s.copy(softWraps = it)) },
            toggle(R.string.settings_current_line, s.highlightCurrentLine) { change(s.copy(highlightCurrentLine = it)) },
            toggle(R.string.settings_whitespace, s.showWhitespace) { change(s.copy(showWhitespace = it)) },
            toggle(R.string.settings_margin, s.showRightMargin) { change(s.copy(showRightMargin = it)) },
            numeric(R.string.settings_margin_column, s.rightMargin, 40..200) { change(s.copy(rightMargin = it)) }
        )),
        SettingsGroup(R.string.settings_typing, listOf(
            numeric(R.string.settings_indent_size, s.indentSize, 1..8) { change(s.copy(indentSize = it)) },
            toggle(R.string.settings_use_tabs, s.useTabs) { change(s.copy(useTabs = it)) },
            toggle(R.string.settings_auto_indent, s.autoIndent) { change(s.copy(autoIndent = it)) },
            toggle(R.string.settings_pair_brackets, s.pairBrackets) { change(s.copy(pairBrackets = it)) },
            toggle(R.string.settings_pair_quotes, s.pairQuotes) { change(s.copy(pairQuotes = it)) },
            link(R.string.keyboard_shortcuts, keymap)
        )),
        SettingsGroup(R.string.settings_files, listOf(
            toggle(R.string.settings_reopen, s.reopenLastProject) { change(s.copy(reopenLastProject = it)) },
            toggle(R.string.settings_background_save, s.saveOnBackground) { change(s.copy(saveOnBackground = it)) },
            toggle(R.string.settings_trim, s.trimTrailingSpaces) { change(s.copy(trimTrailingSpaces = it)) },
            toggle(R.string.settings_final_newline, s.ensureFinalNewline) { change(s.copy(ensureFinalNewline = it)) }
        )),
        sdk?.let { SettingsGroup(R.string.settings_build_tools, listOf(
            toggle(R.string.settings_offline, s.gradleOffline) { change(s.copy(gradleOffline = it)) },
            toggle(R.string.settings_stacktrace, s.gradleStacktrace) { change(s.copy(gradleStacktrace = it)) },
            numeric(R.string.settings_workers, s.gradleWorkers, 1..8) { change(s.copy(gradleWorkers = it)) },
            link(R.string.sdk_manager, it)
        )) },
        SettingsGroup(R.string.settings_console, listOf(
            numeric(R.string.settings_console_size, s.consoleFontSize, 10..32, sp) { change(s.copy(consoleFontSize = it)) },
            toggle(R.string.settings_console_wraps, s.consoleSoftWraps) { change(s.copy(consoleSoftWraps = it)) }
        )),
        SettingsGroup(R.string.plugin_store, listOf(link(R.string.plugin_store, plugins))),
        SettingsGroup(R.string.settings_app_updates, listOf(
            Preference(R.string.check_for_updates) { name ->
                PreferenceRow(name, checkUpdates) {
                    ValueText(stringResource(R.string.settings_installed_version, BuildConfig.VERSION_NAME))
                }
            },
            toggle(R.string.settings_auto_update, s.autoUpdate, summary = R.string.settings_auto_update_summary) {
                change(s.copy(autoUpdate = it))
            }
        ))
    )
}
