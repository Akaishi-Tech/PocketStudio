package com.hcgstudio.pocketstudio

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed class StudioFont(val id: String) {
    class Preset(id: String, @get:StringRes val label: Int, val family: FontFamily) : StudioFont(id)
    data class Installed(val name: String) : StudioFont(INSTALLED_PREFIX + name)

    companion object {
        private const val INSTALLED_PREFIX = "installed:"
        val System = Preset("System", R.string.settings_system_font, FontFamily.Default)
        val JetBrainsMono = Preset("JetBrainsMono", R.string.settings_jetbrains_mono, FontFamily(Font(R.font.jetbrains_mono_regular)))
        val Inter = Preset("Inter", R.string.settings_inter, FontFamily(Font(R.font.siwc_inter_medium)))
        val Serif = Preset("Serif", R.string.settings_serif, FontFamily.Serif)
        val Monospace = Preset("Monospace", R.string.settings_monospace, FontFamily.Monospace)
        val builtIns = listOf(System, JetBrainsMono, Inter, Serif, Monospace)

        fun read(value: Any?, fallback: StudioFont): StudioFont {
            if (value !is String) return fallback
            if (value.startsWith(INSTALLED_PREFIX)) {
                val name = value.removePrefix(INSTALLED_PREFIX)
                return if (name.isBlank()) fallback else Installed(name)
            }
            return builtIns.firstOrNull { it.id == value } ?: fallback
        }
    }
}

internal data class StudioFontOption(val font: StudioFont, val isMonospace: Boolean)
internal data class ResolvedStudioFont(val font: StudioFont, val family: FontFamily)

internal val builtInFontOptions = StudioFont.builtIns.map {
    StudioFontOption(it, it == StudioFont.Monospace || it == StudioFont.JetBrainsMono)
}

private val installedByName by lazy { installedFonts().associateBy { it.name } }

internal suspend fun availableStudioFonts(): List<StudioFontOption> = withContext(Dispatchers.IO) {
    builtInFontOptions + installedByName.values.map { StudioFontOption(StudioFont.Installed(it.name), it.isMonospace) }
}

@Composable
internal fun StudioFont.resolve(fallback: StudioFont.Preset): ResolvedStudioFont = when (this) {
    is StudioFont.Preset -> ResolvedStudioFont(this, family)
    is StudioFont.Installed -> key(name) {
        val selection = this
        produceState(ResolvedStudioFont(selection, fallback.family)) {
            val family = withContext(Dispatchers.IO) {
                installedByName[name]?.loadTypeface()?.asFontFamily()
            }
            value = if (family != null) ResolvedStudioFont(selection, family) else ResolvedStudioFont(fallback, fallback.family)
        }.value
    }
}

// A Compose family backed by a single Typeface would use the same face for bold and italic.
private fun Typeface.asFontFamily() = FontFamily(buildList<Font> {
    for (weight in 100..900 step 100) {
        add(InstalledFontStyle(this@asFontFamily, FontWeight(weight), FontStyle.Normal))
        add(InstalledFontStyle(this@asFontFamily, FontWeight(weight), FontStyle.Italic))
    }
})

private class InstalledFontStyle(
    val base: Typeface,
    override val weight: FontWeight,
    override val style: FontStyle
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    companion object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
            val face = font as InstalledFontStyle
            val italic = face.style == FontStyle.Italic
            if (Build.VERSION.SDK_INT >= 28) return Typeface.create(face.base, face.weight.weight, italic)
            val bold = face.weight >= FontWeight.SemiBold
            val legacyStyle = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            return Typeface.create(face.base, legacyStyle)
        }

        override suspend fun awaitLoad(context: Context, font: AndroidFont) = loadBlocking(context, font)
    }
}
