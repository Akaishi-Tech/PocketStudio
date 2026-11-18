package com.hcgstudio.pocketstudio

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import java.io.File
import kotlin.math.abs

internal class InstalledFont(
    val name: String,
    val isMonospace: Boolean,
    private val createTypeface: () -> Typeface?
) {
    fun loadTypeface(): Typeface? = try {
        createTypeface()
    } catch (_: RuntimeException) {
        null
    }
}

// Rank by upright style, distance from regular weight, then distance from normal width.
private fun regularRank(weight: Int, italic: Boolean, widthClass: Int) =
    (if (italic) 10000 else 0) + abs(weight - 400) * 10 + abs(widthClass - 5)

@RequiresApi(29)
private fun registeredFonts(): List<InstalledFont> {
    val families = sortedMapOf<String, MutableList<Pair<Font, OpenTypeFace>>>(String.CASE_INSENSITIVE_ORDER)
    for (font in SystemFonts.getAvailableFonts()) {
        val face = try {
            readFontFace(font.buffer, font.ttcIndex, font.file?.nameWithoutExtension)
        } catch (_: RuntimeException) {
            null
        } ?: continue
        families.getOrPut(face.name) { mutableListOf() }.add(font to face)
    }
    return families.map { (name, faces) ->
        val sorted = faces.sortedWith(compareBy(
            { (font, info) -> regularRank(font.style.weight, font.style.slant != FontStyle.FONT_SLANT_UPRIGHT, info.widthClass) },
            { it.first.file?.path }, { it.first.ttcIndex }
        ))
        // The Android family builder accepts exactly one face for each weight and slant.
        val styles = sorted.map { it.first }.distinctBy { it.style.weight to it.style.slant }
        InstalledFont(name, sorted.first().second.isMonospace) {
            val builder = FontFamily.Builder(styles.first())
            for (index in 1 until styles.size) builder.addFont(styles[index])
            val variable = if (Build.VERSION.SDK_INT >= 35) builder.buildVariableFamily() else null
            Typeface.CustomFallbackBuilder(variable ?: builder.build()).build()
        }
    }
}

private fun legacyFontFiles(): List<File> {
    val directories = listOf(File(Environment.getRootDirectory(), "fonts"), File("/product/fonts"),
        File("/vendor/fonts"), File("/odm/fonts"), File("/oem/fonts"), File("/system_ext/fonts"))
    val extensions = setOf("ttf", "otf", "ttc", "otc")
    return directories.flatMap { directory ->
        try {
            directory.listFiles { file -> file.extension.lowercase() in extensions }?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

@WorkerThread
internal fun installedFonts(): List<InstalledFont> {
    if (Build.VERSION.SDK_INT >= 29) {
        val registered = try {
            registeredFonts()
        } catch (_: RuntimeException) {
            emptyList()
        }
        if (registered.isNotEmpty()) return registered
    }
    val families = sortedMapOf<String, Pair<Int, InstalledFont>>(String.CASE_INSENSITIVE_ORDER)
    for (file in legacyFontFiles().sorted()) {
        for (face in readFontFaces(file)) {
            val rank = regularRank(face.weight, face.italic, face.widthClass)
            if (rank >= (families[face.name]?.first ?: Int.MAX_VALUE)) continue
            val font = InstalledFont(face.name, face.isMonospace) {
                Typeface.Builder(file).setTtcIndex(face.index).build()
            }
            families[face.name] = rank to font
        }
    }
    return families.values.map { it.second }
}
