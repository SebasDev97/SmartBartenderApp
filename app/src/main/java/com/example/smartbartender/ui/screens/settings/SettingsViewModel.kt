package com.example.smartbartender.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.di.containerViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val ledShowEnabled: Boolean = true,
    val loadedBottleCount: Int = 0,
    val totalBottleCount: Int = BottleCatalog.MAX_SLOTS,
)

class SettingsViewModel(
    private val preferences: BartenderPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.ledShowEnabled, preferences.loadedBottleIds) { led, ids -> led to ids }
                .collect { (led, ids) ->
                    _uiState.update { it.copy(ledShowEnabled = led, loadedBottleCount = ids.size) }
                }
        }
    }

    fun setLedShowEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLedShowEnabled(enabled) }
    }

    companion object {
        val Factory = containerViewModelFactory { container ->
            SettingsViewModel(container.preferences)
        }
    }
}
