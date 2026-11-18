package com.hcgstudio.pocketstudio

import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val markdownParser = Parser.builder().extensions(listOf(
    TablesExtension.builder().maxCells(4096).build(), StrikethroughExtension.create()
)).build()

@Composable
internal fun CodexMarkdown(text: String, modifier: Modifier = Modifier) {
    val document by produceState<Node?>(null, text) {
        value = withContext(Dispatchers.Default) {
            try { markdownParser.parse(text) } catch (_: RuntimeException) { null }
        }
    }
    SelectionContainer(modifier) {
        val node = document
        if (node == null) Text(text, style = MaterialTheme.typography.bodyMedium)
        else MarkdownBlocks(node)
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

@Composable
private fun MarkdownBlocks(container: Node, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp)) {
        container.children().forEach { node ->
            when (node) {
                is Paragraph -> InlineText(node)
                is Heading -> {
                    val typography = MaterialTheme.typography
                    val style = when (node.level) {
                        1 -> typography.headlineSmall
                        2 -> typography.titleLarge
                        3 -> typography.titleMedium
                        else -> typography.titleSmall
                    }
                    InlineText(node, Modifier.semantics { heading() }, style.copy(fontWeight = FontWeight.SemiBold))
                }
                is ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(if (node.isTight) 3.dp else 8.dp)) {
                    val first = (node as? OrderedList)?.markerStartNumber ?: 1
                    node.children().forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            val marker = if (node is OrderedList) "${first + index}${node.markerDelimiter ?: "."}" else "•"
                            Text(marker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f, fill = false)) { MarkdownBlocks(item, compact = node.isTight) }
                        }
                    }
                }
                is FencedCodeBlock -> CodeBlock(node.literal, node.info.orEmpty().substringBefore(' '))
                is IndentedCodeBlock -> CodeBlock(node.literal, "")
                is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Box(Modifier.weight(1f, fill = false)) { MarkdownBlocks(node) }
                }
                is TableBlock -> MarkdownTable(node)
                is ThematicBreak -> HorizontalDivider()
                is HtmlBlock -> Text(node.literal.trimEnd(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String) {
    val context = LocalContext.current
    val clipboardLabel = androidx.compose.ui.res.stringResource(R.string.code)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            DisableSelection {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(language, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, code.removeSuffix("\n")))
                    }) { Text(stringResource(R.string.copy_code), style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(code.removeSuffix("\n"), Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                softWrap = false, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = remember(table) { table.children().flatMap { it.children() }.map { it.children().filterIsInstance<TableCell>().toList() }.toList() }
    val columns = rows.maxOfOrNull { it.size } ?: return
    if (columns == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth / columns).coerceIn(104.dp, 360.dp)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEach { cells ->
                val header = cells.firstOrNull()?.isHeader == true
                Row(Modifier.background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    cells.forEach { cell ->
                        val style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            })
                        InlineText(cell, Modifier.width(width).padding(horizontal = 8.dp, vertical = 7.dp), style)
                    }
                }
                HorizontalDivider(Modifier.width(width * columns))
            }
        }
    }
}

@Composable
private fun InlineText(node: Node, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val inline = remember(node, colors, context) {
        buildAnnotatedString { inlineNodes(node, colors, context) }
    }
    Text(inline, modifier, style = style)
}

private fun AnnotatedString.Builder.inlineNodes(parent: Node, colors: ColorScheme, context: Context) {
    parent.children().forEach { node ->
        when (node) {
            is MarkdownText -> append(node.literal)
            is HardLineBreak -> append('\n')
            is SoftLineBreak -> append(' ')
            is HtmlInline -> append(node.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh)) { append(node.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlineNodes(node, colors, context) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlineNodes(node, colors, context) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inlineNodes(node, colors, context) }
            is Link -> {
                val uri = Uri.parse(node.destination)
                if (uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    val link = LinkAnnotation.Url(node.destination, TextLinkStyles(
                        SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, uiString(R.string.no_browser_available_to_open_this_link), Toast.LENGTH_SHORT).show() }
                        catch (_: SecurityException) { Toast.makeText(context, uiString(R.string.unable_to_open_this_link), Toast.LENGTH_SHORT).show() }
                    }
                    withLink(link) { inlineNodes(node, colors, context) }
                } else inlineNodes(node, colors, context)
            }
            else -> inlineNodes(node, colors, context)
        }
    }
}
