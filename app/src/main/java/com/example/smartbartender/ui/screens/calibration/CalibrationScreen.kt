package com.example.smartbartender.ui.screens.calibration

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartbartender.domain.model.CalibrationPhase
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CalibrationStatus
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonLime
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary
import java.util.Locale

/** Everything the calibration screen can ask of its ViewModel. */
data class CalibrationActions(
    val onMeasureReference: () -> Unit,
    val onTogglePump: (Int) -> Unit,
    val onJog: (Int) -> Unit,
    val onStart: () -> Unit,
    val onAbort: () -> Unit,
)

/**
 * The machine's three set-up steps, top to bottom in the order they are done: measure the
 * empty tray, prime the pumps, then calibrate them into the empty glass.
 */
@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    led: LedState,
    actions: CalibrationActions,
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
        if (!state.connected) {
            Text(
                text = "Connect to the machine in Settings first — calibration runs on the machine itself.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            return@Column
        }
        if (state.machineBusy) {
            Text(
                text = "A drink is being poured. Calibration is available again once it is done.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeonCyan,
            )
            Spacer(Modifier.height(16.dp))
        }

        SectionHeader(title = "1 · Glass sensor", accent = accent)
        Spacer(Modifier.height(10.dp))
        SensorPanel(state = state, accent = accent, onMeasure = actions.onMeasureReference)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "2 · Prime the pumps", accent = accent)
        Spacer(Modifier.height(10.dp))
        PumpsPanel(state = state, accent = accent, onToggle = actions.onTogglePump, onJog = actions.onJog)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "3 · Calibrate", accent = accent)
        Spacer(Modifier.height(10.dp))
        RunPanel(state = state, accent = accent, onStart = actions.onStart, onAbort = actions.onAbort)

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
        }
    }
}

@Composable
private fun SensorPanel(state: CalibrationUiState, accent: Color, onMeasure: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            InfoRow(
                label = "Empty tray",
                value = state.referenceCm?.let { "${it.cm()} cm" } ?: "Not measured",
                valueColor = if (state.referenceCm == null) ErrorRed else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            val sensor = state.sensor
            InfoRow(
                label = "Sensor now",
                value = when {
                    sensor == null -> "—"
                    sensor.distanceCm == null -> "No echo"
                    sensor.glassPresent -> "${sensor.distanceCm.cm()} cm · glass detected"
                    else -> "${sensor.distanceCm.cm()} cm"
                },
                valueColor = if (sensor?.glassPresent == true) NeonLime else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Take every glass off the tray, then measure. Every glass is detected against " +
                    "this distance, so measure again if the sensor or the tray moves. The machine " +
                    "won't pour until it has one.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onMeasure,
                enabled = state.canDrivePumps && !state.isMeasuringReference,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (state.isMeasuringReference) "Measuring…" else "Measure empty tray",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PumpsPanel(
    state: CalibrationUiState,
    accent: Color,
    onToggle: (Int) -> Unit,
    onJog: (Int) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "A dry tube pours far too little on its first run. Hold a cup under the nozzle " +
                    "and test each pump until liquid flows steadily. Untick a pump to leave it out.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            state.pumps.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.selected,
                        onCheckedChange = { onToggle(row.pump) },
                        enabled = !state.isRunning,
                        colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Obsidian),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Pump ${row.pump} · ${row.bottleName ?: "empty"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${row.mlPerSecond.rate()} ml/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.result != null) NeonLime else TextSecondary,
                        )
                    }
                    TextButton(onClick = { onJog(row.pump) }, enabled = state.canDrivePumps) {
                        Text(
                            text = if (state.joggingPump == row.pump) {
                                "Running…"
                            } else {
                                "Test ${CalibrationViewModel.PRIME_SECONDS.toInt()} s"
                            },
                            color = if (state.canDrivePumps) accent else SteelOutline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunPanel(state: CalibrationUiState, accent: Color, onStart: () -> Unit, onAbort: () -> Unit) {
    GlassPanel(accent = if (state.isRunning) accent else null, modifier = Modifier.fillMaxWidth()) {
        Column {
            val run = state.run
            if (run != null && state.isRunning) {
                RunProgress(run = run, accent = accent)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAbort) { Text("Stop calibration", color = TextSecondary) }
            } else {
                Text(
                    text = "Place the empty ${state.glassDiameterMm.toInt()} mm glass under the nozzle " +
                        "and start. Each selected pump runs a few seconds into it; the sensor measures " +
                        "how far the level rose. The new rates are used straight away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                if (state.referenceCm == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Measure the empty tray first (step 1).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (state.isStarting) "Starting…" else "Start calibration", fontWeight = FontWeight.Bold)
                }
                run?.let {
                    Spacer(Modifier.height(16.dp))
                    RunSummary(run = it)
                }
            }
        }
    }
}

@Composable
private fun RunProgress(run: CalibrationRun, accent: Color) {
    val done = run.results.size
    Text(
        text = when (run.phase) {
            CalibrationPhase.WAITING_GLASS -> "Waiting for the empty glass"
            else -> run.currentPump?.let { "Pump $it of ${run.pumps.size}" } ?: "Calibrating"
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = run.message, style = MaterialTheme.typography.bodyMedium, color = accent)
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { if (run.pumps.isEmpty()) 0f else done.toFloat() / run.pumps.size },
        modifier = Modifier.fillMaxWidth(),
        color = accent,
        trackColor = SteelOutline,
    )
    if (run.results.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ResultRows(run)
    }
}

@Composable
private fun RunSummary(run: CalibrationRun) {
    val (text, color) = when (run.status) {
        CalibrationStatus.FINISHED -> run.message to NeonLime
        CalibrationStatus.ABORTED -> "Stopped" to TextSecondary
        else -> (run.error?.message ?: run.message) to ErrorRed
    }
    Text(text = "Last run: $text", style = MaterialTheme.typography.bodyMedium, color = color)
    if (run.results.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        ResultRows(run)
    }
}

@Composable
private fun ResultRows(run: CalibrationRun) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        run.results.forEach { result ->
            InfoRow(
                label = "Pump ${result.pump}",
                value = "${result.mlPerSecond.rate()} ml/s  ·  ${result.volumeMl.toInt()} ml in " +
                    String.format(Locale.US, "%.1f", result.seconds) + " s",
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

private fun Double.cm(): String = String.format(Locale.US, "%.1f", this)

private fun Double.rate(): String = String.format(Locale.US, "%.2f", this)
