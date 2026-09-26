package com.example.smartbartender.ui.screens.bottles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.components.label
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * Inventory screen for the machine's bottle slots. Every toggle is persisted immediately,
 * so the rack survives an app restart.
 */
@Composable
fun BottlesScreen(
    state: BottlesUiState,
    led: LedState,
    onToggleBottle: (Bottle) -> Unit,
    onEjectSlot: (Int) -> Unit,
    onEjectAll: () -> Unit,
    onLoadDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = led.primary

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.bottles_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(R.plurals.bottles_summary, BottleCatalog.MAX_SLOTS, BottleCatalog.MAX_SLOTS, state.loadedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(14.dp))

                SlotRack(slots = state.slots, accent = accent, onEjectSlot = onEjectSlot)

                AnimatedVisibility(
                    visible = state.showRackFullNotice,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.bottles_rack_full_notice, BottleCatalog.MAX_SLOTS, BottleCatalog.MAX_SLOTS),
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonAmber,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                Row {
                    TextButton(onClick = onLoadDefaults) { Text(stringResource(R.string.bottles_load_starter)) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onEjectAll, enabled = state.loadedCount > 0) {
                        Text(stringResource(R.string.bottles_eject_all))
                    }
                }
            }
        }

        BottleCatalog.byCategory.forEach { (category, bottles) ->
            item(key = "header-${category.name}") {
                SectionHeader(
                    title = category.label(),
                    trailing = "${bottles.count { state.isLoaded(it) }}/${bottles.size}",
                    accent = accent,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(bottles, key = { it.id }) { bottle ->
                val loaded = state.isLoaded(bottle)
                BottleRow(
                    bottle = bottle,
                    loaded = loaded,
                    // With a full rack the remaining bottles read as unavailable until one is ejected.
                    blocked = !loaded && state.isFull,
                    accent = accent,
                    onToggle = { onToggleBottle(bottle) },
                )
            }
        }
    }
}

/** The four physical slots, drawn as a front-panel readout. Tap a filled slot to eject it. */
@Composable
private fun SlotRack(
    slots: List<Bottle?>,
    accent: Color,
    onEjectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEachIndexed { index, bottle ->
            val filled = bottle != null
            GlassPanel(
                modifier = Modifier.weight(1f),
                accent = if (filled) accent else null,
                cornerRadius = 14,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                onClick = if (filled) ({ onEjectSlot(index) }) else null,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.bottles_slot, index + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (filled) accent else TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(if (filled) accent.copy(alpha = 0.85f) else SteelOutline.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = bottle?.displayName ?: stringResource(R.string.bottles_slot_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (filled) MaterialTheme.colorScheme.onSurface else TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottleRow(
    bottle: Bottle,
    loaded: Boolean,
    blocked: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorColor by animateColorAsState(
        targetValue = if (loaded) accent else SteelOutline,
        label = "bottleIndicator",
    )
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (blocked) 0.45f else 1f),
        accent = if (loaded) accent else null,
        cornerRadius = 16,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A filled slot reads as a lit indicator on the machine's front panel.
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(indicatorColor.copy(alpha = if (loaded) 0.9f else 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (loaded) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = bottle.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        when {
                            loaded -> R.string.bottles_loaded_hint
                            blocked -> R.string.bottles_rack_full
                            else -> R.string.bottles_load_hint
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        loaded -> accent
                        blocked -> NeonAmber
                        else -> TextSecondary
                    },
                )
            }
            if (loaded) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.bottles_eject, bottle.displayName),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
