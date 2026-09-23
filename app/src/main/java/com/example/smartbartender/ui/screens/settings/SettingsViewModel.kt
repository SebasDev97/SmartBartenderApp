package com.example.smartbartender.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.hardware.HttpBartenderMachine
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val ledShowEnabled: Boolean = true,
    val loadedBottleCount: Int = 0,
    val totalBottleCount: Int = BottleCatalog.MAX_SLOTS,
    val machineHost: String = "",
    val machinePort: String = MachineAddress.DEFAULT_PORT.toString(),
    val machineEnabled: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disabled,
    /** Result of the Test button, cleared when the address is edited. */
    val testResult: String? = null,
    val isTesting: Boolean = false,
    /** True while the user is typing an address that hasn't been saved yet. */
    val isEditing: Boolean = false,
) {
    val machineSummary: String
        get() = when (val state = connection) {
            ConnectionState.Disabled -> "Not connected"
            ConnectionState.Connecting -> "Connecting…"
            is ConnectionState.Failed -> state.message
            is ConnectionState.Connected -> with(state.snapshot) {
                "$name · ${if (isSimulated) "simulated" else "hardware"} · $pumpCount pumps"
            }
        }

    val hardwareLinkLabel: String
        get() = when (val state = connection) {
            is ConnectionState.Connected -> if (state.snapshot.isSimulated) "Simulated machine" else "Live"
            ConnectionState.Connecting -> "Connecting…"
            else -> "Not connected"
        }
}

class SettingsViewModel(
    private val preferences: BartenderPreferences,
    private val machine: BartenderMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferences.ledShowEnabled,
                preferences.loadedBottleIds,
                preferences.machineAddress,
                machine.connection,
            ) { led, ids, address, connection ->
                SettingsUiState(
                    ledShowEnabled = led,
                    loadedBottleCount = ids.size,
                    machineHost = address.host,
                    machinePort = address.port.toString(),
                    machineEnabled = address.enabled,
                    connection = connection,
                )
            }.collect { fresh ->
                // Keep whatever the user is typing and the last test result; everything else
                // is owned by DataStore and the live connection.
                _uiState.update { current ->
                    fresh.copy(
                        machineHost = if (current.isEditing) current.machineHost else fresh.machineHost,
                        machinePort = if (current.isEditing) current.machinePort else fresh.machinePort,
                        testResult = current.testResult,
                        isTesting = current.isTesting,
                        isEditing = current.isEditing,
                    )
                }
            }
        }
    }

    fun setLedShowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLedShowEnabled(enabled)
            // The preference stays the source of truth for the on-screen strip; the machine
            // is simply told to match.
            machine.setLed(enabled, HttpBartenderMachine.LED_CYCLE_MILLIS)
        }
    }

    fun setMachineHost(host: String) {
        _uiState.update { it.copy(machineHost = host, testResult = null, isEditing = true) }
    }

    fun setMachinePort(port: String) {
        _uiState.update { it.copy(machinePort = port.filter(Char::isDigit), testResult = null, isEditing = true) }
    }

    fun setMachineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val state = _uiState.value
            preferences.setMachineAddress(state.machineHost, state.port, enabled)
            _uiState.update { it.copy(isEditing = false) }
        }
    }

    /** Saves the address and lets the connection loop pick it up. */
    fun connect() {
        viewModelScope.launch {
            val state = _uiState.value
            preferences.setMachineAddress(state.machineHost, state.port, enabled = true)
            _uiState.update { it.copy(isEditing = false, testResult = null) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val result = machine.testConnection(state.machineHost, state.port)
            _uiState.update {
                it.copy(
                    isTesting = false,
                    testResult = result.fold(
                        onSuccess = { snapshot ->
                            "Found ${snapshot.name} (${snapshot.backend}, firmware ${snapshot.firmware})"
                        },
                        onFailure = { error -> error.message ?: "No answer from that address" },
                    ),
                )
            }
        }
    }

    companion object {
        val Factory = containerViewModelFactory { container ->
            SettingsViewModel(container.preferences, container.machine)
        }
    }
}

private val SettingsUiState.port: Int
    get() = machinePort.toIntOrNull() ?: MachineAddress.DEFAULT_PORT
