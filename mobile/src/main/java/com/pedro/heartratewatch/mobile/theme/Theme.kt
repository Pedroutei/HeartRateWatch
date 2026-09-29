package com.pedro.heartratewatch.mobile.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Pip-Boy inspired: green phosphor CRT on near-black, monospace text. Red is kept for error/
// danger since it reads universally even against an otherwise monochrome-green scheme -- same
// reasoning as the watch tile's permanent Start/Stop bar colors.
private val PipBoyGreen = Color(0xFF4FFF8A)
private val PipBoyGreenDim = Color(0xFF2F8F57)
private val PipBoyBackground = Color(0xFF03170A)
private val PipBoySurface = Color(0xFF0A2412)
private val PipBoyRed = Color(0xFFFF6B5F)

private fun Typography.monospaced(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = FontFamily.Monospace),
    displayMedium = displayMedium.copy(fontFamily = FontFamily.Monospace),
    displaySmall = displaySmall.copy(fontFamily = FontFamily.Monospace),
    headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Monospace),
    headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Monospace),
    headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Monospace),
    titleLarge = titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = titleMedium.copy(fontFamily = FontFamily.Monospace),
    titleSmall = titleSmall.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace)
)

@Composable
fun PipBoyTheme(content: @Composable () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = PipBoyGreen,
        onPrimary = PipBoyBackground,
        secondary = PipBoyGreenDim,
        onSecondary = PipBoyBackground,
        tertiary = PipBoyGreenDim,
        onTertiary = PipBoyBackground,
        background = PipBoyBackground,
        onBackground = PipBoyGreen,
        surface = PipBoySurface,
        onSurface = PipBoyGreen,
        surfaceVariant = PipBoySurface,
        onSurfaceVariant = PipBoyGreenDim,
        error = PipBoyRed,
        onError = PipBoyBackground
    )
    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography.monospaced()) {
        Surface(color = colorScheme.background, content = content)
    }
}
