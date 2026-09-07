package com.example.smartbartender.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DeLuxeColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Obsidian,
    primaryContainer = Color_PrimaryContainer,
    onPrimaryContainer = NeonCyan,
    secondary = NeonMagenta,
    onSecondary = Obsidian,
    secondaryContainer = Color_SecondaryContainer,
    onSecondaryContainer = NeonMagenta,
    tertiary = NeonViolet,
    onTertiary = Obsidian,
    background = Obsidian,
    onBackground = TextPrimary,
    surface = Graphite,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = GraphiteElevated,
    surfaceContainerHigh = GraphiteElevated,
    outline = SteelOutline,
    outlineVariant = SteelOutline,
    error = ErrorRed,
    onError = Obsidian,
)

/**
 * The app is a single-purpose appliance companion: it is always dark, never dynamic-colored.
 * [darkTheme] is accepted only so previews can be explicit about it.
 */
@Composable
fun SmartBartenderTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DeLuxeColorScheme,
        typography = Typography,
        content = content,
    )
}
