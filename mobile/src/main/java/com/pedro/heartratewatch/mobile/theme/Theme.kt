package com.pedro.heartratewatch.mobile.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.ThemePreferenceRepository

// Pip-Boy inspired: green phosphor CRT, monospace text. Red is kept for error/danger since it
// reads universally even against an otherwise monochrome-green scheme -- same reasoning as the
// watch tile's permanent Start/Stop bar colors. Dark is literal black, not a dark-green tint --
// tested that tint in practice and it read as "light green on dark green" rather than the
// intended "light green on black".
private val PipBoyGreen = Color(0xFF4FFF8A)
private val PipBoyGreenDim = Color(0xFF2F8F57)
private val PipBoyBlack = Color(0xFF000000)
private val PipBoyBlackContainer = Color(0xFF061109)
private val PipBoyRed = Color(0xFFFF6B5F)

// Light variant: same green accent family, but on a plain light surface instead of Pip-Boy black
// -- this one's just for readability (bright rooms, outdoor glare), not a "light Pip-Boy" thing.
private val PipBoyGreenDark = Color(0xFF1B4D33)
private val PipBoyWhite = Color(0xFFFDFDFB)
private val PipBoyWhiteContainer = Color(0xFFE3F3E8)
private val PipBoyRedLight = Color(0xFFC62828)

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
    val context = LocalContext.current
    val useLightTheme by remember(context) { ThemePreferenceRepository(context).useLightThemeFlow }
        .collectAsStateWithLifecycle(initialValue = false)

    // Building on darkColorScheme()/lightColorScheme() (rather than copying MaterialTheme's own
    // default scheme) matters: each fills in every role with a sensible default of the right
    // brightness first, including ones not explicitly overridden below (dialog/dropdown/card
    // containers, outlines, etc.) -- copying a mismatched default left some of those the wrong
    // brightness regardless of the handful of roles actually listed here.
    val colorScheme = if (useLightTheme) {
        lightColorScheme(
            primary = PipBoyGreenDark,
            onPrimary = PipBoyWhite,
            primaryContainer = PipBoyWhiteContainer,
            onPrimaryContainer = PipBoyGreenDark,
            secondary = PipBoyGreenDark,
            onSecondary = PipBoyWhite,
            secondaryContainer = PipBoyWhiteContainer,
            onSecondaryContainer = PipBoyGreenDark,
            tertiary = PipBoyGreenDark,
            onTertiary = PipBoyWhite,
            tertiaryContainer = PipBoyWhiteContainer,
            onTertiaryContainer = PipBoyGreenDark,
            background = PipBoyWhite,
            onBackground = Color.Black,
            surface = PipBoyWhite,
            onSurface = Color.Black,
            surfaceVariant = PipBoyWhiteContainer,
            onSurfaceVariant = PipBoyGreenDark,
            outline = PipBoyGreenDark,
            error = PipBoyRedLight,
            onError = PipBoyWhite
        )
    } else {
        darkColorScheme(
            primary = PipBoyGreen,
            onPrimary = PipBoyBlack,
            primaryContainer = PipBoyBlackContainer,
            onPrimaryContainer = PipBoyGreen,
            secondary = PipBoyGreenDim,
            onSecondary = PipBoyBlack,
            secondaryContainer = PipBoyBlackContainer,
            onSecondaryContainer = PipBoyGreen,
            tertiary = PipBoyGreenDim,
            onTertiary = PipBoyBlack,
            tertiaryContainer = PipBoyBlackContainer,
            onTertiaryContainer = PipBoyGreen,
            background = PipBoyBlack,
            onBackground = PipBoyGreen,
            surface = PipBoyBlack,
            onSurface = PipBoyGreen,
            surfaceVariant = PipBoyBlackContainer,
            onSurfaceVariant = PipBoyGreenDim,
            outline = PipBoyGreenDim,
            error = PipBoyRed,
            onError = PipBoyBlack
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography.monospaced()) {
        Surface(color = colorScheme.background, content = content)
    }
}
