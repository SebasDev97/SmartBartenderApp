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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smartbartender.domain.model.BottleVolume
import com.example.smartbartender.domain.model.DrinkCount
import com.example.smartbartender.domain.model.Milestone
import com.example.smartbartender.domain.model.PourStats
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalLocale

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
    val accent = if (led.enabled) led.primary else NeonCyan
    val stats = state.stats

    if (state.isEmpty || stats == null) {
        if (!state.isLoading) {
            EmptyState(
                icon = Icons.Filled.Insights,
                title = "No pours yet",
                message = "Cocktails you make from this phone are counted here — how many, " +
                    "which ones, and how much went through each pump.",
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
            SectionHeader(title = "Most made", accent = accent)
            Spacer(Modifier.height(10.dp))
            TopCocktails(stats.topCocktails, accent = accent, onCocktailClick = onCocktailClick)
        }

        if (stats.bottles.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "Through the pumps", accent = accent)
            Spacer(Modifier.height(10.dp))
            BottleLeaderboard(stats.bottles, accent = accent)
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "Your rhythm", accent = accent)
        Spacer(Modifier.height(10.dp))
        Rhythm(stats)

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            title = "Milestones",
            trailing = "${stats.milestones.count { it.achieved }} / ${stats.milestones.size}",
            accent = accent,
        )
        Spacer(Modifier.height(10.dp))
        Milestones(stats.milestones, accent = accent)

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Counted on this phone. The machine can't see how much is left in a bottle — " +
                "these are the volumes its pumps poured.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onResetRequest) {
            Text("Reset statistics", color = ErrorRed)
        }
    }

    if (state.showResetConfirm) {
        AlertDialog(
            onDismissRequest = onResetDismiss,
            title = { Text("Reset statistics?") },
            text = { Text("Every pour recorded on this phone is forgotten. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = onResetConfirm) { Text("Reset", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = onResetDismiss) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun HeroTiles(stats: PourStats, accent: Color, ledEnabled: Boolean) {
    val edge = if (ledEnabled) accent else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${stats.cocktailsMade}", "Cocktails made", accent, edge, Modifier.weight(1f))
            StatTile(formatVolume(stats.totalMl), "Poured in total", accent, edge, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${stats.recipesTried}", "Recipes tried", accent, edge, Modifier.weight(1f))
            StatTile(
                "${stats.thisWeek}",
                "This week · ${stats.thisMonth} this month",
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
                        text = "×${drink.count}",
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
                            text = formatVolume(bottle.ml) + bottleEquivalents(bottle),
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
            stats.happyHour?.let { InfoRow("Happy hour", String.format(LocalLocale.current.platformLocale, "%02d:00", it)) }
            stats.favouriteWeekday?.let { InfoRow("Favourite day", DateFormatSymbols.getInstance().weekdays[it]) }
            stats.averageDrinkMl?.let { InfoRow("Average drink", "${it.roundToInt()} ml") }
            if (stats.cocktailsMade > 0) {
                InfoRow("Alcohol-free", "${stats.mocktails} (${(stats.mocktailShare * 100).roundToInt()}%)")
            }
            InfoRow("Changed my mind", "${stats.stoppedEarly}")
            if (stats.failed > 0) InfoRow("Machine trouble", "${stats.failed}")
            stats.firstPourAtMs?.let { InfoRow("First pour", dates.format(Date(it))) }
            stats.lastPourAtMs?.let { InfoRow("Latest pour", dates.format(Date(it))) }
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
                        contentDescription = if (milestone.achieved) "Achieved" else "Locked",
                        tint = if (milestone.achieved) accent else TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = milestone.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (milestone.achieved) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                TextSecondary
                            },
                        )
                        Text(
                            text = milestone.detail,
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
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
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

private fun formatVolume(ml: Double): String =
    if (ml < 1000) "${ml.roundToInt()} ml" else String.format(Locale.getDefault(), "%.1f L", ml / 1000)

/** "≈ 1.7 bottles" once a pump has poured at least a tenth of one; below that it's noise. */
private fun bottleEquivalents(bottle: BottleVolume): String {
    val bottles = bottle.bottleEquivalents
    if (bottles < 0.1) return ""
    return String.format(Locale.getDefault(), " · ≈ %.1f bottle%s", bottles, if (bottles >= 1.05) "s" else "")
}
