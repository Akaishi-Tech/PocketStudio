package com.hcgstudio.pocketstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.edit

internal enum class StudioThemeMode { System, Light, Dark, HighContrast }

internal enum class StudioFont(val family: FontFamily) {
    System(FontFamily.Default),
    JetBrainsMono(FontFamily(Font(R.font.jetbrains_mono_regular))),
    Inter(FontFamily(Font(R.font.siwc_inter_medium))),
    Serif(FontFamily.Serif),
    Monospace(FontFamily.Monospace)
}

internal data class StudioSettings(
    val theme: StudioThemeMode = StudioThemeMode.System,
    val uiFont: StudioFont = StudioFont.System,
    val uiFontSize: Int = 14,
    val compactMode: Boolean = false,
    val showMainToolbarActions: Boolean = true,
    val showEditorTabs: Boolean = true,
    val showTabCloseButtons: Boolean = true,
    val showStatusBar: Boolean = true,
    val editorFont: StudioFont = StudioFont.Monospace,
    val editorFontSize: Int = 14,
    val fontLigatures: Boolean = false,
    val lineSpacing: Float = 1.55f,
    val editorScheme: EditorScheme = EditorScheme.THEME,
    val schemeColors: Map<EditorScheme, Map<EditorColorRole, Int>> = emptyMap(),
    val showLineNumbers: Boolean = true,
    val softWraps: Boolean = false,
    val highlightCurrentLine: Boolean = true,
    val showWhitespace: Boolean = false,
    val showRightMargin: Boolean = false,
    val rightMargin: Int = 100,
    val indentSize: Int = 4,
    val useTabs: Boolean = false,
    val autoIndent: Boolean = true,
    val pairBrackets: Boolean = true,
    val pairQuotes: Boolean = true,
    val reopenLastProject: Boolean = false,
    val saveOnBackground: Boolean = false,
    val trimTrailingSpaces: Boolean = false,
    val ensureFinalNewline: Boolean = false,
    val gradleOffline: Boolean = false,
    val gradleStacktrace: Boolean = false,
    val gradleWorkers: Int = 2,
    val consoleFontSize: Int = 12,
    val consoleSoftWraps: Boolean = true,
    val autoUpdate: Boolean = true
) {
    val colorOverrides get() = schemeColors[editorScheme].orEmpty()

    fun withColorOverrides(colors: Map<EditorColorRole, Int>) =
        copy(schemeColors = if (colors.isEmpty()) schemeColors - editorScheme else schemeColors + (editorScheme to colors))

    fun save(prefs: SharedPreferences) {
        prefs.edit {
            putString("theme", theme.name)
            putString("uiFont", uiFont.name)
            putInt("uiFontSize", uiFontSize)
            putBoolean("compactMode", compactMode)
            putBoolean("showMainToolbarActions", showMainToolbarActions)
            putBoolean("showEditorTabs", showEditorTabs)
            putBoolean("showTabCloseButtons", showTabCloseButtons)
            putBoolean("showStatusBar", showStatusBar)
            putString("editorFont", editorFont.name)
            putInt("editorFontSize", editorFontSize)
            putBoolean("fontLigatures", fontLigatures)
            putFloat("lineSpacing", lineSpacing)
            putString("editorScheme", editorScheme.name)
            for (scheme in EditorScheme.entries) {
                for (role in EditorColorRole.entries) {
                    val key = colorKey(scheme, role)
                    val color = schemeColors[scheme]?.get(role)
                    if (color == null) remove(key) else putInt(key, color)
                }
            }
            putBoolean("showLineNumbers", showLineNumbers)
            putBoolean("softWraps", softWraps)
            putBoolean("highlightCurrentLine", highlightCurrentLine)
            putBoolean("showWhitespace", showWhitespace)
            putBoolean("showRightMargin", showRightMargin)
            putInt("rightMargin", rightMargin)
            putInt("indentSize", indentSize)
            putBoolean("useTabs", useTabs)
            putBoolean("autoIndent", autoIndent)
            putBoolean("pairBrackets", pairBrackets)
            putBoolean("pairQuotes", pairQuotes)
            putBoolean("reopenLastProject", reopenLastProject)
            putBoolean("saveOnBackground", saveOnBackground)
            putBoolean("trimTrailingSpaces", trimTrailingSpaces)
            putBoolean("ensureFinalNewline", ensureFinalNewline)
            putBoolean("gradleOffline", gradleOffline)
            putBoolean("gradleStacktrace", gradleStacktrace)
            putInt("gradleWorkers", gradleWorkers)
            putInt("consoleFontSize", consoleFontSize)
            putBoolean("consoleSoftWraps", consoleSoftWraps)
            putBoolean("autoUpdate", autoUpdate)
        }
    }

    companion object {
        fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("studio-settings", Context.MODE_PRIVATE)

        @JvmStatic fun load(context: Context) = read(preferences(context))

        // Each key has an independent fallback for upgrades from older settings.
        fun read(prefs: SharedPreferences): StudioSettings {
            val defaults = StudioSettings()
            val values = prefs.all
            fun flag(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
            fun number(key: String, fallback: Int, range: IntRange) = (values[key] as? Int)?.coerceIn(range) ?: fallback
            return StudioSettings(
                theme = enumSetting(values["theme"], defaults.theme),
                uiFont = enumSetting(values["uiFont"], defaults.uiFont),
                uiFontSize = number("uiFontSize", defaults.uiFontSize, 11..20),
                compactMode = flag("compactMode", defaults.compactMode),
                showMainToolbarActions = flag("showMainToolbarActions", defaults.showMainToolbarActions),
                showEditorTabs = flag("showEditorTabs", defaults.showEditorTabs),
                showTabCloseButtons = flag("showTabCloseButtons", defaults.showTabCloseButtons),
                showStatusBar = flag("showStatusBar", defaults.showStatusBar),
                editorFont = enumSetting(values["editorFont"], defaults.editorFont),
                editorFontSize = number("editorFontSize", defaults.editorFontSize, 10..32),
                fontLigatures = flag("fontLigatures", defaults.fontLigatures),
                lineSpacing = (values["lineSpacing"] as? Float)?.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: defaults.lineSpacing,
                editorScheme = enumSetting(values["editorScheme"], defaults.editorScheme),
                schemeColors = buildMap {
                    for (scheme in EditorScheme.entries) {
                        val colors = EditorColorRole.entries.mapNotNull { role ->
                            (values[colorKey(scheme, role)] as? Int)?.let { role to it }
                        }.toMap()
                        if (colors.isNotEmpty()) put(scheme, colors)
                    }
                },
                showLineNumbers = flag("showLineNumbers", defaults.showLineNumbers),
                softWraps = flag("softWraps", defaults.softWraps),
                highlightCurrentLine = flag("highlightCurrentLine", defaults.highlightCurrentLine),
                showWhitespace = flag("showWhitespace", defaults.showWhitespace),
                showRightMargin = flag("showRightMargin", defaults.showRightMargin),
                rightMargin = number("rightMargin", defaults.rightMargin, 40..200),
                indentSize = number("indentSize", defaults.indentSize, 1..8),
                useTabs = flag("useTabs", defaults.useTabs),
                autoIndent = flag("autoIndent", defaults.autoIndent),
                pairBrackets = flag("pairBrackets", defaults.pairBrackets),
                pairQuotes = flag("pairQuotes", defaults.pairQuotes),
                reopenLastProject = flag("reopenLastProject", defaults.reopenLastProject),
                saveOnBackground = flag("saveOnBackground", defaults.saveOnBackground),
                trimTrailingSpaces = flag("trimTrailingSpaces", defaults.trimTrailingSpaces),
                ensureFinalNewline = flag("ensureFinalNewline", defaults.ensureFinalNewline),
                gradleOffline = flag("gradleOffline", defaults.gradleOffline),
                gradleStacktrace = flag("gradleStacktrace", defaults.gradleStacktrace),
                gradleWorkers = number("gradleWorkers", defaults.gradleWorkers, 1..8),
                consoleFontSize = number("consoleFontSize", defaults.consoleFontSize, 10..32),
                consoleSoftWraps = flag("consoleSoftWraps", defaults.consoleSoftWraps),
                autoUpdate = flag("autoUpdate", defaults.autoUpdate)
            )
        }

        private fun colorKey(scheme: EditorScheme, role: EditorColorRole) = "color.${scheme.name}.${role.name}"

        private inline fun <reified E : Enum<E>> enumSetting(value: Any?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == value } ?: fallback
    }
}
