package dev.luna.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF0E1664),
    secondary = Color(0xFF5B5F78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E4F5),
    onSecondaryContainer = Color(0xFF181B2C),
    background = DayBackground,
    onBackground = DayInk,
    surface = DayBackground,
    onSurface = DayInk,
    surfaceVariant = DaySurface,
    onSurfaceVariant = DayInkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DaySurfaceLow,
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerHighest = Color(0xFFD8DEEB),
    outline = Color(0xFF74777F),
    outlineVariant = DayLine,
    error = Alert,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF1B2599),
    primaryContainer = Color(0xFF323FB6),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF33353E),
    outline = Color(0xFF8E909A),
    outlineVariant = NightLine,
    error = AlertNight,
    onError = Color(0xFF690005),
)

/** Luna's look. Follows the phone's light or dark setting; no dynamic colour, so it always looks like Luna. */
@Composable
fun LunaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = Shapes) {
        // Icons and text with no colour of their own use the page's ink, never black on a dark page
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
