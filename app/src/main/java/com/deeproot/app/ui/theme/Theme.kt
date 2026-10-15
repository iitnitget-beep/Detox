package com.deeproot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    secondary = Green600,
    onSecondary = Color.White,
    secondaryContainer = Green100,
    onSecondaryContainer = Green950,
    tertiary = Indigo800,
    onTertiary = Color.White,
    error = Red600,
    onError = Color.White,
    errorContainer = Red100,
    onErrorContainer = Red900,
    background = LightBackground,
    onBackground = LightText,
    surface = LightBackground,
    onSurface = LightText,
    surfaceVariant = LightBorder,
    onSurfaceVariant = LightMuted,
    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightCard,
    surfaceContainer = Color(0xFFF1F2F7),
    surfaceContainerHigh = Color(0xFFEBEDF3),
    surfaceContainerHighest = LightBorder,
    outline = Color(0xFF9CA3AF),
    outlineVariant = LightBorder,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Indigo400,
    onPrimary = Indigo900,
    primaryContainer = Indigo800,
    onPrimaryContainer = Indigo100,
    secondary = Green400,
    onSecondary = Green950,
    secondaryContainer = Green900,
    onSecondaryContainer = Green100,
    tertiary = Indigo100,
    onTertiary = Indigo900,
    error = Red400,
    onError = Red950,
    errorContainer = Red900,
    onErrorContainer = Red100,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkBackground,
    onSurface = DarkText,
    surfaceVariant = DarkBorder,
    onSurfaceVariant = DarkMuted,
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = DarkCard,
    surfaceContainer = Color(0xFF1C2030),
    surfaceContainerHigh = Color(0xFF232838),
    surfaceContainerHighest = DarkBorder,
    outline = Color(0xFF4B5263),
    outlineVariant = DarkBorder,
)

/** Extra semantic colors that Material 3 does not provide. */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val backlog: Color,
    val isDark: Boolean,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = Green600,
        onSuccessContainer = Green950,
        successContainer = Green100,
        backlog = Red600,
        isDark = false,
    )
}

@Composable
fun LectureTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) {
        ExtendedColors(Green400, Green100, Green900, Red400, isDark = true)
    } else {
        ExtendedColors(Green600, Green950, Green100, Red600, isDark = false)
    }
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}

object AppTheme {
    val extended: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
