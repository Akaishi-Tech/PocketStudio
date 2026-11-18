package com.hcgstudio.pocketstudio

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat

internal val LocalStudioSettings = compositionLocalOf { StudioSettings() }
internal val LocalStudioDarkTheme = compositionLocalOf { false }

private val lightColors = lightColorScheme(
    primary = Color(0xff25694e), onPrimary = Color.White,
    primaryContainer = Color(0xffbcebd0), onPrimaryContainer = Color(0xff0d452d),
    secondary = Color(0xff506459), onSecondary = Color.White,
    secondaryContainer = Color(0xffdbe8dd), onSecondaryContainer = Color(0xff1c3426),
    tertiary = Color(0xff805333), onTertiary = Color.White,
    tertiaryContainer = Color(0xffffdcc4), onTertiaryContainer = Color(0xff49290f),
    background = Color(0xfffbfcf8), onBackground = Color(0xff18211b),
    surface = Color(0xfffbfcf8), onSurface = Color(0xff18211b),
    surfaceVariant = Color(0xffe3e8e1), onSurfaceVariant = Color(0xff47534a),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff4f6f1),
    surfaceContainer = Color(0xffecf0ea), surfaceContainerHigh = Color(0xffe7ebe5),
    surfaceContainerHighest = Color(0xffe1e6df), outline = Color(0xff727d74),
    outlineVariant = Color(0xffc6cfc6), inverseSurface = Color(0xff2d332e),
    inverseOnSurface = Color(0xffeef3ec), inversePrimary = Color(0xff8ed5b7)
)

private val darkColors = darkColorScheme(
    primary = Color(0xff8ed5b7), onPrimary = Color(0xff003824),
    primaryContainer = Color(0xff15513c), onPrimaryContainer = Color(0xffa5f2cc),
    secondary = Color(0xffb3ccbf), onSecondary = Color(0xff20382b),
    secondaryContainer = Color(0xff30453a), onSecondaryContainer = Color(0xffd6ecdf),
    tertiary = Color(0xffefbb97), onTertiary = Color(0xff49290f),
    tertiaryContainer = Color(0xff634024), onTertiaryContainer = Color(0xffffdcc4),
    background = Color(0xff111512), onBackground = Color(0xffe2e8e0),
    surface = Color(0xff111512), onSurface = Color(0xffe2e8e0),
    surfaceVariant = Color(0xff3c463f), onSurfaceVariant = Color(0xffbec9be),
    surfaceContainerLowest = Color(0xff0b100c), surfaceContainerLow = Color(0xff181e1a),
    surfaceContainer = Color(0xff1e2421), surfaceContainerHigh = Color(0xff282e2a),
    surfaceContainerHighest = Color(0xff343b35), outline = Color(0xff89948b),
    outlineVariant = Color(0xff414b43), inverseSurface = Color(0xffe2e8e0),
    inverseOnSurface = Color(0xff2d332e), inversePrimary = Color(0xff25694e)
)

private val contrastColors = darkColors.copy(
    primary = Color(0xffa5ffbf), onPrimary = Color.Black,
    primaryContainer = Color(0xff144a25), onPrimaryContainer = Color.White,
    secondary = Color(0xffb4eaff), onSecondary = Color.Black,
    secondaryContainer = Color(0xff193c49), onSecondaryContainer = Color.White,
    tertiary = Color(0xffffdf9b), onTertiary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White, onSurfaceVariant = Color(0xffeef1ed),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xff101210),
    surfaceContainer = Color(0xff191b19), surfaceContainerHigh = Color(0xff222522),
    surfaceContainerHighest = Color(0xff2e312e), outline = Color.White,
    outlineVariant = Color(0xffbcc4bd), error = Color(0xffffbabb)
)

@Composable
internal fun StudioTheme(settings: StudioSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        StudioThemeMode.System -> isSystemInDarkTheme()
        StudioThemeMode.Light -> false
        StudioThemeMode.Dark, StudioThemeMode.HighContrast -> true
    }
    val colors = when {
        settings.theme == StudioThemeMode.HighContrast -> contrastColors
        dark -> darkColors
        else -> lightColors
    }
    val window = LocalActivity.current?.window
    val view = LocalView.current
    LaunchedEffect(window, view, dark) {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val typography = remember(settings.uiFont, settings.uiFontSize) {
        val scale = settings.uiFontSize / 14f
        fun TextStyle.configured() = copy(
            fontFamily = settings.uiFont.family,
            fontSize = fontSize * scale,
            lineHeight = lineHeight * scale
        )
        with(Typography()) {
            Typography(
                displayLarge = displayLarge.configured(), displayMedium = displayMedium.configured(), displaySmall = displaySmall.configured(),
                headlineLarge = headlineLarge.configured(), headlineMedium = headlineMedium.configured(), headlineSmall = headlineSmall.configured(),
                titleLarge = titleLarge.configured(), titleMedium = titleMedium.configured(), titleSmall = titleSmall.configured(),
                bodyLarge = bodyLarge.configured(), bodyMedium = bodyMedium.configured(), bodySmall = bodySmall.configured(),
                labelLarge = labelLarge.configured(), labelMedium = labelMedium.configured(), labelSmall = labelSmall.configured()
            )
        }
    }
    CompositionLocalProvider(LocalStudioSettings provides settings, LocalStudioDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
