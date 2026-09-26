package com.example.smartbartender.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.RunStatus
import com.example.smartbartender.domain.model.SensorReading
import com.example.smartbartender.domain.model.toMachineError
import com.example.smartbartender.ui.screens.machine.PumpRow
import com.example.smartbartender.ui.screens.machine.PumpSelection
import com.example.smartbartender.ui.screens.machine.pumpRows
import com.example.smartbartender.ui.screens.machine.selectedPumpsOrAll
import com.example.smartbartender.util.isObserved
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class CalibrationUiState(
    val connected: Boolean = false,
    /** A pour or a cleaning is running, so nothing here may drive a pump. */
    val machineBusy: Boolean = false,
    val referenceCm: Double? = null,
    val glassDiameterMm: Double = MachineSnapshot.DEFAULT_GLASS_DIAMETER_MM,
    val sensor: SensorReading? = null,
    val pumps: List<PumpRow> = emptyList(),
    /** The run in progress, or the last one — pushed by the machine, never invented here. */
    val run: CalibrationRun? = null,
    val isMeasuringReference: Boolean = false,
    val isStarting: Boolean = false,
    val joggingPump: Int? = null,
    /** The last command the machine refused. */
    val error: MachineError? = null,
) {
    val isRunning: Boolean get() = run?.status == RunStatus.RUNNING
    val canDrivePumps: Boolean get() = connected && !machineBusy && !isRunning && joggingPump == null
    val canStart: Boolean
        get() = canDrivePumps && !isStarting && referenceCm != null && pumps.any { it.selected }

    /** Pumps the run on screen has already measured. */
    val calibratedPumps: Set<Int> get() = run?.results?.mapTo(HashSet()) { it.pump }.orEmpty()
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

    private val selection = PumpSelection()

    init {
        viewModelScope.launch {
            combine(machine.connection, selection.deselectedPumps, ::Pair).collect { (connection, deselected) ->
                render(connection, deselected)
            }
        }
        // Only while the screen is on show: a phone left on it in the background must not keep
        // asking the Pi for readings.
        viewModelScope.launch {
            _uiState.isObserved().collectLatest { onShow -> if (onShow) pollSensor() }
        }
    }

    fun togglePump(pump: Int) = selection.toggle(pump)

    fun measureReference() {
        viewModelScope.launch {
            _uiState.update { it.copy(isMeasuringReference = true, error = null) }
            val result = machine.measureReference()
            _uiState.update {
                it.copy(
                    isMeasuringReference = false,
                    sensor = result.getOrNull() ?: it.sensor,
                    error = result.exceptionOrNull()?.toMachineError(),
                )
            }
        }
    }

    fun jog(pump: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(joggingPump = pump, error = null) }
            // The machine answers once the pump has stopped again.
            val result = machine.jog(pump, PRIME_SECONDS)
            _uiState.update { it.copy(joggingPump = null, error = result.exceptionOrNull()?.toMachineError()) }
        }
    }

    fun start() {
        val state = _uiState.value
        if (!state.canStart) return
        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true, error = null) }
            val result = machine.startCalibration(state.pumps.selectedPumpsOrAll())
            _uiState.update { it.copy(isStarting = false, error = result.exceptionOrNull()?.toMachineError()) }
        }
    }

    fun abort() {
        viewModelScope.launch {
            val result = machine.abortCalibration()
            _uiState.update { it.copy(error = result.exceptionOrNull()?.toMachineError()) }
        }
    }

    private fun render(connection: ConnectionState, deselected: Set<Int>) {
        val snapshot = connection.snapshotOrNull
        val run = snapshot?.calibration
        _uiState.update { current ->
            current.copy(
                connected = snapshot != null,
                machineBusy = snapshot?.busyWithOtherWork(ownRunActive = run?.status == RunStatus.RUNNING) == true,
                referenceCm = snapshot?.sensorReferenceCm,
                glassDiameterMm = snapshot?.glassDiameterMm ?: current.glassDiameterMm,
                run = run,
                pumps = snapshot.pumpRows(deselected),
                sensor = if (snapshot == null) null else current.sensor,
            )
        }
    }

    /**
     * A live reading once a second, so the user sees the glass being detected before they
     * start. Paused while the machine is measuring for itself, to keep its timing clean, and
     * stopped by cancellation when the screen goes out of view.
     */
    private suspend fun pollSensor() {
        while (true) {
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
