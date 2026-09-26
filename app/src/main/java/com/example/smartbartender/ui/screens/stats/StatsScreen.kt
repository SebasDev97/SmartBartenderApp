package com.example.smartbartender.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.BottleVolume
import com.example.smartbartender.domain.model.DrinkCount
import com.example.smartbartender.domain.model.Milestone
import com.example.smartbartender.domain.model.MilestoneKind
import com.example.smartbartender.domain.model.PourStats
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.InfoRow
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.util.Date
import kotlin.math.roundToInt

/** What this phone has poured: counts, favourites, the busiest hour, and a few milestones. */
@Composable
fun StatsScreen(
    state: StatsUiState,
    led: LedState,
    onCocktailClick: (String) -> Unit,
    onResetRequest: () -> Unit,
    onResetConfirm: () -> Unit,
    onResetDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = led.primary
    val stats = state.stats

    if (state.isEmpty || stats == null) {
        if (!state.isLoading) {
            EmptyState(
                icon = Icons.Filled.Insights,
                title = stringResource(R.string.stats_empty_title),
                message = stringResource(R.string.stats_empty_message),
                accent = accent,
                modifier = modifier.padding(contentPadding),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        HeroTiles(stats = stats, accent = accent, ledEnabled = led.enabled)

        if (stats.topCocktails.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = stringResource(R.string.stats_most_made), accent = accent)
            Spacer(Modifier.height(10.dp))
            TopCocktails(stats.topCocktails, accent = accent, onCocktailClick = onCocktailClick)
        }

        if (stats.bottles.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = stringResource(R.string.stats_through_pumps), accent = accent)
            Spacer(Modifier.height(10.dp))
            BottleLeaderboard(stats.bottles, accent = accent)
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.stats_rhythm), accent = accent)
        Spacer(Modifier.height(10.dp))
        Rhythm(stats)

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            title = stringResource(R.string.stats_milestones),
            trailing = "${stats.milestones.count { it.achieved }} / ${stats.milestones.size}",
            accent = accent,
        )
        Spacer(Modifier.height(10.dp))
        Milestones(stats.milestones, accent = accent)

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.stats_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onResetRequest) {
            Text(stringResource(R.string.stats_reset), color = ErrorRed)
        }
    }

    if (state.showResetConfirm) {
        AlertDialog(
            onDismissRequest = onResetDismiss,
            title = { Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_message)) },
            confirmButton = {
                TextButton(onClick = onResetConfirm) { Text(stringResource(R.string.stats_reset_confirm), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = onResetDismiss) { Text(stringResource(R.string.stats_reset_keep)) }
            },
        )
    }
}

