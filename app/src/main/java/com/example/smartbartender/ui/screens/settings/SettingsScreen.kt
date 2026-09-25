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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MetaChip
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.components.ledColorAt
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/** Machine settings: where the machine lives, and the De-Luxe LED show. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    led: LedState,
    onLedShowChange: (Boolean) -> Unit,
    onMachineHostChange: (String) -> Unit,
    onMachinePortChange: (String) -> Unit,
    onMachineEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onTestConnection: () -> Unit,
    onOpenCalibration: () -> Unit,
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

        SectionHeader(title = "Machine link", accent = accent)
        Spacer(Modifier.height(10.dp))
        MachineLinkPanel(
            state = state,
            accent = accent,
            ledEnabled = led.enabled,
            onHostChange = onMachineHostChange,
            onPortChange = onMachinePortChange,
            onEnabledChange = onMachineEnabledChange,
            onConnect = onConnect,
            onTest = onTestConnection,
        )

        Spacer(Modifier.height(24.dp))
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

        val snapshot = (state.connection as? ConnectionState.Connected)?.snapshot
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column {
                InfoRow("Slots filled", "${state.loadedBottleCount} / ${state.totalBottleCount}")
                Spacer(Modifier.height(12.dp))
                InfoRow("Recipe source", "TheCocktailDB")
                Spacer(Modifier.height(12.dp))
                InfoRow("Hardware link", state.hardwareLinkLabel)
                if (snapshot != null) {
                    Spacer(Modifier.height(12.dp))
                    InfoRow(
                        "Glass sensor",
                        if (snapshot.sensorReferenceCm == null) "Not set up" else "Ready",
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(text = "Prototype build", accent = accent)
                    MetaChip(text = "De-Luxe edition", accent = TextSecondary)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onOpenCalibration,
                    enabled = snapshot != null,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Calibrate pumps", fontWeight = FontWeight.Bold)
                }
                if (snapshot != null && snapshot.sensorReferenceCm == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The machine won't pour until its glass sensor has measured the empty tray.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonAmber,
                    )
                }
            }
        }

        if (snapshot == null || snapshot.isSimulated) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (snapshot == null) {
                    "No machine connected — pouring is disabled until one is."
                } else {
                    "Connected to a simulated machine. Commands are logged, no liquid moves."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

/**
 * Where the machine lives. Typed in by hand on purpose: mDNS is unreliable on Android below
 * API 30, and an IP the user pinned on their router is the thing that keeps working.
 */
@Composable
private fun MachineLinkPanel(
    state: SettingsUiState,
    accent: Color,
    ledEnabled: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onTest: () -> Unit,
) {
    GlassPanel(accent = if (ledEnabled) accent else null, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Use hardware machine",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Pours and the light strip run on the Raspberry Pi instead of on screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = state.machineEnabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Obsidian,
                        checkedTrackColor = accent,
                        checkedBorderColor = accent,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.machineHost,
                    onValueChange = onHostChange,
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.42") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                    colors = machineFieldColors(accent),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = state.machinePort,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    colors = machineFieldColors(accent),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onTest, enabled = !state.isTesting) {
                    Text(if (state.isTesting) "Testing…" else "Test", color = accent)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = state.machineSummary,
                style = MaterialTheme.typography.labelSmall,
                color = state.connection.statusColor(accent),
            )
            state.testResult?.let { result ->
                Spacer(Modifier.height(4.dp))
                Text(text = result, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun machineFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    focusedLabelColor = accent,
    cursorColor = accent,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun ConnectionState.statusColor(accent: Color): Color = when (this) {
    is ConnectionState.Connected -> accent
    ConnectionState.Connecting -> NeonAmber
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    ConnectionState.Disabled -> TextSecondary
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
