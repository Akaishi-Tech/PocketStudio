package com.hcgstudio.pocketstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.ArrayDeque
import java.util.Locale

internal class StudioEditorState(
    initialText: String,
    val fileName: String,
    cursor: Int = 0,
    private val onTextChanged: (String) -> Unit = {}
) {
    var language: EditorLanguage? = null
    var linuxCompletion: LinuxCompletion? = null
    var value by mutableStateOf(TextFieldValue(initialText, TextRange(cursor.coerceIn(0, initialText.length))))
        private set
    val text get() = value.text
    val cursor get() = value.selection.end
    val start get() = value.selection.min
    val end get() = value.selection.max
    val selected get() = text.substring(start, end)
    val eol get() = if (text.contains("\r\n")) "\r\n" else "\n"
    val line get() = lineAt(cursor)
    val column get() = cursor - lineStart(cursor) + 1
    val isJava get() = fileName.endsWith(".java", true)
    val isXml get() = fileName.endsWith(".xml", true)
    val isKotlin get() = fileName.endsWith(".kt", true) || fileName.endsWith(".kts", true)
    var find by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var wholeWords by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private var lineStarts = offsets(initialText)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var undoSize = 0
    private var expansion = mutableListOf<TextRange>()

    private class Edit(val at: Int, val old: String, val new: String, val before: TextRange, val after: TextRange) {
        val size get() = old.length + new.length
    }

    /** Apply typing assistance only to a single committed character replacing the current selection. */
    fun type(next: TextFieldValue, settings: StudioSettings) {
        val insertedLength = next.text.length - (text.length - (end - start))
        val typedAtSelection = next.text != text && next.composition == null && value.composition == null &&
            next.selection.collapsed && next.selection.end == start + 1 && insertedLength == 1 &&
            next.text.regionMatches(0, text, 0, start) &&
            next.text.regionMatches(start + 1, text, end, text.length - end)
        if (!typedAtSelection) {
            input(next)
            return
        }
        val ch = next.text[start]
        when (ch) {
            '\n' -> newLine(false, settings = settings)
            '\t' -> indent(false, settings)
            else -> {
                val quote = ch == '\'' || ch == '"'
                val pairing = if (quote) settings.pairQuotes && !escapedAt(start) else settings.pairBrackets
                val following = text.getOrNull(end)
                val close = when (ch) {
                    '(' -> ')'
                    '{' -> '}'
                    '[' -> ']'
                    '\'', '"' -> ch
                    else -> null
                }
                when {
                    pairing && start == end && ch == following && (quote || ch in ")]}") -> select(cursor + 1)
                    pairing && close != null && (start != end || following == null || !Character.isJavaIdentifierPart(following)) -> {
                        val body = selected
                        replace(start, end, "$ch$body$close", TextRange(start + 1, start + 1 + body.length))
                    }
                    else -> input(next)
                }
            }
        }
    }

    private fun escapedAt(at: Int): Boolean {
        var pos = at
        while (pos > 0 && text[pos - 1] == '\\') pos--
        return (at - pos) % 2 != 0
    }

    fun input(next: TextFieldValue) {
        val before = value
        if (before == next) return
        expansion.clear()
        if (before.text != next.text) {
            var at = 0
            val old = before.text
            val new = next.text
            val shortest = minOf(old.length, new.length)
            while (at < shortest && old[at] == new[at]) at++
            var tail = 0
            while (tail < shortest - at && old[old.lastIndex - tail] == new[new.lastIndex - tail]) tail++
            val edit = Edit(at, old.substring(at, old.length - tail), new.substring(at, new.length - tail), before.selection, next.selection)
            undoStack.addLast(edit)
            undoSize += edit.size
            while (undoStack.size > 1 && (undoStack.size > 250 || undoSize > 2_000_000)) undoSize -= undoStack.removeFirst().size
            redoStack.clear()
            apply(next)
        } else value = next
    }

    private fun apply(next: TextFieldValue) {
        value = next
        lineStarts = offsets(next.text)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        onTextChanged(next.text)
    }

    fun undo() {
        val edit = undoStack.pollLast() ?: return
        redoStack.addLast(edit)
        undoSize -= edit.size
        expansion.clear()
        apply(TextFieldValue(text.replaceRange(edit.at, edit.at + edit.new.length, edit.old), edit.before))
    }

    fun redo() {
        val edit = redoStack.pollLast() ?: return
        undoStack.addLast(edit)
        undoSize += edit.size
        expansion.clear()
        apply(TextFieldValue(text.replaceRange(edit.at, edit.at + edit.old.length, edit.new), edit.after))
    }

    fun select(a: Int, b: Int = a) {
        expansion.clear()
        value = value.copy(selection = TextRange(a.coerceIn(0, text.length), b.coerceIn(0, text.length)), composition = null)
    }

    fun replace(a: Int, b: Int, insertion: String, selection: TextRange = TextRange(a + insertion.length)) {
        input(TextFieldValue(text.replaceRange(a, b, insertion), selection))
    }

    fun insert(insertion: String) = replace(start, end, insertion)
    val lineCount get() = lineStarts.size
    fun lineStartOffset(line: Int) = lineStarts[line - 1]

    fun lineAt(at: Int): Int {
        val index = lineStarts.binarySearch(at)
        return if (index >= 0) index + 1 else -index - 1
    }
    private fun lineStart(at: Int) = lineStartOffset(lineAt(at))
    private fun lineEnd(at: Int): Int {
        val next = lineStarts.getOrNull(lineAt(at)) ?: return text.length
        return if (next >= 2 && text[next - 2] == '\r') next - 2 else next - 1
    }
    fun go(line: Int, column: Int = 1) {
        val at = lineStarts[(line - 1).coerceIn(lineStarts.indices)]
        select(at + (column - 1).coerceIn(0, lineEnd(at) - at))
    }
    private val blockStart get() = lineStart(start)
    private val blockEnd get() = lineEnd(if (end > start && text[end - 1] == '\n') end - 1 else end)
    private fun nextLine(at: Int) = lineStarts.getOrNull(lineAt(at)) ?: text.length
    private fun wordBounds(): TextRange {
        var a = start
        var b = end
        while (a > 0 && Character.isJavaIdentifierPart(text[a - 1])) a--
        while (b < text.length && Character.isJavaIdentifierPart(text[b])) b++
        return TextRange(a, b)
    }

    fun duplicate() {
        if (start != end) {
            val copy = selected
            replace(end, end, copy, TextRange(end, end + copy.length))
        } else {
            val copy = text.substring(blockStart, blockEnd)
            replace(blockEnd, blockEnd, eol + copy, TextRange(cursor + eol.length + copy.length))
        }
    }

    fun linesText() = text.substring(blockStart, nextLine(blockEnd))

    fun deleteLines() {
        val next = nextLine(blockEnd)
        var from = blockStart
        if (next == blockEnd && from > 0) from = lineEnd(from - 1)
        replace(from, next, "")
    }

    fun moveLines(down: Boolean) {
        val from = blockStart
        val to = blockEnd
        val block = text.substring(from, to)
        if (down) {
            if (to == text.length) return
            val next = nextLine(to)
            val after = lineEnd(next)
            val other = text.substring(next, after)
            val sep = text.substring(to, next)
            val shift = other.length + sep.length
            val selection = TextRange((start + shift).coerceAtMost(after), (end + shift).coerceAtMost(after))
            replace(from, after, other + sep + block, selection)
        } else {
            if (from == 0) return
            val prev = lineStart(from - 1)
            val prevEnd = lineEnd(from - 1)
            val sep = text.substring(prevEnd, from)
            replace(prev, to, block + sep + text.substring(prev, prevEnd), TextRange(start - (from - prev), end - (from - prev)))
        }
    }

    private fun transformLines(transform: (String) -> String) {
        val from = blockStart
        val to = blockEnd
        val changed = text.substring(from, to).split(Regex("\r?\n")).joinToString(eol, transform = transform)
        val sel = if (start == end) TextRange((cursor + changed.length - (to - from)).coerceIn(from, from + changed.length))
                  else TextRange(from, from + changed.length)
        replace(from, to, changed, sel)
    }

    fun indent(outdent: Boolean, settings: StudioSettings) {
        val width = settings.indentSize
        val unit = if (settings.useTabs) "\t" else " ".repeat(width)
        if (!outdent && start == end) {
            if (settings.useTabs) insert(unit)
            else {
                var visualColumn = 0
                for (i in lineStart(cursor) until cursor) {
                    visualColumn += if (text[i] == '\t') width - visualColumn % width else 1
                }
                insert(" ".repeat(width - visualColumn % width))
            }
        } else transformLines { line ->
            when {
                !outdent -> unit + line
                line.startsWith('\t') -> line.drop(1)
                else -> line.drop(line.take(width).takeWhile { it == ' ' }.length)
            }
        }
    }

    fun commentLines() {
        if (isXml) { blockComment(); return }
        val marker = when (fileName.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "py", "sh", "properties", "toml", "yaml", "yml", "rb" -> "#"
            "sql", "lua" -> "--"
            else -> "//"
        }
        val lines = text.substring(blockStart, blockEnd).lines()
        val remove = lines.any { it.isNotBlank() } && lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(marker) }
        transformLines {
            val code = it.trimStart()
            val space = it.take(it.length - code.length)
            if (code.isEmpty()) it
            else if (remove) space + code.removePrefix(marker).removePrefix(" ")
            else space + marker + " " + code
        }
    }

    fun blockComment() {
        val a = if (start == end) blockStart else start
        val b = if (start == end) blockEnd else end
        val token = if (isXml) "<!--" to "-->" else "/*" to "*/"
        val body = text.substring(a, b)
        val result = if (body.startsWith(token.first) && body.endsWith(token.second) && body.length >= token.first.length + token.second.length)
            body.removePrefix(token.first).removeSuffix(token.second)
        else {
            require(!body.contains(if (isXml) "--" else "*/")) { uiString(R.string.the_selection_contains_a_comment_terminator) }
            token.first + body + token.second
        }
        replace(a, b, result, TextRange(a, a + result.length))
    }

    fun selectLine() = select(blockStart, nextLine(blockEnd))

    fun word(forward: Boolean, extend: Boolean = false, delete: Boolean = false) {
        if (delete && start != end) { insert(""); return }
        var at = cursor
        fun group(pos: Int): Int {
            val cp = text.codePointAt(pos)
            return when {
                Character.isWhitespace(cp) -> 0
                Character.isJavaIdentifierPart(cp) -> 1
                else -> 2
            }
        }
        if (forward && at < text.length) {
            val category = group(at)
            while (at < text.length && group(at) == category) at = text.offsetByCodePoints(at, 1)
        } else if (!forward && at > 0) {
            val category = group(text.offsetByCodePoints(at, -1))
            while (at > 0) {
                val prev = text.offsetByCodePoints(at, -1)
                if (group(prev) != category) break
                at = prev
            }
        }
        if (delete) replace(minOf(cursor, at), maxOf(cursor, at), "")
        else select(if (extend) value.selection.start else at, at)
    }

    fun surround(before: String, after: String) {
        val enclosed = before + selected + after
        replace(start, end, enclosed, TextRange(start + before.length, start + enclosed.length - after.length))
    }

    fun changeCase() {
        val r = if (start == end) wordBounds() else value.selection
        val s = text.substring(r.min, r.max)
        val changed = if (s == s.uppercase(Locale.ROOT)) s.lowercase(Locale.ROOT) else s.uppercase(Locale.ROOT)
        replace(r.min, r.max, changed, TextRange(r.min, r.min + changed.length))
    }

    fun newLine(below: Boolean, above: Boolean = false, settings: StudioSettings) {
        val at = if (above || below) cursor else start
        val a = lineStart(at)
        val b = lineEnd(at)
        val splitAt = if (above || below) b else at
        val prefix = text.substring(a, splitAt)
        val leading = if (settings.autoIndent) prefix.takeWhile { it == ' ' || it == '\t' } else ""
        if (above) {
            replace(a, a, leading + eol, TextRange(a + leading.length))
            return
        }
        val opening = prefix.trimEnd().lastOrNull()
        val extraLevel = settings.autoIndent && opening != null && opening in "{[("
        val indent = leading + if (!extraLevel) "" else if (settings.useTabs) "\t" else " ".repeat(settings.indentSize)
        if (below) replace(b, b, eol + indent)
        else {
            val closing = when (opening) { '{' -> '}'; '[' -> ']'; '(' -> ')'; else -> null }
            val tail = if (extraLevel && closing == text.getOrNull(end)) eol + leading else ""
            replace(start, end, eol + indent + tail, TextRange(start + eol.length + indent.length))
        }
    }

    fun joinLines() {
        val a = blockStart
        var b = blockEnd
        if (lineAt(a) == lineAt(b) && b < text.length) b = lineEnd(nextLine(b))
        val joined = text.substring(a, b).replace(Regex("[ \t]*\r?\n[ \t]*"), " ")
        replace(a, b, joined, TextRange((cursor.coerceIn(a, a + joined.length))))
    }

    fun expand(shrink: Boolean) {
        if (shrink) {
            if (expansion.isNotEmpty()) value = value.copy(selection = expansion.removeAt(expansion.lastIndex), composition = null)
            return
        }
        val options = listOf(wordBounds(), TextRange(blockStart, blockEnd), TextRange(0, text.length))
        val next = options.firstOrNull { it.min <= start && it.max >= end && it.length > end - start } ?: return
        expansion.add(value.selection)
        value = value.copy(selection = next, composition = null)
    }

    fun searchPattern(): Regex? {
        if (find.isEmpty()) return null
        var p = Regex.escape(find)
        if (wholeWords) p = "(?<![\\p{L}\\p{N}_$])" + p + "(?![\\p{L}\\p{N}_$])"
        return if (matchCase) Regex(p) else Regex(p, RegexOption.IGNORE_CASE)
    }

    fun match(forward: Boolean, includeCurrent: Boolean = false): Boolean {
        val p = searchPattern() ?: return false
        val hit = if (forward) p.find(text, if (includeCurrent) start else end) ?: p.find(text)
            else p.findAll(text).takeWhile { it.range.last < start }.lastOrNull() ?: p.findAll(text).lastOrNull()
        if (hit == null) return false
        select(hit.range.first, hit.range.last + 1)
        return true
    }

    fun replaceMatch(): Boolean {
        val pattern = searchPattern() ?: return false
        if (pattern.find(text, start)?.range != start until end && !match(true, true)) return false
        insert(replacement)
        match(true)
        return true
    }

    fun replaceAll(): Int {
        val p = searchPattern() ?: return 0
        var count = 0
        val result = p.replace(text) { count++; replacement }
        if (count > 0) replace(0, text.length, result, TextRange(cursor.coerceAtMost(result.length)))
        return count
    }

    fun complete(prefix: TextRange, token: String) = replace(prefix.min, prefix.max, token)
    fun prefix(): TextRange { val word = wordBounds(); return TextRange(word.min, cursor) }

    companion object {
        private fun offsets(s: String): IntArray {
            val starts = IntArray(1 + s.count { it == '\n' })
            var line = 1
            s.forEachIndexed { i, c -> if (c == '\n') starts[line++] = i + 1 }
            return starts
        }
    }
}
