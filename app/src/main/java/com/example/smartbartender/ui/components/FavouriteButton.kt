package com.example.smartbartender.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonMagenta
import com.example.smartbartender.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Heart toggle shared by the recipe screen and the library cards.
 *
 * Favouriting pops the heart on an overshooting spring and throws a ring and a few sparks out
 * of it; un-favouriting only gives a small squish. The burst is kept inside ~70% of [heartSize]
 * from the centre so it stays within the button's own clip.
 */
@Composable
fun FavouriteButton(
    isFavourite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    heartSize: Dp = 24.dp,
    idleTint: Color = TextSecondary,
) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(
                if (isFavourite) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
            )
            onToggle()
        },
        modifier = modifier,
    ) {
        AnimatedHeart(isFavourite = isFavourite, size = heartSize, idleTint = idleTint)
    }
}

@Composable
private fun AnimatedHeart(isFavourite: Boolean, size: Dp, idleTint: Color) {
    val scale = remember { Animatable(1f) }
    // 0 → 1 over the burst; 1 means nothing is drawn.
    val burst = remember { Animatable(1f) }
    // Only animate real changes, not the first composition or a card scrolling back into view.
    var previous by remember { mutableStateOf(isFavourite) }

    LaunchedEffect(isFavourite) {
        if (isFavourite == previous) return@LaunchedEffect
        previous = isFavourite
        if (isFavourite) {
            launch {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
            }
            scale.snapTo(0.55f)
            scale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        } else {
            burst.snapTo(1f)
            scale.animateTo(0.8f, tween(durationMillis = 90))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    val tint by animateColorAsState(
        targetValue = if (isFavourite) NeonMagenta else idleTint,
        animationSpec = tween(durationMillis = 180),
        label = "heartTint",
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val t = burst.value
            if (t >= 1f) return@Canvas
            val maxRadius = this.size.minDimension * 0.7f

            // A ring that swells out of the heart and thins away.
            drawCircle(
                color = NeonMagenta.copy(alpha = (1f - t) * 0.8f),
                radius = maxRadius * (0.35f + 0.65f * t),
                style = Stroke(width = (1f - t) * this.size.minDimension * 0.14f),
            )

            // Sparks fly out a beat later, so they read as coming from the pop.
            val sparkT = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
            if (sparkT > 0f) {
                SPARK_COLORS.forEachIndexed { index, color ->
                    val angle = Math.toRadians(-90.0 + index * 360.0 / SPARK_COLORS.size)
                    val distance = maxRadius * (0.45f + 0.55f * sparkT)
                    drawCircle(
                        color = color.copy(alpha = 1f - sparkT),
                        radius = this.size.minDimension * 0.07f * (1f - sparkT * 0.6f),
                        center = center + Offset(
                            x = (cos(angle) * distance).toFloat(),
                            y = (sin(angle) * distance).toFloat(),
                        ),
                    )
                }
            }
        }
        Icon(
            imageVector = if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

private val SPARK_COLORS = listOf(NeonMagenta, NeonAmber, NeonCyan, NeonMagenta, NeonAmber, NeonCyan)
