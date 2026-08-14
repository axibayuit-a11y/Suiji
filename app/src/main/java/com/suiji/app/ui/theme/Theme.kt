package com.suiji.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.suiji.app.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF555555),
    onTertiary = Color.White,
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF606060),
    outline = Color(0xFFB8B8B8),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFF222222),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFD7D7D7),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF252525),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF111111),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF202020),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF292929),
    error = Color(0xFFE5E5E5),
    onError = Color(0xFF111111)
)

@Composable
fun SuijiTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        typography = SuijiTypography,
        content = content
    )
}
