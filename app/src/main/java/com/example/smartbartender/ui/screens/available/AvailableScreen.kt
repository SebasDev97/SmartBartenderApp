package com.example.smartbartender.ui.screens.available

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Liquor
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MakeableCocktailRow
import com.example.smartbartender.ui.components.CocktailListSkeleton
import com.example.smartbartender.ui.components.ErrorState
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * "What can this machine pour right now" — the home screen of the appliance.
 */
@Composable
fun AvailableScreen(
    state: AvailableUiState,
    led: LedState,
    onCocktailClick: (String) -> Unit,
    onOpenBottles: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            !state.inventoryKnown -> CocktailListSkeleton(modifier = Modifier.padding(contentPadding))

            !state.hasBottles -> EmptyState(
                icon = Icons.Outlined.Liquor,
                title = "No bottles loaded",
                message = "Load up to ${com.example.smartbartender.domain.model.BottleCatalog.MAX_SLOTS} bottles into the rack and the machine will work out what it can pour.",
                actionLabel = "Load bottles",
                onAction = onOpenBottles,
                modifier = Modifier.padding(contentPadding),
            )

            state.isLoading -> CocktailListSkeleton(modifier = Modifier.padding(contentPadding))

            state.errorMessage != null -> ErrorState(
                message = state.errorMessage,
                onRetry = onRetry,
                modifier = Modifier.padding(contentPadding),
            )

            state.isEmptyResult -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "Nothing pourable yet",
                message = "None of the recipes match the bottles in the rack. With only ${com.example.smartbartender.domain.model.BottleCatalog.MAX_SLOTS} slots, a mixer or a citrus juice opens up the most drinks.",
                actionLabel = "Adjust bottles",
                onAction = onOpenBottles,
                accent = NeonAmber,
                modifier = Modifier.padding(contentPadding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ReadyHeadline(
                        readyCount = state.canMakeNow.size,
                        bottleCount = state.loadedBottles.size,
                        led = led,
                    )
                }

                if (state.canMakeNow.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Can make now",
                            trailing = "${state.canMakeNow.size}",
                            accent = NeonCyan,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(state.canMakeNow, key = { "now-${it.cocktail.id}" }) { makeable ->
                        MakeableCocktailRow(
                            makeable = makeable,
                            onClick = { onCocktailClick(makeable.cocktail.id) },
                        )
                    }
                }

                if (state.almost.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "One bottle away",
                            trailing = "${state.almost.size}",
                            accent = NeonAmber,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    items(state.almost, key = { "almost-${it.cocktail.id}" }) { makeable ->
                        MakeableCocktailRow(
                            makeable = makeable,
                            onClick = { onCocktailClick(makeable.cocktail.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Big readout at the top of the list, in the spirit of an appliance status panel. */
@Composable
private fun ReadyHeadline(
    readyCount: Int,
    bottleCount: Int,
    led: LedState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$readyCount",
            style = MaterialTheme.typography.displaySmall,
            color = if (led.enabled) led.primary else NeonCyan,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (readyCount == 1) "cocktail ready to pour" else "cocktails ready to pour",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$bottleCount of ${com.example.smartbartender.domain.model.BottleCatalog.MAX_SLOTS} slots filled",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}
