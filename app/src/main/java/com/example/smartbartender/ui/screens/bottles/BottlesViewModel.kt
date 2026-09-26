package com.example.smartbartender.ui.screens.bottles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class BottlesUiState(
    val loadedBottleIds: Set<String> = emptySet(),
    /** Slot 1..MAX_SLOTS, null where the slot is empty. */
    val slots: List<Bottle?> = List(BottleCatalog.MAX_SLOTS) { null },
    /** Briefly true after a bottle was refused because every slot is taken. */
    val showRackFullNotice: Boolean = false,
) {
    val loadedCount: Int get() = loadedBottleIds.size
    val isFull: Boolean get() = loadedCount >= BottleCatalog.MAX_SLOTS

    fun isLoaded(bottle: Bottle): Boolean = bottle.id in loadedBottleIds
}

/**
 * Owns the machine's bottle slots. Every change is written straight to the [RackStore], and
 * loading into a full rack is refused rather than silently swapping a bottle out — the app
 * mirrors what someone standing at the machine would have to do. Telling the machine is
 * [com.example.smartbartender.data.hardware.RackSync]'s job, not this screen's.
 */
class BottlesViewModel(private val rack: RackStore) : ViewModel() {

    private val _uiState = MutableStateFlow(BottlesUiState())
    val uiState = _uiState.asStateFlow()

    private var noticeJob: Job? = null

    init {
        viewModelScope.launch {
            rack.slots.collect { slots ->
                _uiState.update {
                    it.copy(
                        loadedBottleIds = slots.filterNotNull().toSet(),
                        slots = slots.map { id -> id?.let(BottleCatalog::byId) },
                    )
                }
            }
        }
    }

    fun toggleBottle(bottle: Bottle) {
        val loading = !_uiState.value.isLoaded(bottle)
        viewModelScope.launch {
            val changed = rack.setBottleLoaded(bottle.id, loaded = loading)
            if (loading && !changed) showRackFullNotice()
        }
    }

    /** Ejects the bottle sitting in [slotIndex], if any. */
    fun ejectSlot(slotIndex: Int) {
        val bottle = _uiState.value.slots.getOrNull(slotIndex) ?: return
        toggleBottle(bottle)
    }

    fun ejectAll() {
        viewModelScope.launch { rack.setLoadedBottles(emptySet()) }
    }

    fun loadDefaults() {
        viewModelScope.launch { rack.setLoadedBottles(BottleCatalog.defaultSelection) }
    }

    private fun showRackFullNotice() {
        noticeJob?.cancel()
        _uiState.update { it.copy(showRackFullNotice = true) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_DURATION_MILLIS.milliseconds)
            _uiState.update { it.copy(showRackFullNotice = false) }
        }
    }

    companion object {
        private const val NOTICE_DURATION_MILLIS = 2800L

        val Factory = containerViewModelFactory { container -> BottlesViewModel(container.rack) }
    }
}
