package com.example.smartbartender.ui.screens.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MetaChip
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.components.ledColorAt
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/** Machine settings — most importantly the De-Luxe LED show. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    led: LedState,
    onLedShowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = if (led.enabled) led.primary else NeonCyan
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
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))

        SectionHeader(title = "De-Luxe", accent = accent)
        Spacer(Modifier.height(10.dp))

        GlassPanel(accent = if (led.enabled) accent else null, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "LED show",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Animated light strip around the glass tray, playing while a cocktail is poured.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.ledShowEnabled,
                        onCheckedChange = onLedShowChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Obsidian,
                            checkedTrackColor = accent,
                            checkedBorderColor = accent,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
                LedStrip(led = led)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.ledShowEnabled) "Strip online — cycling spectrum" else "Strip off — neutral finish",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.ledShowEnabled) accent else TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "Machine", accent = accent)
        Spacer(Modifier.height(10.dp))

        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column {
                InfoRow("Slots filled", "${state.loadedBottleCount} / ${state.totalBottleCount}")
                Spacer(Modifier.height(12.dp))
                InfoRow("Recipe source", "TheCocktailDB")
                Spacer(Modifier.height(12.dp))
                InfoRow("Hardware link", "Simulated")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(text = "Prototype build", accent = accent)
                    MetaChip(text = "De-Luxe edition", accent = TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Pours are simulated in software. No hardware is driven by this prototype.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

/** Live preview of the LED strip, so the toggle has something to show for itself. */
@Composable
private fun LedStrip(led: LedState, modifier: Modifier = Modifier) {
    val colors = if (led.enabled) {
        List(7) { index -> ledColorAt((led.progress + index / 7f) % 1f) }
    } else {
        List(7) { MaterialTheme.colorScheme.surfaceVariant }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(colors)),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
