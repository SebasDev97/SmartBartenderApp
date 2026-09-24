package com.example.smartbartender.ui.screens.available

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.MakeableCocktail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AvailableUiState(
    val isLoading: Boolean = true,
    /** False until DataStore has reported the rack, so we never flash "no bottles". */
    val inventoryKnown: Boolean = false,
    val loadedBottles: List<Bottle> = emptyList(),
    /** Recipes from TheCocktailDB, as scored by the repository. */
    val recipes: List<MakeableCocktail> = emptyList(),
    /** The user's own drinks, scored on the phone. Kept apart so an edit never refetches. */
    val customDrinks: List<MakeableCocktail> = emptyList(),
    val errorMessage: String? = null,
) {
    /** The user's own drinks lead each section; they are the ones they came back for. */
    val canMakeNow: List<MakeableCocktail> =
        customDrinks.filter { it.canMakeNow }.sortedBy { it.cocktail.name } + recipes.filter { it.canMakeNow }

    val almost: List<MakeableCocktail> =
        customDrinks.filterNot { it.canMakeNow }.sortedBy { it.cocktail.name } + recipes.filterNot { it.canMakeNow }

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
        viewModelScope.launch {
            combine(preferences.customDrinks, preferences.loadedBottleIds) { drinks, ids ->
                CustomDrinks.evaluate(drinks, ids)
            }.collect { scored -> _uiState.update { it.copy(customDrinks = scored) } }
        }
    }

    fun retry() = load(_uiState.value.loadedBottles)

    private fun load(bottles: List<Bottle>) {
        loadJob?.cancel()
        if (bottles.isEmpty()) {
            _uiState.update {
                it.copy(isLoading = false, recipes = emptyList(), errorMessage = null)
            }
            return
        }
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatchingCancellable { repository.findMakeable(bottles) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            recipes = result.canMakeNow + result.almost,
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

/**
 * [runCatching] that lets cancellation through. A load that was cancelled because a newer
 * one started must not report a failure: its state update is not itself cancellable, so the
 * error would land after the replacement has already cleared it and strand the screen.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    val result = runCatching(block)
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    return result
}

/** Turns any failure into something a guest standing at the machine can act on. */
fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "No internet connection. Check your network and try again."
    is java.net.SocketTimeoutException -> "TheCocktailDB took too long to answer."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong while contacting TheCocktailDB."
}
