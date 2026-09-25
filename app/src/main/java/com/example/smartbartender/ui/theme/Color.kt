package com.example.smartbartender.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette: near-black machined surfaces with neon LED accents.
 */

// Neon accents
val NeonCyan = Color(0xFF2AF5E4)
val NeonMagenta = Color(0xFFFF3DD8)
val NeonViolet = Color(0xFF8B5CFF)
val NeonAmber = Color(0xFFFFB547)
val NeonLime = Color(0xFF9DFF3D)
val NeonBlue = Color(0xFF3D8BFF)

// Machined surfaces
val Obsidian = Color(0xFF05070C)
val Graphite = Color(0xFF0C1018)
val GraphiteElevated = Color(0xFF141A25)
val SteelOutline = Color(0xFF232C3B)

// Text
val TextPrimary = Color(0xFFEAF2FF)
val TextSecondary = Color(0xFF9AA8BF)

// Feedback
val ErrorRed = Color(0xFFFF5D6C)

/** Hues cycled by the LED show simulation. */
val LedSpectrum = listOf(
    NeonCyan,
    NeonBlue,
    NeonViolet,
    NeonMagenta,
    NeonAmber,
    NeonLime,
    NeonCyan,
)

// Tonal containers used by the Material 3 scheme
val Color_PrimaryContainer = Color(0xFF0E2E31)
val Color_SecondaryContainer = Color(0xFF2E0F29)
