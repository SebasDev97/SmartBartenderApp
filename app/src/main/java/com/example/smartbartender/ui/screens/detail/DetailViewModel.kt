package com.example.smartbartender.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.appContainer
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.ui.screens.available.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One stage of the simulated pour. */
data class PourStep(
    val label: String,
    val detail: String? = null,
)

data class PreparationState(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val steps: List<PourStep> = emptyList(),
    val currentStepIndex: Int = 0,
) {
    val progress: Float
        get() = when {
            steps.isEmpty() -> 0f
            isFinished -> 1f
            else -> currentStepIndex.toFloat() / steps.size
        }

    val currentStep: PourStep? get() = steps.getOrNull(currentStepIndex)
    val isIdle: Boolean get() = !isRunning && !isFinished
}

data class DetailUiState(
    val isLoading: Boolean = true,
    val cocktail: Cocktail? = null,
    val ledShowEnabled: Boolean = false,
    val preparation: PreparationState = PreparationState(),
    val errorMessage: String? = null,
)

/**
 * Loads one recipe and runs the simulated preparation.
 *
 * The machine hardware is somebody else's problem: here the pour is a timed walk through
 * the recipe's ingredients, which is exactly what the real controller will report back.
 */
class DetailViewModel(
    private val repository: CocktailRepository,
    private val cocktailId: String,
    preferences: BartenderPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState = _uiState.asStateFlow()

    private var preparationJob: Job? = null

    init {
        loadCocktail()
        viewModelScope.launch {
            preferences.ledShowEnabled.distinctUntilChanged().collect { enabled ->
                _uiState.update { it.copy(ledShowEnabled = enabled) }
            }
        }
    }

    fun retry() = loadCocktail()

    private fun loadCocktail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.cocktailById(cocktailId) }
                .onSuccess { cocktail -> _uiState.update { it.copy(isLoading = false, cocktail = cocktail) } }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    fun startPreparation() {
        val cocktail = _uiState.value.cocktail ?: return
        if (_uiState.value.preparation.isRunning) return

        val steps = buildList {
            add(PourStep("Positioning glass", cocktail.glass ?: "Cocktail glass"))
            cocktail.ingredients.forEach { ingredient ->
                add(PourStep("Pouring ${ingredient.name}", ingredient.measure))
            }
            add(PourStep("Mixing", "Stirring the blend"))
            add(PourStep("Finishing touch", "Garnish and serve"))
        }

        preparationJob?.cancel()
        _uiState.update {
            it.copy(preparation = PreparationState(isRunning = true, steps = steps, currentStepIndex = 0))
        }
        preparationJob = viewModelScope.launch {
            steps.indices.forEach { index ->
                _uiState.update { it.copy(preparation = it.preparation.copy(currentStepIndex = index)) }
                delay(STEP_DURATION_MILLIS)
            }
            _uiState.update {
                it.copy(
                    preparation = it.preparation.copy(
                        isRunning = false,
                        isFinished = true,
                        currentStepIndex = steps.lastIndex,
                    ),
                )
            }
        }
    }

    fun cancelPreparation() {
        preparationJob?.cancel()
        _uiState.update { it.copy(preparation = PreparationState()) }
    }

    /** Dismisses the "your cocktail is ready" state and returns the machine to idle. */
    fun acknowledgePreparation() = cancelPreparation()

    companion object {
        private const val STEP_DURATION_MILLIS = 1100L

        /** [SavedStateHandle] carries the `cocktailId` navigation argument. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val id: String = requireNotNull(handle["cocktailId"]) { "cocktailId argument missing" }
                DetailViewModel(appContainer.repository, id, appContainer.preferences)
            }
        }
    }
}
