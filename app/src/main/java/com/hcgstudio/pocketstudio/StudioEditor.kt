package com.hcgstudio.pocketstudio

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.regex.Pattern
import kotlin.math.roundToInt

private val codeTokens = Pattern.compile(
    """(//[^\n]*|/\*[\s\S]*?(?:\*/|\z)|<!--[\s\S]*?(?:-->|\z))|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|\b(abstract|annotation|as|assert|boolean|break|by|byte|case|catch|char|class|companion|const|constructor|continue|data|default|do|double|else|enum|extends|false|final|finally|float|for|fun|if|implements|import|in|instanceof|int|interface|internal|is|lateinit|long|native|new|null|object|open|out|override|package|private|protected|public|return|sealed|short|static|super|suspend|switch|synchronized|this|throw|throws|transient|true|try|typealias|typeof|val|var|void|volatile|when|where|while)\b|(@[\w.]+|\b[A-Z]\w*\b)|\b(\d+(?:\.\d+)?)\b"""
)

private fun highlight(text: String, colors: Map<EditorColorRole, Color>, language: EditorLanguage?): AnnotatedString {
    if (text.length > 160_000) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    val roles = listOf(EditorColorRole.COMMENT, EditorColorRole.STRING, EditorColorRole.KEYWORD,
        EditorColorRole.TYPE, EditorColorRole.NUMBER)
    val match = (language?.tokens ?: codeTokens).matcher(text)
    while (match.find()) {
        val index = (1..roles.size).first { match.start(it) >= 0 } - 1
        builder.addStyle(SpanStyle(color = colors.getValue(roles[index])), match.start(), match.end())
    }
    return builder.toAnnotatedString()
}

@Composable
internal fun StudioEditor(
    state: StudioEditorState,
    modifier: Modifier = Modifier,
    breakpoints: Set<Int> = emptySet(),
    onBreakpoint: (Int) -> Unit = {}
) = key(state) {
    EditorContent(state, LocalStudioSettings.current, modifier, breakpoints, onBreakpoint, preview = false)
}

@Composable
internal fun EditorPreview(settings: StudioSettings, modifier: Modifier = Modifier) {
    val sample = remember {
        val code = "// MainActivity.kt\nclass MainActivity {\n    val title = \"Studio\"\n    val enabled = 1 != 0\n}"
        StudioEditorState(code, "MainActivity.kt", code.indexOf("title"))
    }
    val height = with(LocalDensity.current) { (settings.editorFontSize * settings.lineSpacing * sample.lineCount).sp.toDp() } + 24.dp
    EditorContent(sample, settings, modifier.height(height), emptySet(), {}, preview = true)
}

