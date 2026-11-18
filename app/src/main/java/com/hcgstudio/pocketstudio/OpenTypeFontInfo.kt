package com.hcgstudio.pocketstudio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal data class OpenTypeFace(
    val index: Int,
    val name: String,
    val weight: Int,
    val widthClass: Int,
    val italic: Boolean,
    val isMonospace: Boolean
)

private const val TTC = 0x74746366 // ttcf
private const val NAME = 0x6e616d65 // name
private const val OS2 = 0x4f532f32 // OS/2
private const val POST = 0x706f7374 // post

internal fun readFontFace(buffer: ByteBuffer, index: Int, fallbackName: String?): OpenTypeFace? {
    val source = buffer.duplicate().apply { position(0) }
    val data = OpenTypeData(source.limit().toLong()) { offset, size ->
        source.duplicate().apply {
            position(offset.toInt())
            limit(offset.toInt() + size)
        }.slice()
    }
    return data.face(index, fallbackName)
}

internal fun readFontFaces(file: File): List<OpenTypeFace> = try {
    RandomAccessFile(file, "r").use { input ->
        val data = OpenTypeData(input.length()) { offset, size ->
            input.seek(offset)
            ByteBuffer.wrap(ByteArray(size).also { input.readFully(it) })
        }
        (0 until data.faceCount).mapNotNull { index -> data.face(index, file.nameWithoutExtension) }
    }
} catch (_: IOException) {
    emptyList()
} catch (_: SecurityException) {
    emptyList()
}

private fun ByteBuffer.u16(offset: Int) = getShort(offset).toInt() and 0xffff
private fun ByteBuffer.u32(offset: Int) = getInt(offset).toUInt().toLong()

private class OpenTypeData(val size: Long, val bytes: (Long, Int) -> ByteBuffer) {
    private val header = if (size >= 12) bytes(0, 12) else null
    private val collection = header?.getInt(0) == TTC
    val faceCount = when {
        header == null -> 0
        !collection -> 1
        else -> header.getInt(8).takeIf { it in 1..65535 && 12L + it * 4L <= size } ?: 0
    }

    fun face(index: Int, fallbackName: String?): OpenTypeFace? {
        if (index !in 0 until faceCount) return null
        val offset = if (collection) bytes(12L + index * 4L, 4).u32(0) else 0L
        if (offset + 12 > size) return null
        val sfnt = bytes(offset, 12)
        // TrueType, CFF OpenType, and Apple TrueType signatures.
        if (sfnt.getInt(0) !in setOf(0x00010000, 0x4f54544f, 0x74727565)) return null
        val count = sfnt.u16(4)
        if (offset + 12 + count * 16L > size) return null
        val directory = bytes(offset + 12, count * 16)

        fun table(tag: Int, maxBytes: Int): ByteBuffer? {
            for (entry in 0 until count) {
                val record = entry * 16
                if (directory.getInt(record) != tag) continue
                // Table offsets are absolute, including in collections.
                val start = directory.u32(record + 8)
                val length = directory.u32(record + 12)
                if (start + length > size) return null
                return bytes(start, minOf(length, maxBytes.toLong()).toInt())
            }
            return null
        }

        // String storage offset, relative offset and length are each unsigned 16-bit fields.
        val name = table(NAME, 3 * 65536)?.familyName()
            ?: fallbackName?.let { if (faceCount == 1) it else "$it (${index + 1})" } ?: return null
        val os2 = table(OS2, 64)
        val weight = os2?.takeIf { it.limit() >= 6 }?.u16(4)?.takeIf { it in 1..1000 } ?: 400
        val widthClass = os2?.takeIf { it.limit() >= 8 }?.u16(6)?.takeIf { it in 1..9 } ?: 5
        val italic = os2 != null && os2.limit() >= 64 && (os2.u16(62) and 0x201) != 0
        val post = table(POST, 16)
        val fixedPitch = post != null && post.limit() >= 16 && post.getInt(12) != 0
        // PANOSE Latin Text (2) with Monospaced proportion (9).
        val fixedProportion = os2 != null && os2.limit() >= 36 &&
            os2.get(32).toInt() == 2 && os2.get(35).toInt() == 9
        return OpenTypeFace(index, name, weight, widthClass, italic, fixedPitch || fixedProportion)
    }
}

private fun ByteBuffer.familyName(): String? {
    if (limit() < 6) return null
    val count = minOf(u16(2), (limit() - 6) / 12)
    val storage = u16(4)
    var name: String? = null
    var bestScore = -1
    for (entry in 0 until count) {
        val record = 6 + entry * 12
        val platform = u16(record)
        val encoding = u16(record + 2)
        val language = u16(record + 4)
        val id = u16(record + 6)
        if (id != 16 && id != 1) continue // Typographic family, then legacy family
        if (platform != 0 && !(platform == 3 && encoding in setOf(0, 1, 10))) continue
        // A consistent language makes the saved family identifiable after a locale change.
        val score = (if (id == 16) 4 else 0) + when {
            platform == 3 && language == 0x0409 -> 3
            platform == 0 -> 2
            else -> 1
        }
        if (score <= bestScore) continue
        val size = u16(record + 8)
        val start = storage + u16(record + 10)
        if (start + size > limit() || size % 2 != 0) continue
        val text = duplicate().apply { position(start); limit(start + size) }
        val candidate = Charsets.UTF_16BE.decode(text).toString().trim()
        if (candidate.isBlank()) continue
        name = candidate
        bestScore = score
    }
    return name
}
