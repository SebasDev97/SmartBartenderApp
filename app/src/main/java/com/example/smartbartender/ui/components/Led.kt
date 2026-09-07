package com.example.smartbartender.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.smartbartender.ui.theme.LedSpectrum
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.SteelOutline

/** Colour of the simulated LED strip at [progress] (0f..1f) through its cycle. */
fun ledColorAt(progress: Float): Color {
    val steps = LedSpectrum.size - 1
    val scaled = (progress.coerceIn(0f, 1f)) * steps
    val index = scaled.toInt().coerceIn(0, steps - 1)
    return lerp(LedSpectrum[index], LedSpectrum[index + 1], scaled - index)
}

/**
 * Drives the LED show simulation. Runs a single infinite transition regardless of [enabled]
 * so switching the show on and off never restructures the composition; when the show is off
 * the colour simply collapses to the machine's neutral cyan.
 */
@Composable
fun rememberLedState(enabled: Boolean, cycleMillis: Int = 7000): LedState {
    val transition = rememberInfiniteTransition(label = "led")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ledProgress",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ledPulse",
    )
    return LedState(
        enabled = enabled,
        progress = progress,
        primary = if (enabled) ledColorAt(progress) else NeonCyan,
        secondary = if (enabled) ledColorAt((progress + 0.35f) % 1f) else SteelOutline,
        intensity = if (enabled) pulse else 0f,
    )
}

/** Snapshot of the simulated LED strip for one frame. */
data class LedState(
    val enabled: Boolean,
    val progress: Float,
    val primary: Color,
    val secondary: Color,
    val intensity: Float,
)

/**
 * Ambient light spill behind screen content. Neutral (invisible) when the LED show is off.
 */
@Composable
fun LedAmbience(
    led: LedState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (!led.enabled) return@drawBehind
                    val alpha = 0.16f * led.intensity
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(led.primary.copy(alpha = alpha), Color.Transparent),
                            center = Offset(size.width * 0.15f, size.height * 0.1f),
                            radius = size.maxDimension * 0.7f,
                        ),
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(led.secondary.copy(alpha = alpha * 0.8f), Color.Transparent),
                            center = Offset(size.width * 0.9f, size.height * 0.85f),
                            radius = size.maxDimension * 0.7f,
                        ),
                    )
                },
        )
        content()
    }
}
