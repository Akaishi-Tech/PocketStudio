package com.hcgstudio.pocketstudio

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
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

@Composable
internal fun StudioEditor(
    state: StudioEditorState,
    modifier: Modifier = Modifier,
    breakpoints: Set<Int> = emptySet(),
    onBreakpoint: (Int) -> Unit = {}
) = key(state) {
    val colors = MaterialTheme.colorScheme
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val syntax = remember(colors) {
        val palette = listOf(colors.onSurfaceVariant, colors.tertiary, colors.primary, colors.secondary, colors.tertiary)
        VisualTransformation { source ->
            if (source.length > 160_000) TransformedText(source, OffsetMapping.Identity)
            else {
                val builder = AnnotatedString.Builder(source)
                val match = codeTokens.matcher(source.text)
                while (match.find()) {
                    val index = (1..5).first { match.start(it) >= 0 } - 1
                    builder.addStyle(SpanStyle(color = palette[index]), match.start(), match.end())
                }
                TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
            }
        }
    }
    val pixels = LocalDensity.current
    val breakpoint = painterResource(R.drawable.studio_fiber_manual_record)
    val breakpointTint = remember(colors.error) { ColorFilter.tint(colors.error) }
    val gutterWidth = with(pixels) { (13.sp.toPx() * .62f * (layout?.lineCount ?: 1).toString().length.coerceAtLeast(2)).toDp() } + 22.dp
    val updatedBreakpoint by rememberUpdatedState(onBreakpoint)
    Row(modifier.background(colors.surface).clipToBounds()) {
        Canvas(Modifier.fillMaxHeight().width(gutterWidth).background(colors.surfaceContainerLow)
            .pointerInput(state) {
                detectTapGestures { position ->
                    val metrics = layout ?: return@detectTapGestures
                    val y = position.y + vertical.value - 12.dp.toPx()
                    if (y in 0f..metrics.getLineBottom(metrics.lineCount - 1)) {
                        updatedBreakpoint(1 + metrics.getLineForVerticalPosition(y))
                    }
                }
            }) {
            val metrics = layout ?: return@Canvas
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            val top = 12.dp.toPx() - vertical.value
            val first = metrics.getLineForVerticalPosition(-top)
            val last = metrics.getLineForVerticalPosition(size.height - top)
            for (index in first..last) {
                val number = index + 1
                val baseline = top + metrics.getLineBaseline(index)
                paint.color = (if (number == state.line) colors.primary else colors.onSurfaceVariant).toArgb()
                drawContext.canvas.nativeCanvas.drawText(number.toString(), size.width - 7.dp.toPx(), baseline, paint)
                if (number in breakpoints) {
                    val side = 12.dp.toPx()
                    translate(top = baseline - 10.dp.toPx()) {
                        with(breakpoint) { draw(Size(side, side), colorFilter = breakpointTint) }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            val pad = 12.dp
            val focus = state.value.selection.end
            LaunchedEffect(layout, focus, maxWidth, maxHeight) {
                val metrics = layout ?: return@LaunchedEffect
                val hit = metrics.getCursorRect(focus.coerceIn(0, metrics.layoutInput.text.length))
                with(pixels) {
                    val y = when {
                        hit.top < vertical.value -> hit.top
                        hit.bottom + 2 * pad.toPx() > vertical.value + viewportHeight.toPx() -> hit.bottom + 2 * pad.toPx() - viewportHeight.toPx()
                        else -> vertical.value.toFloat()
                    }
                    val x = when {
                        hit.left < horizontal.value -> hit.left
                        hit.right + 2 * pad.toPx() > horizontal.value + viewportWidth.toPx() -> hit.right + 2 * pad.toPx() - viewportWidth.toPx()
                        else -> horizontal.value.toFloat()
                    }
                    vertical.scrollTo(y.roundToInt().coerceAtLeast(0))
                    horizontal.scrollTo(x.roundToInt().coerceAtLeast(0))
                }
            }
            BasicTextField(
                value = state.value,
                onValueChange = state::input,
                modifier = Modifier.fillMaxSize().verticalScroll(vertical)
                    .horizontalScroll(horizontal).widthIn(min = viewportWidth)
                    .heightIn(min = viewportHeight).padding(pad)
                    .semantics { contentDescription = uiString(R.string.source_code) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp,
                    color = colors.onSurface, textDirection = TextDirection.Ltr),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = syntax,
                onTextLayout = { layout = it },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
        }
    }
}
