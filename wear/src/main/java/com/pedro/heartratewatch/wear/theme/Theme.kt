package com.pedro.heartratewatch.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Surface

// Pip-Boy inspired: green phosphor CRT on near-black. Red is kept for Stop/danger since it reads
// universally even against an otherwise monochrome-green scheme -- same reasoning as the tile's
// permanent Start/Stop bar colors (see HeartRateTileService).
private val PipBoyGreen = Color(0xFF4FFF8A)
private val PipBoyGreenDim = Color(0xFF2F8F57)
private val PipBoyBackground = Color(0xFF03170A)
private val PipBoyRed = Color(0xFFFF6B5F)

/**
 * NOTE FOR PEDRO: like ExerciseSessionService's Health Services calls, Wear Compose Material3's
 * exact ColorScheme field names have shifted across releases -- if this doesn't compile,
 * Alt+Enter is again the fastest way to the current names.
 */
@Composable
fun PulseGuardTheme(content: @Composable () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = PipBoyGreen,
        onPrimary = PipBoyBackground,
        secondary = PipBoyGreenDim,
        onSecondary = PipBoyBackground,
        background = PipBoyBackground,
        onBackground = PipBoyGreen,
        surfaceContainer = PipBoyBackground,
        onSurface = PipBoyGreen,
        onSurfaceVariant = PipBoyGreenDim,
        error = PipBoyRed,
        onError = PipBoyBackground
    )
    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = colorScheme.background, content = content)
    }
}
