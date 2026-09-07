package com.example.smartbartender.ui.screens.available

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.MakeableCocktail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AvailableUiState(
    val isLoading: Boolean = true,
    /** False until DataStore has reported the rack, so we never flash "no bottles". */
    val inventoryKnown: Boolean = false,
    val loadedBottles: List<Bottle> = emptyList(),
    val canMakeNow: List<MakeableCocktail> = emptyList(),
    val almost: List<MakeableCocktail> = emptyList(),
    val errorMessage: String? = null,
) {
    val hasBottles: Boolean get() = loadedBottles.isNotEmpty()
    val isEmptyResult: Boolean get() = canMakeNow.isEmpty() && almost.isEmpty()
}

/**
 * Recomputes the makeable list whenever the loaded bottles change. The repository caches
 * both the recipe catalog and the last result, so re-entering this tab costs nothing.
 */
class AvailableViewModel(
    private val repository: CocktailRepository,
    preferences: BartenderPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AvailableUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.loadedBottleIds.distinctUntilChanged().collect { ids ->
                val bottles = ids.mapNotNull(BottleCatalog::byId)
                _uiState.update { it.copy(loadedBottles = bottles, inventoryKnown = true) }
                load(bottles)
            }
        }
    }

    fun retry() = load(_uiState.value.loadedBottles)

    private fun load(bottles: List<Bottle>) {
        loadJob?.cancel()
        if (bottles.isEmpty()) {
            _uiState.update {
                it.copy(isLoading = false, canMakeNow = emptyList(), almost = emptyList(), errorMessage = null)
            }
            return
        }
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.findMakeable(bottles) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            canMakeNow = result.canMakeNow,
                            almost = result.almost,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.userMessage(),
                        )
                    }
                }
        }
    }

    companion object {
        val Factory = containerViewModelFactory { container ->
            AvailableViewModel(container.repository, container.preferences)
        }
    }
}

/** Turns any failure into something a guest standing at the machine can act on. */
fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "No internet connection. Check your network and try again."
    is java.net.SocketTimeoutException -> "TheCocktailDB took too long to answer."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong while contacting TheCocktailDB."
}
