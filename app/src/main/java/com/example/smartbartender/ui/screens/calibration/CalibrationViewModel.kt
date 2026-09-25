package com.example.smartbartender.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CalibrationResult
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CalibrationStatus
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** One pump on the calibration screen. */
data class PumpCalibrationRow(
    val pump: Int,
    /** The bottle the machine has on this pump, or null for an empty slot. */
    val bottleName: String?,
    val mlPerSecond: Double,
    val selected: Boolean,
    /** What this pump measured in the run on screen, if it got that far. */
    val result: CalibrationResult?,
)

data class CalibrationUiState(
    val connected: Boolean = false,
    /** A pour is running, so nothing here may drive a pump. */
    val machineBusy: Boolean = false,
    val referenceCm: Double? = null,
    val glassDiameterMm: Double = 58.0,
    val sensor: SensorReading? = null,
    val pumps: List<PumpCalibrationRow> = emptyList(),
    /** The run in progress, or the last one — pushed by the machine, never invented here. */
    val run: CalibrationRun? = null,
    val isMeasuringReference: Boolean = false,
    val isStarting: Boolean = false,
    val joggingPump: Int? = null,
    /** The last command the machine refused, already worded for a person. */
    val errorMessage: String? = null,
) {
    val isRunning: Boolean get() = run?.status == CalibrationStatus.RUNNING
    val canDrivePumps: Boolean get() = connected && !machineBusy && !isRunning && joggingPump == null
    val canStart: Boolean
        get() = canDrivePumps && !isStarting && referenceCm != null && pumps.any { it.selected }
}

/**
 * Drives the machine's calibration: measure the empty tray, prime the pumps, then let the
 * machine run each one into the glass and measure the rise.
 *
 * Like a pour, the run itself lives on the machine. This class only starts and stops it and
 * renders the run the machine pushes through the snapshot.
 */
class CalibrationViewModel(private val machine: BartenderMachine) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationUiState())
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
        viewModelScope.launch { pollSensor() }
    }

    fun togglePump(pump: Int) {
        deselected.update { if (pump in it) it - pump else it + pump }
    }

    fun measureReference() {
        viewModelScope.launch {
            _uiState.update { it.copy(isMeasuringReference = true, errorMessage = null) }
            val result = machine.measureReference()
            _uiState.update {
                it.copy(
                    isMeasuringReference = false,
                    sensor = result.getOrNull() ?: it.sensor,
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun jog(pump: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(joggingPump = pump, errorMessage = null) }
            // The machine answers once the pump has stopped again.
            val result = machine.jog(pump, PRIME_SECONDS)
            _uiState.update { it.copy(joggingPump = null, errorMessage = result.exceptionOrNull()?.message) }
        }
    }

    fun start() {
        val state = _uiState.value
        if (!state.canStart) return
        val pumps = state.pumps.filter { it.selected }.map { it.pump }
        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true, errorMessage = null) }
            val result = machine.startCalibration(pumps.takeIf { it.size < state.pumps.size })
            _uiState.update { it.copy(isStarting = false, errorMessage = result.exceptionOrNull()?.message) }
        }
    }

    fun abort() {
        viewModelScope.launch {
            val result = machine.abortCalibration()
            _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
        }
    }

    private fun render(connection: ConnectionState, deselected: Set<Int>) {
        val snapshot = (connection as? ConnectionState.Connected)?.snapshot
        val run = snapshot?.calibration
        _uiState.update { current ->
            current.copy(
                connected = snapshot != null,
                machineBusy = snapshot?.state == MachineRunState.BUSY && run?.status != CalibrationStatus.RUNNING,
                referenceCm = snapshot?.sensorReferenceCm,
                glassDiameterMm = snapshot?.glassDiameterMm ?: current.glassDiameterMm,
                run = run,
                pumps = snapshot?.slots.orEmpty().map { slot ->
                    PumpCalibrationRow(
                        pump = slot.pump,
                        bottleName = slot.bottleId?.let { id -> BottleCatalog.byId(id)?.displayName ?: id },
                        mlPerSecond = slot.mlPerSecond,
                        selected = slot.pump !in deselected,
                        result = run?.results?.lastOrNull { it.pump == slot.pump },
                    )
                },
                sensor = if (snapshot == null) null else current.sensor,
            )
        }
    }

    /**
     * A live reading once a second, so the user sees the glass being detected before they
     * start. Paused while the machine is measuring for itself, to keep its timing clean.
     */
    private suspend fun pollSensor() {
        while (viewModelScope.isActive) {
            val state = _uiState.value
            if (state.connected && !state.isRunning && !state.isMeasuringReference) {
                machine.readSensor().onSuccess { reading -> _uiState.update { it.copy(sensor = reading) } }
            }
            delay(SENSOR_POLL_MILLIS.milliseconds)
        }
    }

    companion object {
        /** Long enough to see liquid move up a primed tube; short enough to catch in a cup. */
        const val PRIME_SECONDS = 2.0

        private const val SENSOR_POLL_MILLIS = 1_000L

        val Factory = containerViewModelFactory { container -> CalibrationViewModel(container.machine) }
    }
}
