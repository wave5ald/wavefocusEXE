package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = NightSecondary,
    onSecondary = Color(0xFF14103A),
    secondaryContainer = Color(0xFF2A2A66),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = NightTertiary,
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF090D16),
    surfaceContainerLow = Color(0xFF0F1524),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceVariant,
    surfaceContainerHighest = Color(0xFF27324A),
    outline = NightOutline,
    outlineVariant = Color(0xFF243044),
    error = Color(0xFFF43F5E),
    onError = Color(0xFF2A0710),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFECDD3),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF0B0F19)
)

private val LightColorScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = DayOnPrimary,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = DaySecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = DayTertiary,
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFDFF),
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DaySurfaceVariant,
    surfaceContainerHighest = Color(0xFFE7EDF5),
    outline = DayOutline,
    outlineVariant = Color(0xFFE9EEF5),
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF8FAFC)
)

@Composable
fun MyApplicationTheme(
    themeMode: String = "system",
    hueShift: Float = 0f,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    // The settings slider rotates every accent colour of the palette.
    val colorScheme = (if (isDark) DarkColorScheme else LightColorScheme).shiftHue(hueShift)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
