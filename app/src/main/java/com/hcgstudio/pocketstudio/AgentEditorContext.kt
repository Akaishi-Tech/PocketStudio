package com.hcgstudio.pocketstudio

import org.json.JSONObject

/** Read on the editor's main thread so all positions refer to the same live buffer. */
internal fun editorContext(active: OpenFile?): JSONObject {
    if (active == null) return json("active" to false)
    val editor = active.editor
    val text = editor.text
    fun position(offset: Int): JSONObject {
        val line = editor.lineAt(offset)
        return json("line" to line, "column" to offset - editor.lineStartOffset(line) + 1)
    }

    // A single minified line or a select-all should never send the entire document.
    fun splitsSurrogatePair(offset: Int) = offset > 0 && offset < text.length &&
        text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
    val selectionStart = editor.start
    val selectionEnd = editor.end
    var previewEnd = minOf(selectionEnd, selectionStart + 4096)
    if (previewEnd < selectionEnd && splitsSurrogatePair(previewEnd)) previewEnd--

    val cursor = editor.cursor
    val cursorLine = editor.lineAt(cursor)
    val firstLine = (cursorLine - 5).coerceAtLeast(1)
    val lastLine = (cursorLine + 5).coerceAtMost(editor.lineCount)
    val afterLast = if (lastLine == editor.lineCount) text.length else editor.lineStartOffset(lastLine + 1)
    var from = maxOf(editor.lineStartOffset(firstLine), cursor - 2048)
    var until = minOf(afterLast, cursor + 2048)
    if (splitsSurrogatePair(from)) from--
    if (splitsSurrogatePair(until)) until++

    return json("active" to true, "path" to active.node.path, "dirty" to active.dirty,
        "cursor" to position(cursor), "lineCount" to editor.lineCount,
        "selection" to json("start" to position(selectionStart), "endExclusive" to position(selectionEnd),
            "text" to text.substring(selectionStart, previewEnd), "truncated" to (previewEnd < selectionEnd)),
        "context" to json("start" to position(from), "endExclusive" to position(until),
            "text" to text.substring(from, until)))
}
