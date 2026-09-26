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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.ui.common.text
import com.example.smartbartender.ui.components.CocktailListSkeleton
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.ErrorState
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MakeableCocktailRow
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.SmartBartenderTheme
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
                title = stringResource(R.string.available_no_bottles_title),
                message = pluralStringResource(R.plurals.available_no_bottles_message, BottleCatalog.MAX_SLOTS, BottleCatalog.MAX_SLOTS),
                actionLabel = stringResource(R.string.available_load_bottles),
                onAction = onOpenBottles,
                modifier = Modifier.padding(contentPadding),
            )

            state.isLoading -> CocktailListSkeleton(modifier = Modifier.padding(contentPadding))

            // Custom drinks don't need the network, so a failed fetch only blanks the screen
            // when there is nothing of the user's own to show either.
            state.loadError != null && state.isEmptyResult -> ErrorState(
                message = state.loadError.text(),
                onRetry = onRetry,
                modifier = Modifier.padding(contentPadding),
            )

            state.isEmptyResult -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.available_nothing_title),
                message = pluralStringResource(R.plurals.available_nothing_message, BottleCatalog.MAX_SLOTS, BottleCatalog.MAX_SLOTS),
                actionLabel = stringResource(R.string.available_adjust_bottles),
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
                            title = stringResource(R.string.available_can_make_now),
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
                            title = stringResource(R.string.available_one_bottle_away),
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
            color = led.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = pluralStringResource(R.plurals.available_ready_to_pour, readyCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = pluralStringResource(R.plurals.slots_filled, BottleCatalog.MAX_SLOTS, bottleCount, BottleCatalog.MAX_SLOTS),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Preview
@Composable
private fun AvailableScreenEmptyRackPreview() {
    SmartBartenderTheme {
        AvailableScreen(
            state = AvailableUiState(isLoading = false, inventoryKnown = true),
            led = LedState.Off,
            onCocktailClick = {},
            onOpenBottles = {},
            onRetry = {},
        )
    }
}
