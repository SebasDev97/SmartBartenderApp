package com.example.smartbartender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.theme.Graphite
import com.example.smartbartender.ui.theme.GraphiteElevated
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * The app's signature surface: a dark glossy panel with a top-lit gradient and a hairline
 * edge. [accent] tints the edge, which is how LED state bleeds into the UI.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    cornerRadius: Int = 20,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    val edge = accent?.copy(alpha = 0.55f) ?: SteelOutline
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(GraphiteElevated, Graphite),
                ),
            )
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(edge, SteelOutline.copy(alpha = 0.4f)))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Small uppercase caption used above list sections. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(width = 3.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}

/** Compact metadata chip (category, glass, alcohol content, missing ingredient …). */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = TextSecondary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}