@Composable
private fun HeroTiles(stats: PourStats, accent: Color, ledEnabled: Boolean) {
    val edge = if (ledEnabled) accent else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${stats.cocktailsMade}", stringResource(R.string.stats_cocktails_made), accent, edge, Modifier.weight(1f))
            StatTile(volumeText(stats.totalMl), stringResource(R.string.stats_poured_total), accent, edge, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${stats.recipesTried}", stringResource(R.string.stats_recipes_tried), accent, edge, Modifier.weight(1f))
            StatTile(
                "${stats.thisWeek}",
                stringResource(R.string.stats_this_week, stats.thisMonth),
                accent,
                edge,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, accent: Color, edge: Color?, modifier: Modifier) {
    GlassPanel(accent = edge, modifier = modifier) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TopCocktails(drinks: List<DrinkCount>, accent: Color, onCocktailClick: (String) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
        Column {
            drinks.forEachIndexed { index, drink ->
                val id = drink.drinkId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (id != null) Modifier.clickable { onCocktailClick(id) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (index == 0) accent else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(
                        text = drink.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.stats_times, drink.count),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottleLeaderboard(bottles: List<BottleVolume>, accent: Color) {
    val most = bottles.maxOf { it.ml }.coerceAtLeast(1.0)
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            bottles.forEach { bottle ->
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = bottle.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = bottleVolumeText(bottle),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Bar(fraction = (bottle.ml / most).toFloat(), color = accent)
                }
            }
        }
    }
}

@Composable
private fun Rhythm(stats: PourStats) {
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            stats.happyHour?.let { InfoRow(stringResource(R.string.stats_happy_hour), stringResource(R.string.stats_hour, it), emphasized = true) }
            stats.favouriteWeekday?.let {
                InfoRow(stringResource(R.string.stats_favourite_day), DateFormatSymbols.getInstance().weekdays[it], emphasized = true)
            }
            stats.averageDrinkMl?.let {
                InfoRow(stringResource(R.string.stats_average_drink), stringResource(R.string.volume_ml, it.roundToInt()), emphasized = true)
            }
            if (stats.cocktailsMade > 0) {
                InfoRow(
                    stringResource(R.string.stats_alcohol_free),
                    stringResource(R.string.stats_share, stats.mocktails, (stats.mocktailShare * 100).roundToInt()),
                    emphasized = true,
                )
            }
            InfoRow(stringResource(R.string.stats_changed_mind), "${stats.stoppedEarly}", emphasized = true)
            if (stats.failed > 0) InfoRow(stringResource(R.string.stats_machine_trouble), "${stats.failed}", emphasized = true)
            stats.firstPourAtMs?.let { InfoRow(stringResource(R.string.stats_first_pour), dates.format(Date(it)), emphasized = true) }
            stats.lastPourAtMs?.let { InfoRow(stringResource(R.string.stats_latest_pour), dates.format(Date(it)), emphasized = true) }
        }
    }
}

@Composable
private fun Milestones(milestones: List<Milestone>, accent: Color) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            milestones.forEach { milestone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (milestone.achieved) Icons.Filled.EmojiEvents else Icons.Outlined.Lock,
                        contentDescription = stringResource(
                            if (milestone.achieved) R.string.stats_milestone_achieved else R.string.stats_milestone_locked,
                        ),
                        tint = if (milestone.achieved) accent else TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(milestone.kind.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (milestone.achieved) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                TextSecondary
                            },
                        )
                        Text(
                            text = stringResource(milestone.kind.detailRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        if (!milestone.achieved) {
                            Spacer(Modifier.height(6.dp))
                            Bar(fraction = milestone.progress, color = accent.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(SteelOutline),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun volumeText(ml: Double): String =
    if (ml < 1000) stringResource(R.string.volume_ml, ml.roundToInt()) else stringResource(R.string.volume_litres, ml / 1000)

/** "1.2 L · ≈ 1.7 bottles" once a pump has poured at least a tenth of one; below that it's noise. */
@Composable
private fun bottleVolumeText(bottle: BottleVolume): String {
    val volume = volumeText(bottle.ml)
    val bottles = bottle.bottleEquivalents
    if (bottles < 0.1) return volume
    // "1.0 bottle" reads fine up to 1.05; past that the rounded number is plural.
    val quantity = if (bottles >= 1.05) 2 else 1
    return stringResource(R.string.stats_volume_with_bottles, volume, pluralStringResource(R.plurals.stats_bottles, quantity, bottles))
}

private val MilestoneKind.titleRes: Int
    get() = when (this) {
        MilestoneKind.FIRST_POUR -> R.string.milestone_first_pour
        MilestoneKind.REGULAR -> R.string.milestone_regular
        MilestoneKind.PARTY_HOST -> R.string.milestone_party_host
        MilestoneKind.CENTURION -> R.string.milestone_centurion
        MilestoneKind.FIRST_LITRE -> R.string.milestone_first_litre
        MilestoneKind.FIVE_LITRES -> R.string.milestone_five_litres
        MilestoneKind.EXPLORER -> R.string.milestone_explorer
        MilestoneKind.CONNOISSEUR -> R.string.milestone_connoisseur
        MilestoneKind.DESIGNATED_DRIVER -> R.string.milestone_designated_driver
    }

private val MilestoneKind.detailRes: Int
    get() = when (this) {
        MilestoneKind.FIRST_POUR -> R.string.milestone_first_pour_detail
        MilestoneKind.REGULAR -> R.string.milestone_regular_detail
        MilestoneKind.PARTY_HOST -> R.string.milestone_party_host_detail
        MilestoneKind.CENTURION -> R.string.milestone_centurion_detail
        MilestoneKind.FIRST_LITRE -> R.string.milestone_first_litre_detail
        MilestoneKind.FIVE_LITRES -> R.string.milestone_five_litres_detail
        MilestoneKind.EXPLORER -> R.string.milestone_explorer_detail
        MilestoneKind.CONNOISSEUR -> R.string.milestone_connoisseur_detail
        MilestoneKind.DESIGNATED_DRIVER -> R.string.milestone_designated_driver_detail
    }
