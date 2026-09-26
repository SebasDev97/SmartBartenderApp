package com.example.smartbartender.ui.screens.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.CalibrationPhase
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.InfoRow
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.screens.machine.AccentCheckbox
import com.example.smartbartender.ui.screens.machine.LastRunSummary
import com.example.smartbartender.ui.screens.machine.MachineRunPage
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonLime
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

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
    val accent = led.primary
    MachineRunPage(
        connected = state.connected,
        busy = state.machineBusy,
        disconnectedMessage = stringResource(R.string.calibration_disconnected),
        busyMessage = stringResource(R.string.calibration_busy),
        error = state.error,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SectionHeader(title = stringResource(R.string.calibration_step_sensor), accent = accent)
        Spacer(Modifier.height(10.dp))
        SensorPanel(state = state, accent = accent, onMeasure = actions.onMeasureReference)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.calibration_step_prime), accent = accent)
        Spacer(Modifier.height(10.dp))
        PumpsPanel(state = state, accent = accent, onToggle = actions.onTogglePump, onJog = actions.onJog)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.calibration_step_calibrate), accent = accent)
        Spacer(Modifier.height(10.dp))
        RunPanel(state = state, accent = accent, onStart = actions.onStart, onAbort = actions.onAbort)
    }
}

@Composable
private fun SensorPanel(state: CalibrationUiState, accent: Color, onMeasure: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            InfoRow(
                label = stringResource(R.string.calibration_empty_tray),
                value = state.referenceCm?.let { stringResource(R.string.distance_cm, it) }
                    ?: stringResource(R.string.calibration_not_measured),
                valueColor = if (state.referenceCm == null) ErrorRed else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            val sensor = state.sensor
            InfoRow(
                label = stringResource(R.string.calibration_sensor_now),
                value = when {
                    sensor == null -> stringResource(R.string.calibration_no_reading)
                    sensor.distanceCm == null -> stringResource(R.string.calibration_no_echo)
                    sensor.glassPresent -> stringResource(R.string.calibration_glass_detected, sensor.distanceCm)
                    else -> stringResource(R.string.distance_cm, sensor.distanceCm)
                },
                valueColor = if (sensor?.glassPresent == true) NeonLime else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.calibration_sensor_help),
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
                    text = stringResource(
                        if (state.isMeasuringReference) R.string.calibration_measuring else R.string.calibration_measure,
                    ),
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
                text = stringResource(R.string.calibration_prime_help),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            state.pumps.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentCheckbox(
                        checked = row.selected,
                        onCheckedChange = { onToggle(row.pump) },
                        enabled = !state.isRunning,
                        accent = accent,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.calibration_pump,
                                row.pump,
                                row.bottleName ?: stringResource(R.string.calibration_pump_empty),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.flow_rate, row.mlPerSecond),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.pump in state.calibratedPumps) NeonLime else TextSecondary,
                        )
                    }
                    TextButton(onClick = { onJog(row.pump) }, enabled = state.canDrivePumps) {
                        Text(
                            text = if (state.joggingPump == row.pump) {
                                stringResource(R.string.calibration_running)
                            } else {
                                stringResource(R.string.calibration_test_pump, CalibrationViewModel.PRIME_SECONDS.toInt())
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
                TextButton(onClick = onAbort) {
                    Text(stringResource(R.string.calibration_stop), color = TextSecondary)
                }
            } else {
                Text(
                    text = stringResource(R.string.calibration_run_help, state.glassDiameterMm.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                if (state.referenceCm == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calibration_measure_first),
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
                    Text(
                        stringResource(if (state.isStarting) R.string.run_starting else R.string.calibration_start),
                        fontWeight = FontWeight.Bold,
                    )
                }
                run?.let {
                    Spacer(Modifier.height(16.dp))
                    LastRunSummary(
                        status = it.status,
                        message = it.message,
                        fault = it.error,
                        stoppedText = stringResource(R.string.run_stopped),
                    )
                    if (it.results.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        ResultRows(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunProgress(run: CalibrationRun, accent: Color) {
    Text(
        text = when {
            run.phase == CalibrationPhase.WAITING_GLASS -> stringResource(R.string.calibration_waiting_glass)
            run.currentPump != null -> stringResource(R.string.calibration_pump_of, run.currentPump, run.pumps.size)
            else -> stringResource(R.string.calibration_calibrating)
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = run.message, style = MaterialTheme.typography.bodyMedium, color = accent)
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { if (run.pumps.isEmpty()) 0f else run.results.size.toFloat() / run.pumps.size },
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
private fun ResultRows(run: CalibrationRun) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        run.results.forEach { result ->
            InfoRow(
                label = stringResource(R.string.calibration_result_pump, result.pump),
                value = stringResource(
                    R.string.calibration_result,
                    result.mlPerSecond,
                    result.volumeMl.toInt(),
                    result.seconds,
                ),
            )
        }
    }
}
