package com.example.smartbartender.ui.screens.cleaning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CleaningStatus
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One pump on the cleaning screen. */
data class CleaningPumpRow(
    val pump: Int,
    /** The bottle the rack lists on this pump — it should be water for now. Null for an empty slot. */
    val bottleName: String?,
    val mlPerSecond: Double,
    val selected: Boolean,
)

data class CleaningUiState(
    val connected: Boolean = false,
    /** A pour or a calibration is running, so nothing here may drive a pump. */
    val machineBusy: Boolean = false,
    val pumps: List<CleaningPumpRow> = emptyList(),
    val seconds: Int = CleaningViewModel.DEFAULT_SECONDS,
    val rounds: Int = CleaningViewModel.DEFAULT_ROUNDS,
    /** The user has said a container big enough stands under the nozzle. No sensor checks it. */
    val containerConfirmed: Boolean = false,
    /** The run in progress, or the last one — pushed by the machine, never invented here. */
    val run: CleaningRun? = null,
    val isStarting: Boolean = false,
    /** The last command the machine refused, already worded for a person. */
    val errorMessage: String? = null,
) {
    val isRunning: Boolean get() = run?.status == CleaningStatus.RUNNING

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

    /** Pumps the user has unticked. Unticked rather than ticked, so a new pump starts selected. */
    private val deselected = MutableStateFlow<Set<Int>>(emptySet())

    init {
        viewModelScope.launch {
            machine.connection.collect { connection -> render(connection, deselected.value) }
        }
        viewModelScope.launch {
            deselected.collect { render(machine.connection.value, it) }
        }
    }

    fun togglePump(pump: Int) {
        deselected.update { if (pump in it) it - pump else it + pump }
    }

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
        val pumps = state.pumps.filter { it.selected }.map { it.pump }
        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true, errorMessage = null) }
            val result = machine.startCleaning(
                pumps = pumps.takeIf { it.size < state.pumps.size },
                seconds = state.seconds.toDouble(),
                rounds = state.rounds,
            )
            _uiState.update {
                it.copy(
                    isStarting = false,
                    // The container fills up; ask again before the next run.
                    containerConfirmed = if (result.isSuccess) false else it.containerConfirmed,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun abort() {
        viewModelScope.launch {
            val result = machine.abortCleaning()
            _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
        }
    }

    private fun render(connection: ConnectionState, deselected: Set<Int>) {
        val snapshot = (connection as? ConnectionState.Connected)?.snapshot
        val run = snapshot?.cleaning
        _uiState.update { current ->
            current.copy(
                connected = snapshot != null,
                machineBusy = snapshot?.state == MachineRunState.BUSY && run?.status != CleaningStatus.RUNNING,
                run = run,
                pumps = snapshot?.slots.orEmpty().map { slot ->
                    CleaningPumpRow(
                        pump = slot.pump,
                        bottleName = slot.bottleId?.let { id -> BottleCatalog.byId(id)?.displayName ?: id },
                        mlPerSecond = slot.mlPerSecond,
                        selected = slot.pump !in deselected,
                    )
                },
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
