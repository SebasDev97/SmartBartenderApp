package com.example.smartbartender.ui.screens.cleaning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.RunStatus
import com.example.smartbartender.domain.model.toMachineError
import com.example.smartbartender.ui.screens.machine.PumpRow
import com.example.smartbartender.ui.screens.machine.PumpSelection
import com.example.smartbartender.ui.screens.machine.pumpRows
import com.example.smartbartender.ui.screens.machine.selectedPumpsOrAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CleaningUiState(
    val connected: Boolean = false,
    /** A pour or a calibration is running, so nothing here may drive a pump. */
    val machineBusy: Boolean = false,
    /** The rack's bottle on each pump — it should be water for now. */
    val pumps: List<PumpRow> = emptyList(),
    val seconds: Int = CleaningViewModel.DEFAULT_SECONDS,
    val rounds: Int = CleaningViewModel.DEFAULT_ROUNDS,
    /** The user has said a container big enough stands under the nozzle. No sensor checks it. */
    val containerConfirmed: Boolean = false,
    /** The run in progress, or the last one — pushed by the machine, never invented here. */
    val run: CleaningRun? = null,
    val isStarting: Boolean = false,
    /** The last command the machine refused. */
    val error: MachineError? = null,
) {
    val isRunning: Boolean get() = run?.status == RunStatus.RUNNING

    /** Roughly what ends up in the container, from each pump's calibrated flow. */
    val estimatedMl: Double get() = pumps.filter { it.selected }.sumOf { it.mlPerSecond } * seconds * rounds

    val canEdit: Boolean get() = connected && !isRunning && !isStarting

    val canStart: Boolean
        get() = canEdit && !machineBusy && containerConfirmed && pumps.any { it.selected }
}

/**
 * Rinses the pump lines with warm water: each pump in turn, round after round.
 *
 * Like a pour or a calibration, the run itself lives on the machine. This class only starts
 * and stops it and renders the run the machine pushes through the snapshot. The rack is never
 * touched — the user swaps the bottles for water and back by hand.
 */
class CleaningViewModel(private val machine: BartenderMachine) : ViewModel() {

    private val _uiState = MutableStateFlow(CleaningUiState())
    val uiState = _uiState.asStateFlow()

    private val selection = PumpSelection()

    init {
        viewModelScope.launch {
            combine(machine.connection, selection.deselectedPumps, ::Pair).collect { (connection, deselected) ->
                render(connection, deselected)
            }
        }
    }

    fun togglePump(pump: Int) = selection.toggle(pump)

    fun setSeconds(seconds: Int) {
        _uiState.update { it.copy(seconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)) }
    }

    fun setRounds(rounds: Int) {
        _uiState.update { it.copy(rounds = rounds.coerceIn(MIN_ROUNDS, MAX_ROUNDS)) }
    }

    fun setContainerConfirmed(confirmed: Boolean) {
        _uiState.update { it.copy(containerConfirmed = confirmed) }
    }

    fun start() {
        val state = _uiState.value
        if (!state.canStart) return
        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true, error = null) }
            val result = machine.startCleaning(
                pumps = state.pumps.selectedPumpsOrAll(),
                seconds = state.seconds.toDouble(),
                rounds = state.rounds,
            )
            _uiState.update {
                it.copy(
                    isStarting = false,
                    // The container fills up; ask again before the next run.
                    containerConfirmed = if (result.isSuccess) false else it.containerConfirmed,
                    error = result.exceptionOrNull()?.toMachineError(),
                )
            }
        }
    }

    fun abort() {
        viewModelScope.launch {
            val result = machine.abortCleaning()
            _uiState.update { it.copy(error = result.exceptionOrNull()?.toMachineError()) }
        }
    }

    private fun render(connection: ConnectionState, deselected: Set<Int>) {
        val snapshot = connection.snapshotOrNull
        val run = snapshot?.cleaning
        _uiState.update { current ->
            current.copy(
                connected = snapshot != null,
                machineBusy = snapshot?.busyWithOtherWork(ownRunActive = run?.status == RunStatus.RUNNING) == true,
                run = run,
                pumps = snapshot.pumpRows(deselected),
            )
        }
    }

    companion object {
        const val DEFAULT_SECONDS = 10
        const val MIN_SECONDS = 5

        /** The machine refuses a single pump run over 30 s — its Arduino has no watchdog. */
        const val MAX_SECONDS = 30
        const val SECONDS_STEP = 5

        const val DEFAULT_ROUNDS = 2
        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 5

        val Factory = containerViewModelFactory { container -> CleaningViewModel(container.machine) }
    }
}
