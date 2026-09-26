package com.example.smartbartender.ui.screens.cleaning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.CleaningPhase
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.screens.machine.AccentCheckbox
import com.example.smartbartender.ui.screens.machine.LastRunSummary
import com.example.smartbartender.ui.screens.machine.MachineRunPage
import com.example.smartbartender.ui.screens.machine.PumpRow
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SmartBartenderTheme
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary
import kotlin.math.ceil

/** Everything the cleaning screen can ask of its ViewModel. */
data class CleaningActions(
    val onTogglePump: (Int) -> Unit,
    val onSecondsChange: (Int) -> Unit,
    val onRoundsChange: (Int) -> Unit,
    val onContainerConfirmedChange: (Boolean) -> Unit,
    val onStart: () -> Unit,
    val onAbort: () -> Unit,
)

/** Rinse the lines with warm water: prepare, pick pumps and timing, then run. */
@Composable
fun CleaningScreen(
    state: CleaningUiState,
    led: LedState,
    actions: CleaningActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = led.primary
    MachineRunPage(
        connected = state.connected,
        busy = state.machineBusy,
        disconnectedMessage = stringResource(R.string.cleaning_disconnected),
        busyMessage = stringResource(R.string.cleaning_busy),
        error = state.error,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        SectionHeader(title = stringResource(R.string.cleaning_step_prepare), accent = accent)
        Spacer(Modifier.height(10.dp))
        PreparePanel(state = state, accent = accent, onConfirm = actions.onContainerConfirmedChange)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.cleaning_step_pumps), accent = accent)
        Spacer(Modifier.height(10.dp))
        PumpTimingPanel(state = state, accent = accent, actions = actions)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.cleaning_step_rinse), accent = accent)
        Spacer(Modifier.height(10.dp))
        RunPanel(state = state, accent = accent, onStart = actions.onStart, onAbort = actions.onAbort)
    }
}

@Composable
private fun PreparePanel(state: CleaningUiState, accent: Color, onConfirm: (Boolean) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = stringResource(R.string.cleaning_prepare_help),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentCheckbox(
                    checked = state.containerConfirmed,
                    onCheckedChange = onConfirm,
                    enabled = state.canEdit,
                    accent = accent,
                )
                Text(
                    text = stringResource(R.string.cleaning_container_confirm, state.estimatedMl.roundedMl()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PumpTimingPanel(state: CleaningUiState, accent: Color, actions: CleaningActions) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            state.pumps.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentCheckbox(
                        checked = row.selected,
                        onCheckedChange = { actions.onTogglePump(row.pump) },
                        enabled = state.canEdit,
                        accent = accent,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.cleaning_pump, row.pump),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = row.bottleName?.let { stringResource(R.string.cleaning_normally, it) }
                                ?: stringResource(R.string.cleaning_normally_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Stepper(
                label = stringResource(R.string.cleaning_seconds_per_pump),
                value = stringResource(R.string.duration_seconds, state.seconds),
                accent = accent,
                enabled = state.canEdit,
                canDecrease = state.seconds > CleaningViewModel.MIN_SECONDS,
                canIncrease = state.seconds < CleaningViewModel.MAX_SECONDS,
                onDecrease = { actions.onSecondsChange(state.seconds - CleaningViewModel.SECONDS_STEP) },
                onIncrease = { actions.onSecondsChange(state.seconds + CleaningViewModel.SECONDS_STEP) },
            )
            Stepper(
                label = stringResource(R.string.cleaning_rounds),
                value = stringResource(R.string.cleaning_rounds_value, state.rounds),
                accent = accent,
                enabled = state.canEdit,
                canDecrease = state.rounds > CleaningViewModel.MIN_ROUNDS,
                canIncrease = state.rounds < CleaningViewModel.MAX_ROUNDS,
                onDecrease = { actions.onRoundsChange(state.rounds - 1) },
                onIncrease = { actions.onRoundsChange(state.rounds + 1) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.cleaning_order_help, state.pumps.size),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    accent: Color,
    enabled: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDecrease, enabled = enabled && canDecrease) {
            Text("−", color = if (enabled && canDecrease) accent else SteelOutline, fontWeight = FontWeight.Bold)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        TextButton(onClick = onIncrease, enabled = enabled && canIncrease) {
            Text("+", color = if (enabled && canIncrease) accent else SteelOutline, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RunPanel(state: CleaningUiState, accent: Color, onStart: () -> Unit, onAbort: () -> Unit) {
    GlassPanel(accent = if (state.isRunning) accent else null, modifier = Modifier.fillMaxWidth()) {
        Column {
            val run = state.run
            if (run != null && state.isRunning) {
                RunProgress(run = run, accent = accent)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAbort) { Text(stringResource(R.string.cleaning_stop), color = TextSecondary) }
            } else {
                if (!state.containerConfirmed) {
                    Text(
                        text = stringResource(R.string.cleaning_tick_container),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Obsidian),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        stringResource(if (state.isStarting) R.string.run_starting else R.string.cleaning_start),
                        fontWeight = FontWeight.Bold,
                    )
                }
                run?.let {
                    Spacer(Modifier.height(16.dp))
                    LastRunSummary(
                        status = it.status,
                        message = it.message,
                        fault = it.error,
                        stoppedText = stringResource(R.string.cleaning_stopped),
                    )
                }
            }
        }
    }
}

@Composable
private fun RunProgress(run: CleaningRun, accent: Color) {
    Text(
        text = when {
            run.currentPump == null -> stringResource(R.string.cleaning_progress_starting)
            run.phase == CleaningPhase.PAUSING -> stringResource(R.string.cleaning_round, run.currentRound ?: 0, run.rounds)
            else -> stringResource(R.string.cleaning_round_pump, run.currentRound ?: 0, run.rounds, run.currentPump)
        },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = run.message, style = MaterialTheme.typography.bodyMedium, color = accent)
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { run.progress },
        modifier = Modifier.fillMaxWidth(),
        color = accent,
        trackColor = SteelOutline,
    )
}

/** Up to the next 50 ml — it is an estimate of a container size, so err on the big side. */
private fun Double.roundedMl(): Int = (ceil(this / 50) * 50).toInt().coerceAtLeast(50)

@Preview
@Composable
private fun CleaningScreenPreview() {
    SmartBartenderTheme {
        CleaningScreen(
            state = CleaningUiState(
                connected = true,
                pumps = listOf(
                    PumpRow(pump = 1, bottleName = "Vodka", mlPerSecond = 12.5, selected = true),
                    PumpRow(pump = 2, bottleName = null, mlPerSecond = 12.5, selected = false),
                ),
            ),
            led = LedState.Off,
            actions = CleaningActions({}, {}, {}, {}, {}, {}),
        )
    }
}
