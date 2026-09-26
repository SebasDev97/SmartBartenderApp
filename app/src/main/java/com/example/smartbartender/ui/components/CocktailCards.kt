package com.example.smartbartender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.DrinkLook
import com.example.smartbartender.domain.model.MakeableCocktail
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * Cocktail photo with placeholder when the API has no thumbnail. A custom drink has no photo,
 * so its [look] — an emoji on its chosen colour — stands in for one.
 */
@Composable
fun CocktailImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    look: DrinkLook? = null,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (look != null) {
            DrinkLookImage(look = look, contentDescription = contentDescription, modifier = Modifier.fillMaxSize())
        } else if (url.isNullOrBlank()) {
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

@Composable
private fun DrinkLookImage(look: DrinkLook, contentDescription: String?, modifier: Modifier = Modifier) {
    val colour = Color(look.colorArgb)
    BoxWithConstraints(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    0f to colour.copy(alpha = 0.55f),
                    1f to Obsidian,
                ),
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        // Scale the emoji with the tile: a 76 dp row thumbnail and a full-width hero both
        // read as one glyph filling about half the frame.
        val glyphSize = with(LocalDensity.current) { (minOf(maxWidth, maxHeight) * 0.45f).toSp() }
        Text(text = look.emoji.ifBlank { "🍹" }, fontSize = glyphSize)
    }
}

/** Grid tile used by the Library screen. The heart only shows when [onToggleFavourite] is set. */
@Composable
fun CocktailGridCard(
    cocktail: CocktailSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = NeonCyan,
    isFavourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
) {
    GlassPanel(
        modifier = modifier,
        accent = accent,
        contentPadding = PaddingValues(10.dp),
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
                    look = cocktail.look,
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
                if (onToggleFavourite != null) {
                    FavouriteButton(
                        isFavourite = isFavourite,
                        onToggle = onToggleFavourite,
                        heartSize = 20.dp,
                        idleTint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Obsidian.copy(alpha = 0.55f)),
                    )
                }
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
        contentPadding = PaddingValues(10.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CocktailImage(
                url = cocktail.thumbUrl,
                contentDescription = cocktail.name,
                look = cocktail.look,
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
                        MetaChip(text = stringResource(R.string.chip_ready_to_pour), accent = NeonCyan)
                    } else {
                        MetaChip(
                            text = stringResource(R.string.chip_missing, makeable.missingIngredients.first()),
                            accent = NeonAmber,
                        )
                    }
                }
            }
        }
    }
}
