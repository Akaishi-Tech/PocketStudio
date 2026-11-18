package com.hcgstudio.pocketstudio

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

internal enum class EditorScheme(@get:StringRes val label: Int) {
    THEME(R.string.editor_scheme_theme),
    DARCULA(R.string.editor_scheme_darcula),
    LIGHT(R.string.editor_scheme_light),
    HIGH_CONTRAST(R.string.editor_scheme_contrast),
    SOLARIZED_DARK(R.string.editor_scheme_solarized_dark),
    SOLARIZED_LIGHT(R.string.editor_scheme_solarized_light)
}

internal enum class EditorColorRole(@get:StringRes val label: Int) {
    BACKGROUND(R.string.editor_color_background), TEXT(R.string.editor_color_text),
    GUTTER(R.string.editor_color_gutter), LINE_NUMBER(R.string.editor_color_line_number),
    CURRENT_LINE(R.string.editor_color_current_line), CARET(R.string.editor_color_caret),
    SELECTION(R.string.editor_color_selection), COMMENT(R.string.editor_color_comment),
    STRING(R.string.editor_color_string), KEYWORD(R.string.editor_color_keyword),
    TYPE(R.string.editor_color_type), NUMBER(R.string.editor_color_number)
}

private fun palette(
    background: Int, text: Int, gutter: Int, lineNumber: Int, currentLine: Int, caret: Int,
    selection: Int, comment: Int, string: Int, keyword: Int, type: Int, number: Int
) = mapOf(
    EditorColorRole.BACKGROUND to background, EditorColorRole.TEXT to text,
    EditorColorRole.GUTTER to gutter, EditorColorRole.LINE_NUMBER to lineNumber,
    EditorColorRole.CURRENT_LINE to currentLine, EditorColorRole.CARET to caret,
    EditorColorRole.SELECTION to selection, EditorColorRole.COMMENT to comment,
    EditorColorRole.STRING to string, EditorColorRole.KEYWORD to keyword,
    EditorColorRole.TYPE to type, EditorColorRole.NUMBER to number
).mapValues { Color(it.value or 0xFF000000.toInt()) }

private val schemes = mapOf(
    EditorScheme.DARCULA to palette(
        0x2B2B2B, 0xA9B7C6, 0x313335, 0x90969B, 0x323232, 0xBBBBBB,
        0x214283, 0x929292, 0xA5C261, 0xCC7832, 0xA9B7C6, 0x6897BB
    ),
    EditorScheme.LIGHT to palette(
        0xFFFFFF, 0x080808, 0xF2F2F2, 0x767676, 0xFCFAED, 0x080808,
        0xA6D2FF, 0x707070, 0x067D17, 0x0033B3, 0x1750EB, 0x1750EB
    ),
    EditorScheme.HIGH_CONTRAST to palette(
        0x131314, 0xEBEBEB, 0x131314, 0x999999, 0x000066, 0xFFFFFF,
        0x006280, 0x7EC3E6, 0x54B33E, 0xED864A, 0xE1C1FF, 0x33CCFF
    ),
    EditorScheme.SOLARIZED_DARK to palette(
        0x002B36, 0x93A1A1, 0x073642, 0x839496, 0x073642, 0xEEE8D5,
        0x10536A, 0x839496, 0x2AA198, 0xB58900, 0x268BD2, 0xD33682
    ),
    EditorScheme.SOLARIZED_LIGHT to palette(
        0xFDF6E3, 0x586E75, 0xEEE8D5, 0x657B83, 0xEEE8D5, 0x073642,
        0xC9E1DE, 0x657B83, 0x147D75, 0x866600, 0x126FA5, 0xAE296C
    )
)

@Composable
internal fun editorPalette(settings: StudioSettings): Map<EditorColorRole, Color> {
    val colors = MaterialTheme.colorScheme
    return remember(colors, settings.editorScheme, settings.colorOverrides) {
        val base = schemes[settings.editorScheme] ?: mapOf(
            EditorColorRole.BACKGROUND to colors.surface,
            EditorColorRole.TEXT to colors.onSurface,
            EditorColorRole.GUTTER to colors.surfaceContainerLow,
            EditorColorRole.LINE_NUMBER to colors.onSurfaceVariant,
            EditorColorRole.CURRENT_LINE to colors.primary.copy(alpha = .07f).compositeOver(colors.surface),
            EditorColorRole.CARET to colors.primary,
            EditorColorRole.SELECTION to colors.primary.copy(alpha = .3f).compositeOver(colors.surface),
            EditorColorRole.COMMENT to colors.onSurfaceVariant,
            EditorColorRole.STRING to colors.tertiary,
            EditorColorRole.KEYWORD to colors.primary,
            EditorColorRole.TYPE to colors.secondary,
            EditorColorRole.NUMBER to colors.tertiary
        )
        base + settings.colorOverrides.mapValues { Color(it.value) }
    }
}
