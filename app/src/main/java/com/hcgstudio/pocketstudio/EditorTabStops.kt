package com.hcgstudio.pocketstudio

import androidx.compose.ui.text.input.OffsetMapping

/** Expand tabs for layout while keeping every edit, selection and breakpoint in source coordinates. */
internal class EditorTabStops(val source: String, width: Int) : OffsetMapping {
    private val tabs = IntArray(source.count { it == '\t' })
    private val ends = IntArray(tabs.size)
    val display = if (tabs.isEmpty()) source else buildString(source.length) {
        var tab = 0
        var column = 0
        source.forEachIndexed { index, ch ->
            if (ch == '\t') {
                val spaces = width - column % width
                repeat(spaces) { append(' ') }
                column += spaces
                tabs[tab] = index
                ends[tab++] = length
            } else {
                append(ch)
                column = if (ch == '\n') 0 else column + 1
            }
        }
    }

    private fun extraBefore(index: Int): Int = if (index == 0) 0 else ends[index - 1] - tabs[index - 1] - 1

    override fun originalToTransformed(offset: Int): Int {
        val hit = tabs.binarySearch(offset)
        return offset + extraBefore(if (hit >= 0) hit else -hit - 1)
    }

    override fun transformedToOriginal(offset: Int): Int {
        val hit = ends.binarySearch(offset)
        if (hit >= 0) return tabs[hit] + 1
        val next = -hit - 1
        val original = offset - extraBefore(next)
        return if (next < tabs.size) minOf(original, tabs[next]) else original
    }
}
