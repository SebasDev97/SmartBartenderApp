package com.example.smartbartender.ui.screens.cleaning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smartbartender.domain.model.CleaningPhase
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CleaningStatus
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonLime
import com.example.smartbartender.ui.theme.Obsidian
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
                text = "Connect to the machine in Settings first — cleaning runs on the machine itself.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            return@Column
        }
        if (state.machineBusy) {
            Text(
                text = "The machine is busy. Cleaning is available again once it is done.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeonCyan,
            )
            Spacer(Modifier.height(16.dp))
        }

        SectionHeader(title = "1 · Prepare", accent = accent)
        Spacer(Modifier.height(10.dp))
        PreparePanel(state = state, accent = accent, onConfirm = actions.onContainerConfirmedChange)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "2 · Pumps and timing", accent = accent)
        Spacer(Modifier.height(10.dp))
        SettingsPanel(state = state, accent = accent, actions = actions)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "3 · Rinse", accent = accent)
        Spacer(Modifier.height(10.dp))
        RunPanel(state = state, accent = accent, onStart = actions.onStart, onAbort = actions.onAbort)

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
        }
    }
}

@Composable
private fun PreparePanel(state: CleaningUiState, accent: Color, onConfirm: (Boolean) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "Swap each bottle for one of warm water. The glass sensor isn't used, " +
                    "so put a jug or bowl under the nozzle that holds everything the pumps will run.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.containerConfirmed,
                    onCheckedChange = onConfirm,
                    enabled = state.canEdit,
                    colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Obsidian),
                )
                Text(
                    text = "A container for about ${state.estimatedMl.roundedMl()} ml is under the nozzle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(state: CleaningUiState, accent: Color, actions: CleaningActions) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            state.pumps.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.selected,
                        onCheckedChange = { actions.onTogglePump(row.pump) },
                        enabled = state.canEdit,
                        colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Obsidian),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Pump ${row.pump}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = row.bottleName?.let { "Normally $it" } ?: "Normally empty",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Stepper(
                label = "Seconds per pump",
                value = "${state.seconds} s",
                accent = accent,
                enabled = state.canEdit,
                canDecrease = state.seconds > CleaningViewModel.MIN_SECONDS,
                canIncrease = state.seconds < CleaningViewModel.MAX_SECONDS,
                onDecrease = { actions.onSecondsChange(state.seconds - CleaningViewModel.SECONDS_STEP) },
                onIncrease = { actions.onSecondsChange(state.seconds + CleaningViewModel.SECONDS_STEP) },
            )
            Stepper(
                label = "Rounds",
                value = "${state.rounds}×",
                accent = accent,
                enabled = state.canEdit,
                canDecrease = state.rounds > CleaningViewModel.MIN_ROUNDS,
                canIncrease = state.rounds < CleaningViewModel.MAX_ROUNDS,
                onDecrease = { actions.onRoundsChange(state.rounds - 1) },
                onIncrease = { actions.onRoundsChange(state.rounds + 1) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The pumps run one at a time, 1 → ${state.pumps.size}, then start over for each round.",
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
                TextButton(onClick = onAbort) { Text("Stop cleaning", color = TextSecondary) }
            } else {
                if (!state.containerConfirmed) {
                    Text(
                        text = "Tick the container box in step 1 to start.",
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
                    Text(if (state.isStarting) "Starting…" else "Start cleaning", fontWeight = FontWeight.Bold)
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
private fun RunProgress(run: CleaningRun, accent: Color) {
    Text(
        text = when {
            run.currentPump == null -> "Starting"
            run.phase == CleaningPhase.PAUSING -> "Round ${run.currentRound} of ${run.rounds}"
            else -> "Round ${run.currentRound} of ${run.rounds} · pump ${run.currentPump}"
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

@Composable
private fun RunSummary(run: CleaningRun) {
    val (text, color) = when (run.status) {
        CleaningStatus.FINISHED -> run.message to NeonLime
        CleaningStatus.ABORTED -> "Stopped — put your bottles back when you're done" to TextSecondary
        else -> (run.error?.message ?: run.message) to ErrorRed
    }
    Text(text = "Last run: $text", style = MaterialTheme.typography.bodyMedium, color = color)
}

/** Up to the next 50 ml — it is an estimate of a container size, so err on the big side. */
private fun Double.roundedMl(): Int = (ceil(this / 50) * 50).toInt().coerceAtLeast(50)
