package com.example.smartbartender.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.PourStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: PourStats? = null,
    val showResetConfirm: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && (stats == null || stats.isEmpty)
}

/** Turns the pour history into [PourStats]. All the arithmetic lives in [PourStats.compute]. */
class StatsViewModel(
    private val preferences: BartenderPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.pourHistory.collect { records ->
                val stats = PourStats.compute(records, nowMs = clock())
                _uiState.update { it.copy(isLoading = false, stats = stats) }
            }
        }
    }

    fun requestReset() = _uiState.update { it.copy(showResetConfirm = true) }

    fun dismissReset() = _uiState.update { it.copy(showResetConfirm = false) }

    fun confirmReset() {
        _uiState.update { it.copy(showResetConfirm = false) }
        viewModelScope.launch { preferences.clearPourHistory() }
    }

    companion object {
        val Factory = containerViewModelFactory { container -> StatsViewModel(container.preferences) }
    }
}
