package com.example.smartbartender.ui.screens.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.ledColorAt
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * Full-screen simulation of the machine pouring. When the LED show is on, the whole panel
 * is washed in the cycling strip colour; when it is off the same animation runs in the
 * machine's neutral cyan.
 */
@Composable
fun PreparationOverlay(
    cocktailName: String,
    preparation: PreparationState,
    led: LedState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent by animateColorAsState(
        targetValue = if (led.enabled) led.primary else NeonCyan,
        animationSpec = tween(600),
        label = "prepAccent",
    )
    val secondary = if (led.enabled) led.secondary else NeonCyan.copy(alpha = 0.4f)
    val progress by animateFloatAsState(
        targetValue = preparation.progress,
        animationSpec = tween(durationMillis = 700),
        label = "prepProgress",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.96f))
            // Swallow taps so the recipe underneath cannot be operated mid-pour.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .drawBehind {
                if (!led.enabled) return@drawBehind
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f * led.intensity), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PourVisual(
                progress = progress,
                accent = accent,
                secondary = secondary,
                led = led,
                waitingForGlass = preparation.waitingForGlass,
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = if (preparation.isFinished) "Ready" else cocktailName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    preparation.isFinished -> "$cocktailName is served. Enjoy."
                    preparation.waitingForGlass -> "Place a glass under the nozzle"
                    else -> preparation.currentStep?.label.orEmpty()
                },
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
                textAlign = TextAlign.Center,
            )
            val detail = if (preparation.waitingForGlass) {
                "Pouring starts as soon as the machine sees an empty glass"
            } else {
                preparation.currentStep?.detail
            }
            if (!preparation.isFinished && !detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))
            ProgressTrack(progress = progress, accent = accent, secondary = secondary, led = led)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )

            Spacer(Modifier.height(36.dp))
            if (preparation.isFinished) {
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Take glass")
                }
            } else {
                TextButton(onClick = onCancel) {
                    Text("Abort pour", color = TextSecondary)
                }
            }
        }
    }
}

/** A glass filling up, rendered with the LED colour of the moment. */
@Composable
private fun PourVisual(
    progress: Float,
    accent: Color,
    secondary: Color,
    led: LedState,
    waitingForGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Halo: the LED ring around the glass tray.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val ringColors = if (led.enabled) {
                        List(9) { index -> ledColorAt((led.progress + index / 9f) % 1f) }
                    } else {
                        List(9) { SteelOutline }
                    }
                    drawCircle(
                        brush = Brush.sweepGradient(ringColors),
                        radius = size.minDimension / 2f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                    )
                },
        )

        // The glass, filling from the bottom as the pour progresses.
        Box(
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 46.dp, bottomEnd = 46.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(progress.coerceIn(0.02f, 1f))
                    .background(Brush.verticalGradient(listOf(accent, secondary))),
            )
            if (waitingForGlass) {
                Icon(
                    imageVector = Icons.Filled.LocalBar,
                    contentDescription = "Waiting for a glass",
                    tint = accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp),
                )
            }
        }
    }
}

/** Slim progress rail under the glass. */
@Composable
private fun ProgressTrack(
    progress: Float,
    accent: Color,
    secondary: Color,
    led: LedState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(SteelOutline.copy(alpha = 0.5f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (led.enabled) {
                        Brush.horizontalGradient(listOf(accent, secondary))
                    } else {
                        Brush.horizontalGradient(listOf(accent, accent))
                    },
                ),
        )
    }
}