@Composable
private fun EditorContent(
    state: StudioEditorState,
    settings: StudioSettings,
    modifier: Modifier,
    breakpoints: Set<Int>,
    onBreakpoint: (Int) -> Unit,
    preview: Boolean
) {
    val colors = editorPalette(settings)
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tabs = remember(state.text, settings.indentSize) { EditorTabStops(state.text, settings.indentSize) }
    val highlighted = remember(tabs, colors, preview) {
        val code = highlight(tabs.display, colors, state.language)
        if (preview) {
            val selection = tabs.display.indexOf("Studio")
            AnnotatedString.Builder(code).apply {
                addStyle(SpanStyle(background = colors.getValue(EditorColorRole.SELECTION)), selection, selection + "Studio".length)
            }.toAnnotatedString()
        } else code
    }
    val syntax = remember(tabs, highlighted) {
        VisualTransformation { source ->
            if (source.text == tabs.source) TransformedText(highlighted, tabs)
            else {
                // The input session may lay out a committed edit before composition catches up.
                val updated = EditorTabStops(source.text, settings.indentSize)
                TransformedText(highlight(updated.display, colors, state.language), updated)
            }
        }
    }
    val pixels = LocalDensity.current
    val breakpoint = painterResource(R.drawable.studio_fiber_manual_record)
    val breakpointTint = ColorFilter.tint(MaterialTheme.colorScheme.error)
    val caret = colors.getValue(EditorColorRole.CARET)
    val numbers = colors.getValue(EditorColorRole.LINE_NUMBER)
    val pad = 12.dp
    val fontSize = settings.editorFontSize
    val editorFamily = LocalEditorFontFamily.current
    val style = TextStyle(fontFamily = editorFamily, fontSize = fontSize.sp,
        lineHeight = (fontSize * settings.lineSpacing).sp, color = colors.getValue(EditorColorRole.TEXT),
        fontFeatureSettings = if (settings.fontLigatures) "'liga' 1, 'calt' 1" else "'liga' 0, 'calt' 0",
        textDirection = TextDirection.Ltr)
    val measure = rememberTextMeasurer()
    val columnWidth = remember(measure, style) { measure.measure("0000000000", style).size.width / 10f }
    val gutterFont = (fontSize - 2).sp
    val editorTypeface by LocalFontFamilyResolver.current.resolve(editorFamily)
    val numberPaint = remember(editorTypeface, gutterFont, pixels) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = editorTypeface as? Typeface ?: Typeface.MONOSPACE
            textSize = with(pixels) { gutterFont.toPx() }
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val digits = state.lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = if (settings.showLineNumbers) with(pixels) { numberPaint.measureText("0".repeat(digits)).toDp() } + 23.dp else 18.dp
    val updatedBreakpoint by rememberUpdatedState(onBreakpoint)
    // A layout from the previous edit can briefly be present before the new text is measured.
    val metrics = layout?.takeIf { it.layoutInput.text.text == tabs.display }
    Row(modifier.background(colors.getValue(EditorColorRole.BACKGROUND)).clipToBounds()) {
        val gutterInput = if (preview) Modifier else Modifier.pointerInput(state, tabs) {
            detectTapGestures { position ->
                val current = layout ?: return@detectTapGestures
                if (current.layoutInput.text.text != tabs.display || state.text != tabs.source) return@detectTapGestures
                val y = position.y + vertical.value - pad.toPx()
                if (y >= 0 && y < current.getLineBottom(current.lineCount - 1)) {
                    val start = current.getLineStart(current.getLineForVerticalPosition(y))
                    updatedBreakpoint(state.lineAt(tabs.transformedToOriginal(start)))
                }
            }
        }
        Canvas(Modifier.fillMaxHeight().width(gutterWidth).background(colors.getValue(EditorColorRole.GUTTER)).then(gutterInput)) {
            if (metrics == null) return@Canvas
            val top = pad.toPx() - vertical.value
            val first = metrics.getLineForVerticalPosition(-top)
            val last = metrics.getLineForVerticalPosition(size.height - top)
            for (index in first..last) {
                val start = metrics.getLineStart(index)
                if (start > 0 && tabs.display[start - 1] != '\n') continue
                val number = state.lineAt(tabs.transformedToOriginal(start))
                val baseline = top + metrics.getLineBaseline(index)
                if (settings.showLineNumbers) {
                    numberPaint.color = (if (number == state.line) caret else numbers).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(number.toString(), size.width - 6.dp.toPx(), baseline, numberPaint)
                }
                if (number in breakpoints) {
                    val side = 12.dp.toPx()
                    translate(left = 2.dp.toPx(), top = baseline - 10.dp.toPx()) {
                        with(breakpoint) { draw(Size(side, side), colorFilter = breakpointTint) }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            val focus = tabs.originalToTransformed(state.cursor)
            val wrap = settings.softWraps
            LaunchedEffect(metrics, focus, maxWidth, maxHeight, wrap) {
                if (metrics == null || preview) return@LaunchedEffect
                val hit = metrics.getCursorRect(focus)
                with(pixels) {
                    val y = when {
                        hit.top < vertical.value -> hit.top
                        hit.bottom + 2 * pad.toPx() > vertical.value + viewportHeight.toPx() -> hit.bottom + 2 * pad.toPx() - viewportHeight.toPx()
                        else -> vertical.value.toFloat()
                    }
                    vertical.scrollTo(y.roundToInt().coerceAtLeast(0))
                    if (!wrap) {
                        val x = when {
                            hit.left < horizontal.value -> hit.left
                            hit.right + 2 * pad.toPx() > horizontal.value + viewportWidth.toPx() -> hit.right + 2 * pad.toPx() - viewportWidth.toPx()
                            else -> horizontal.value.toFloat()
                        }
                        horizontal.scrollTo(x.roundToInt().coerceAtLeast(0))
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                if (metrics == null) return@Canvas
                val left = pad.toPx() - (if (wrap) 0 else horizontal.value)
                val top = pad.toPx() - vertical.value
                val visual = metrics.getLineForOffset(focus)
                if (settings.highlightCurrentLine) {
                    drawRect(colors.getValue(EditorColorRole.CURRENT_LINE), Offset(0f, top + metrics.getLineTop(visual)),
                        Size(size.width, metrics.getLineBottom(visual) - metrics.getLineTop(visual)))
                }
                if (settings.showRightMargin) {
                    val x = left + settings.rightMargin * columnWidth
                    drawLine(numbers.copy(alpha = .55f), Offset(x, 0f), Offset(x, size.height))
                }
                if (settings.showWhitespace) drawWhitespace(metrics, tabs, left, top, numbers)
                if (preview) {
                    val cursor = metrics.getCursorRect(focus)
                    drawLine(caret, Offset(left + cursor.left, top + cursor.top), Offset(left + cursor.left, top + cursor.bottom), 1.dp.toPx())
                }
            }
            val content = Modifier.fillMaxSize().verticalScroll(vertical)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal).widthIn(min = viewportWidth))
                .heightIn(min = viewportHeight).padding(pad)
            if (preview) {
                BasicText(highlighted, modifier = content, style = style, onTextLayout = { layout = it })
            } else {
                val selection = TextSelectionColors(caret, colors.getValue(EditorColorRole.SELECTION))
                CompositionLocalProvider(LocalTextSelectionColors provides selection) {
                    BasicTextField(
                        value = state.value,
                        onValueChange = { state.type(it, settings) },
                        modifier = content.semantics { contentDescription = uiString(R.string.source_code) },
                        textStyle = style,
                        cursorBrush = SolidColor(caret),
                        visualTransformation = syntax,
                        onTextLayout = { layout = it },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                    )
                }
            }
        }
    }
}

/** Iterate only the source characters inside the viewport, including a partially visible tab. */
private fun DrawScope.drawWhitespace(metrics: TextLayoutResult, tabs: EditorTabStops, left: Float, top: Float, color: Color) {
    val first = metrics.getLineForVerticalPosition(-top)
    val last = metrics.getLineForVerticalPosition(size.height - top)
    for (line in first..last) {
        val center = (metrics.getLineTop(line) + metrics.getLineBottom(line)) / 2f
        val from = metrics.getOffsetForPosition(Offset(-left, center))
        val to = metrics.getOffsetForPosition(Offset(size.width - left, center))
        val start = tabs.transformedToOriginal(minOf(from, to))
        val end = tabs.transformedToOriginal(maxOf(from, to))
        for (offset in start..minOf(end, tabs.source.lastIndex)) {
            val ch = tabs.source[offset]
            if (ch != ' ' && ch != '\t') continue
            val position = tabs.originalToTransformed(offset)
            val bounds = metrics.getBoundingBox(position)
            val y = top + bounds.center.y
            if (ch == ' ') {
                drawCircle(color, .85.dp.toPx(), Offset(left + bounds.center.x, y))
            } else {
                val finalSpace = minOf(tabs.originalToTransformed(offset + 1), metrics.getLineEnd(line)) - 1
                if (finalSpace < position) continue
                val right = left + metrics.getBoundingBox(finalSpace).right - 1.dp.toPx()
                val tip = 2.dp.toPx()
                drawLine(color, Offset(left + bounds.left + 1.dp.toPx(), y), Offset(right, y))
                drawLine(color, Offset(right - tip, y - tip), Offset(right, y))
                drawLine(color, Offset(right - tip, y + tip), Offset(right, y))
            }
        }
    }
}
