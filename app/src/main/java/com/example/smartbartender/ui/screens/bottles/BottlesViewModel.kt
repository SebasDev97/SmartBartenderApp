package com.example.smartbartender.ui.screens.bottles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.BottleCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class BottlesUiState(
    val loadedBottleIds: Set<String> = emptySet(),
    /** Slot 1..MAX_SLOTS, null where the slot is empty. Assigned by the ViewModel. */
    val slots: List<Bottle?> = List(BottleCatalog.MAX_SLOTS) { null },
    /** Transient feedback, e.g. when the rack is full. */
    val notice: String? = null,
) {
    val sections: List<Pair<BottleCategory, List<Bottle>>> =
        BottleCatalog.bottles.groupBy { it.category }.toList()

    val loadedCount: Int get() = loadedBottleIds.size
    val isFull: Boolean get() = loadedCount >= BottleCatalog.MAX_SLOTS

    fun isLoaded(bottle: Bottle): Boolean = bottle.id in loadedBottleIds
}

/**
 * Owns the machine's four bottle slots. Every change is written straight to DataStore, and
 * loading a fifth bottle is refused rather than silently swapping one out — the app mirrors
 * what someone standing at the machine would have to do.
 */
class BottlesViewModel(
    private val preferences: BartenderPreferences,
    private val repository: CocktailRepository,
    private val machine: BartenderMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BottlesUiState())
    val uiState = _uiState.asStateFlow()

    private var noticeJob: Job? = null

    init {
        viewModelScope.launch {
            // Slot order is persisted now rather than held in memory: on a real machine,
            // slot 2 is a pump with a specific bottle in it, and forgetting which across a
            // restart means pouring the wrong liquid.
            preferences.slots.collect { slots ->
                _uiState.update {
                    it.copy(
                        loadedBottleIds = slots.filterNotNull().toSet(),
                        slots = slots.map { id -> id?.let(BottleCatalog::byId) },
                    )
                }
                machine.pushSlots(slots)
            }
        }
    }

    fun toggleBottle(bottle: Bottle) {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isLoaded(bottle) && state.isFull) {
                showNotice("All ${BottleCatalog.MAX_SLOTS} slots are full — eject a bottle first.")
                return@launch
            }
            preferences.setBottleLoaded(bottle.id, loaded = !state.isLoaded(bottle))
            repository.invalidateAvailability()
        }
    }

    /** Ejects the bottle sitting in [slotIndex], if any. */
    fun ejectSlot(slotIndex: Int) {
        val bottle = _uiState.value.slots.getOrNull(slotIndex) ?: return
        toggleBottle(bottle)
    }

    fun ejectAll() {
        viewModelScope.launch {
            preferences.setLoadedBottles(emptySet())
            repository.invalidateAvailability()
        }
    }

    fun loadDefaults() {
        viewModelScope.launch {
            preferences.setLoadedBottles(BottleCatalog.defaultSelection)
            repository.invalidateAvailability()
        }
    }

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _uiState.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_DURATION_MILLIS.milliseconds)
            _uiState.update { it.copy(notice = null) }
        }
    }

    companion object {
        private const val NOTICE_DURATION_MILLIS = 2800L

        val Factory = containerViewModelFactory { container ->
            BottlesViewModel(container.preferences, container.repository, container.machine)
        }
    }
}
