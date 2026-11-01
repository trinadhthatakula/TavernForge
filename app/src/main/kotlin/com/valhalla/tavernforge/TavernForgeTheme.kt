package com.valhalla.tavernforge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF245D55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E8DB),
    onPrimaryContainer = Color(0xFF12382F),
    secondary = Color(0xFF765A2A),
    secondaryContainer = Color(0xFFF4DDAF),
    onSecondaryContainer = Color(0xFF382609),
    background = Color(0xFFF6F0E5),
    onBackground = Color(0xFF242D2B),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF242D2B),
    surfaceVariant = Color(0xFFE8E7DB),
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFFAF5EA),
    surfaceContainer = Color(0xFFF3EFE3),
    surfaceContainerHigh = Color(0xFFEBE8DC),
    surfaceContainerHighest = Color(0xFFE3E3D7),
    surfaceTint = Color(0xFF245D55),
    outlineVariant = Color(0xFFCACEC1),
    onSurfaceVariant = Color(0xFF48534E),
    outline = Color(0xFF737D74),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0D7C3),
    onPrimary = Color(0xFF143A2D),
    primaryContainer = Color(0xFF285343),
    onPrimaryContainer = Color(0xFFD6F2DF),
    secondary = Color(0xFFE7C489),
    secondaryContainer = Color(0xFF59431F),
    onSecondaryContainer = Color(0xFFF7DEAD),
    background = Color(0xFF111D20),
    onBackground = Color(0xFFECEEE6),
    surface = Color(0xFF19272A),
    onSurface = Color(0xFFECEEE6),
    surfaceVariant = Color(0xFF33413B),
    surfaceContainerLowest = Color(0xFF0D171A),
    surfaceContainerLow = Color(0xFF172427),
    surfaceContainer = Color(0xFF1E2C2F),
    surfaceContainerHigh = Color(0xFF273639),
    surfaceContainerHighest = Color(0xFF324143),
    surfaceTint = Color(0xFFB0D7C3),
    outlineVariant = Color(0xFF43534D),
    onSurfaceVariant = Color(0xFFC0CBBF),
    outline = Color(0xFF8B978D),
)

@Composable
fun TavernForgeTheme(content: @Composable () -> Unit) {
    val typography = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = typography.copy(
            headlineLarge = typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
            headlineMedium = typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
        ),
        shapes = Shapes(large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
        content = content,
    )
}
