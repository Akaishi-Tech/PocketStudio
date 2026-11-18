package com.hcgstudio.pocketstudio

import android.content.Context

object SettingsChecks {
    @JvmStatic fun run(context: Context) {
        // Exercise every persisted preference with a non-default value.
        val defaults = StudioSettings()
        val configured = StudioSettings(
            theme = StudioThemeMode.HighContrast, uiFont = StudioFont.Inter, uiFontSize = 18,
            compactMode = true, showMainToolbarActions = false, showEditorTabs = false,
            showTabCloseButtons = false, showStatusBar = false, editorFont = StudioFont.JetBrainsMono,
            editorFontSize = 22, lineSpacing = 1.4f, fontLigatures = true, editorScheme = EditorScheme.LIGHT,
            showLineNumbers = false, softWraps = true, highlightCurrentLine = false, showWhitespace = true,
            showRightMargin = true, rightMargin = 120, indentSize = 2, useTabs = true,
            autoIndent = false, pairBrackets = false, pairQuotes = false, reopenLastProject = true,
            saveOnBackground = true, trimTrailingSpaces = true, ensureFinalNewline = true,
            gradleOffline = true, gradleStacktrace = true, gradleWorkers = 1,
            consoleFontSize = 20, consoleSoftWraps = false, autoUpdate = false
        ).withColorOverrides(mapOf(EditorColorRole.COMMENT to 0xff225533.toInt()))
        val dark = configured.copy(editorScheme = EditorScheme.DARCULA)
            .withColorOverrides(mapOf(EditorColorRole.COMMENT to 0xffbbccdd.toInt()))
        val file = "studio-settings-test"
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            check(StudioSettings.read(prefs) == defaults)
            dark.save(prefs)
            check(StudioSettings.read(prefs) == dark)
            check(dark.copy(editorScheme = EditorScheme.LIGHT).colorOverrides == configured.colorOverrides)
            dark.withColorOverrides(emptyMap()).save(prefs)
            check(StudioSettings.read(prefs).schemeColors == configured.schemeColors)
            defaults.save(prefs)
            check(StudioSettings.read(prefs) == defaults)
            prefs.edit().putString("theme", "Unknown").putString("softWraps", "bad")
                .putInt("indentSize", 100).putFloat("lineSpacing", Float.NaN).apply()
            check(StudioSettings.read(prefs) == defaults.copy(indentSize = 8))
        } finally {
            context.deleteSharedPreferences(file)
        }

        val original = "first   \r\n\tsecond\t\nlast  "
        check(prepareForSave(original, defaults) == original)
        val cleaned = prepareForSave(original, configured)
        check(cleaned == "first\r\n\tsecond\nlast\r\n")
        check(savedOffset(original, cleaned, 7) == 5)
        check(savedOffset(original, cleaned, original.indexOf("second") + 3) == cleaned.indexOf("second") + 3)
        check(savedOffset(original, cleaned, original.length) == cleaned.indexOf("last") + 4)
        check(prepareForSave(cleaned, configured) == cleaned)
        check(prepareForSave("", configured).isEmpty())
        check(prepareForSave("a  \n\tb", configured.copy(trimTrailingSpaces = false)) == "a  \n\tb\n")
    }
}
