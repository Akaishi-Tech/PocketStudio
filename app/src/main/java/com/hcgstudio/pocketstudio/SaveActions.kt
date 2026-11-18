package com.hcgstudio.pocketstudio

/** Keep the file's line endings, including mixed LF and CRLF in a single file. */
internal fun prepareForSave(text: String, settings: StudioSettings): String {
    if (text.isEmpty() || (!settings.trimTrailingSpaces && !settings.ensureFinalNewline)) return text
    val cleaned = if (!settings.trimTrailingSpaces) text else buildString(text.length) {
        var start = 0
        while (start < text.length) {
            val lf = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val end = if (lf < text.length && lf > start && text[lf - 1] == '\r') lf - 1 else lf
            var trimmed = end
            while (trimmed > start && (text[trimmed - 1] == ' ' || text[trimmed - 1] == '\t')) trimmed--
            append(text, start, trimmed)
            if (lf < text.length) append(text, end, lf + 1)
            start = lf + 1
        }
    }
    if (!settings.ensureFinalNewline || cleaned.isEmpty() || cleaned.endsWith('\n')) return cleaned
    val eol = if (text.contains("\r\n")) "\r\n" else "\n"
    return cleaned + eol
}

/** Map a caret or selection endpoint onto the same source line after save actions. */
internal fun savedOffset(original: String, cleaned: String, offset: Int): Int {
    var oldStart = 0
    var newStart = 0
    while (true) {
        val oldBreak = original.indexOf('\n', oldStart)
        val newBreak = cleaned.indexOf('\n', newStart)
        if (oldBreak < 0 || offset <= oldBreak) {
            val end = when {
                newBreak < 0 -> cleaned.length
                newBreak > newStart && cleaned[newBreak - 1] == '\r' -> newBreak - 1
                else -> newBreak
            }
            return (newStart + offset - oldStart).coerceAtMost(end)
        }
        oldStart = oldBreak + 1
        newStart = newBreak + 1
    }
}
