package com.rishi.cascade.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF5B3DF5)
private val IndigoLight = Color(0xFF7C5CFF)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1B0066),
    secondary = Color(0xFF00A38B),
    secondaryContainer = Color(0xFFC9F5EA),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFFE8590C),
    background = Color(0xFFF7F5FB),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFEDEAF5),
    onSurfaceVariant = Color(0xFF474657),
    outline = Color(0xFFB9B5C9),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF1B0066),
    primaryContainer = Color(0xFF3A2A9E),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFF57D9C0),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFC9F5EA),
    tertiary = Color(0xFFFFB68A),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF1B1B23),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF2A2A35),
    onSurfaceVariant = Color(0xFFC8C4D6),
    outline = Color(0xFF5D5A6B),
    error = Color(0xFFFFB4AB)
)

/** Colours flows can be tagged with, in the order shown in the picker. */
val FlowColors = listOf(
    Color(0xFF5B3DF5), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
    Color(0xFFEF4444), Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF64748B)
)

fun flowColor(index: Int): Color = FlowColors[index.coerceIn(0, FlowColors.size - 1)]

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun CascadeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}

/** "1 step", "3 steps" - used in the flow subtitles. */
fun plural(count: Int, word: String): String =
    count.toString() + " " + word + (if (count == 1) "" else "s")
