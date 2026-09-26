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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.ui.common.text
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.ledColorAt
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * Full-screen view of the machine pouring, for every [PourPhase] but [PourPhase.Idle]. When
 * the LED show is on, the whole panel is washed in the cycling strip colour; when it is off
 * the same animation runs in the machine's neutral cyan.
 */
@Composable
fun PreparationOverlay(
    cocktailName: String,
    pour: PourPhase,
    led: LedState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent by animateColorAsState(
        targetValue = if (pour is PourPhase.Failed) ErrorRed else led.primary,
        animationSpec = tween(600),
        label = "prepAccent",
    )
    val secondary = if (led.enabled) led.secondary else NeonCyan.copy(alpha = 0.4f)
    val progress by animateFloatAsState(
        targetValue = when (pour) {
            is PourPhase.Pouring -> pour.progress
            is PourPhase.Finished -> 1f
            PourPhase.Idle, is PourPhase.Failed -> 0f
        },
        animationSpec = tween(durationMillis = 700),
        label = "prepProgress",
    )
    val waitingForGlass = (pour as? PourPhase.Pouring)?.waitingForGlass == true

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
                waitingForGlass = waitingForGlass,
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = when (pour) {
                    is PourPhase.Finished -> stringResource(R.string.pour_ready)
                    is PourPhase.Failed -> stringResource(R.string.pour_failed_title)
                    else -> cocktailName
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = headline(pour, cocktailName),
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
                textAlign = TextAlign.Center,
            )
            val detail = (pour as? PourPhase.Pouring)?.let { detail(it) }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (pour !is PourPhase.Failed) {
                Spacer(Modifier.height(28.dp))
                ProgressTrack(progress = progress, accent = accent, secondary = secondary, led = led)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.pour_percent, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(36.dp))
            when (pour) {
                is PourPhase.Pouring -> if (pour.aborting) {
                    Text(stringResource(R.string.pour_stopping), color = TextSecondary)
                } else {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.pour_abort), color = TextSecondary)
                    }
                }

                is PourPhase.Finished -> Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.pour_take_glass))
                }

                is PourPhase.Failed -> TextButton(onClick = onDone) {
                    Text(stringResource(R.string.pour_dismiss), color = MaterialTheme.colorScheme.onSurface)
                }

                PourPhase.Idle -> Unit
            }
        }
    }
}

@Composable
private fun headline(pour: PourPhase, cocktailName: String): String = when (pour) {
    is PourPhase.Pouring -> when {
        pour.waitingForGlass -> stringResource(R.string.pour_place_glass)
        else -> pour.currentStep?.label.orEmpty()
    }

    is PourPhase.Finished -> stringResource(R.string.pour_served, cocktailName)

    is PourPhase.Failed -> when (val failure = pour.failure) {
        PourFailure.NothingPourable -> stringResource(R.string.pour_nothing_pourable)
        is PourFailure.Refused -> failure.error.text()
        is PourFailure.Faulted -> failure.message ?: stringResource(R.string.pour_stopped_unexpectedly)
    }

    PourPhase.Idle -> ""
}

@Composable
private fun detail(pour: PourPhase.Pouring): String? {
    if (pour.waitingForGlass) return stringResource(R.string.pour_waiting_detail)
    val step = pour.currentStep ?: return null
    val volume = step.volume ?: return step.detail
    val poured = stringResource(R.string.pour_step_volume, volume.dispensedMl.toInt(), volume.plannedMl.toInt())
    return if (volume.measured) stringResource(R.string.pour_step_measured, poured) else poured
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
                        style = Stroke(width = 6f),
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
                    contentDescription = stringResource(R.string.pour_waiting_for_glass),
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
                .background(Brush.horizontalGradient(listOf(accent, if (led.enabled) secondary else accent))),
        )
    }
}
