package com.example.smartbartender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.MakeableCocktail
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/** Cocktail photo with a graceful placeholder when the API has no thumbnail. */
@Composable
fun CocktailImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.LocalBar,
                contentDescription = contentDescription,
                tint = TextSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Grid tile used by the Library screen. */
@Composable
fun CocktailGridCard(
    cocktail: CocktailSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = NeonCyan,
) {
    GlassPanel(
        modifier = modifier,
        accent = accent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        onClick = onClick,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                CocktailImage(
                    url = cocktail.thumbUrl,
                    contentDescription = cocktail.name,
                    modifier = Modifier.fillMaxSize(),
                )
                // Bottom scrim keeps the title legible over bright drink photos.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Obsidian.copy(alpha = 0.75f),
                            ),
                        ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = cocktail.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Row used by the Available screen. Shows the pour-ready state: a full recipe match gets a
 * cyan edge, an "almost" match an amber edge plus the ingredient that is missing.
 */
@Composable
fun MakeableCocktailRow(
    makeable: MakeableCocktail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (makeable.canMakeNow) NeonCyan else NeonAmber
    val cocktail: Cocktail = makeable.cocktail
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CocktailImage(
                url = cocktail.thumbUrl,
                contentDescription = cocktail.name,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = cocktail.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cocktail.ingredients.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (makeable.canMakeNow) {
                        MetaChip(text = "Ready to pour", accent = NeonCyan)
                    } else {
                        MetaChip(text = "Missing: ${makeable.missingIngredients.first()}", accent = NeonAmber)
                    }
                }
            }
        }
    }
}
