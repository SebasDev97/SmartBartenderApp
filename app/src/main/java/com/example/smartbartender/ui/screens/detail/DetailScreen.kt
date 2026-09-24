package com.example.smartbartender.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.RecipeIngredient
import com.example.smartbartender.ui.components.CocktailImage
import com.example.smartbartender.ui.components.ErrorState
import com.example.smartbartender.ui.components.FavouriteButton
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MetaChip
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.components.SkeletonBlock
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonMagenta
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/** Full recipe with the "Make this cocktail" trigger. */
@Composable
fun DetailScreen(
    state: DetailUiState,
    led: LedState,
    onStartPreparation: () -> Unit,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
    onCancelPreparation: () -> Unit,
    onFinishAcknowledged: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> DetailSkeleton(contentPadding)
            state.errorMessage != null -> ErrorState(message = state.errorMessage, onRetry = onRetry)
            state.cocktail != null -> RecipeContent(
                cocktail = state.cocktail,
                led = led,
                preparationRunning = !state.preparation.isIdle,
                machineOnline = state.machineOnline,
                pourNotes = state.pourNotes,
                isFavourite = state.isFavourite,
                onStartPreparation = onStartPreparation,
                onToggleFavourite = onToggleFavourite,
                onEdit = if (state.isCustom) onEdit else null,
                contentPadding = contentPadding,
            )
        }

        AnimatedVisibility(
            visible = !state.preparation.isIdle,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PreparationOverlay(
                cocktailName = state.cocktail?.name.orEmpty(),
                preparation = state.preparation,
                led = led,
                onCancel = onCancelPreparation,
                onDone = onFinishAcknowledged,
            )
        }
    }
}

@Composable
private fun RecipeContent(
    cocktail: Cocktail,
    led: LedState,
    preparationRunning: Boolean,
    machineOnline: Boolean,
    pourNotes: List<String>,
    isFavourite: Boolean,
    onStartPreparation: () -> Unit,
    onToggleFavourite: () -> Unit,
    onEdit: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    val accent = if (led.enabled) led.primary else NeonCyan
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 32.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            CocktailImage(
                url = cocktail.thumbUrl,
                contentDescription = cocktail.name,
                look = cocktail.look,
                modifier = Modifier.fillMaxSize(),
            )
            // Fade the photo into the page so the layout reads as one panel.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Obsidian,
                        ),
                    ),
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cocktail.name,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit drink", tint = TextSecondary)
                    }
                }
                FavouriteButton(isFavourite = isFavourite, onToggle = onToggleFavourite, heartSize = 26.dp)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cocktail.category?.let { MetaChip(text = it, accent = accent) }
                cocktail.alcoholic?.let {
                    MetaChip(text = it, accent = if (it.contains("Non", ignoreCase = true)) TextSecondary else NeonMagenta)
                }
                cocktail.glass?.let { MetaChip(text = it) }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStartPreparation,
                // Pouring is the machine's job now. With nothing connected there is no
                // honest thing for this button to do, so it says so rather than miming one.
                enabled = machineOnline && !preparationRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Obsidian,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Make this cocktail", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            if (!machineOnline) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Machine offline — set its address in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else if (pourNotes.isNotEmpty()) {
                // Ice, mint and a salted rim are nobody's pump. Say so before the glass
                // comes out half-made and it reads as a bug.
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "You'll add: " + pourNotes.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))
            SectionHeader(title = "Ingredients", trailing = "${cocktail.ingredients.size}", accent = accent)
            Spacer(Modifier.height(10.dp))
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column {
                    cocktail.ingredients.forEachIndexed { index, ingredient ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        IngredientRow(ingredient = ingredient, accent = accent)
                    }
                    if (cocktail.ingredients.isEmpty()) {
                        Text(
                            "This recipe lists no ingredients.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "Preparation", accent = accent)
            Spacer(Modifier.height(10.dp))
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = cocktail.instructions ?: "No instructions provided for this cocktail.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun IngredientRow(ingredient: RecipeIngredient, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = ingredient.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ingredient.measure ?: "to taste",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
private fun DetailSkeleton(contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize()) {
        SkeletonBlock(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 0,
        )
        Column(Modifier.padding(16.dp)) {
            SkeletonBlock(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(30.dp),
            )
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                cornerRadius = 16,
            )
            Spacer(Modifier.height(24.dp))
            SkeletonBlock(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                cornerRadius = 20,
            )
            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}
