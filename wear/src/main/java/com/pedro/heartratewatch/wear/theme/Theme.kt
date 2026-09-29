package com.pedro.heartratewatch.wear.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

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
        // Wear Compose Material3 1.6.2's own Surface composable is internal (not public), so
        // painting the background is done directly instead -- screens here don't all use a
        // Scaffold-equivalent that would otherwise paint it.
        Box(Modifier.fillMaxSize().background(colorScheme.background)) {
            content()
        }
    }
}
