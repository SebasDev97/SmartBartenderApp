package com.example.smartbartender.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.appContainer
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.PourPlan
import com.example.smartbartender.domain.model.buildPourPlan
import com.example.smartbartender.ui.screens.available.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** One stage of the pour, as reported by the machine. */
data class PourStep(
    val label: String,
    val detail: String? = null,
    /** Something the human does — ice, a garnish, a salted rim. No pump can serve it. */
    val isManual: Boolean = false,
)

data class PreparationState(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val isAborting: Boolean = false,
    val steps: List<PourStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val jobId: String? = null,
    /** Volume-weighted progress from the machine. Authoritative when present. */
    val remoteProgress: Float? = null,
    val errorMessage: String? = null,
) {
    val progress: Float
        get() = remoteProgress ?: when {
            steps.isEmpty() -> 0f
            isFinished -> 1f
            else -> (currentStepIndex + 1).toFloat() / steps.size
        }

    val currentStep: PourStep? get() = steps.getOrNull(currentStepIndex)
    val isIdle: Boolean get() = !isRunning && !isFinished
}

data class DetailUiState(
    val isLoading: Boolean = true,
    val cocktail: Cocktail? = null,
    val machineOnline: Boolean = false,
    val isFavourite: Boolean = false,
    val preparation: PreparationState = PreparationState(),
    /** Ingredients no pump can serve, or that needed a guess. Shown before pouring. */
    val pourNotes: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Loads one recipe and drives the machine that pours it.
 *
 * The machine is the source of truth once a pour starts: this class posts a plan, then does
 * nothing but render the events that come back. That is why there is no timer here any more,
 * and why walking away from the screen mid-pour is harmless — the drink is still being made,
 * and [reattach] picks the overlay back up where it left off.
 */
class DetailViewModel(
    private val repository: CocktailRepository,
    private val cocktailId: String,
    private val preferences: BartenderPreferences,
    private val machine: BartenderMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState = _uiState.asStateFlow()

    private var followJob: Job? = null

    init {
        loadCocktail()
        observeMachine()
        observeFavourite()
    }

    fun retry() = loadCocktail()

    private fun loadCocktail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.cocktailById(cocktailId) }
                .onSuccess { cocktail ->
                    _uiState.update { it.copy(isLoading = false, cocktail = cocktail) }
                    refreshPourNotes()
                    reattach()
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    private fun observeMachine() {
        viewModelScope.launch {
            combine(machine.connection, preferences.slots) { connection, slots -> connection to slots }
                .collect { (connection, _) ->
                    _uiState.update { it.copy(machineOnline = connection.isConnected) }
                    refreshPourNotes()
                }
        }
    }

    private fun observeFavourite() {
        viewModelScope.launch {
            preferences.favourites
                .map { favourites -> favourites.any { it.id == cocktailId } }
                .collect { favourite -> _uiState.update { it.copy(isFavourite = favourite) } }
        }
    }

    fun toggleFavourite() {
        val state = _uiState.value
        val cocktail = state.cocktail ?: return
        viewModelScope.launch { preferences.setFavourite(cocktail.summary, !state.isFavourite) }
    }

    /**
     * Picks a pour back up after the screen — or the whole app — went away.
     *
     * The machine kept pouring regardless, so the honest thing is to show where it actually
     * got to rather than start from zero or pretend nothing happened.
     */
    private suspend fun reattach() {
        val activeJobId = preferences.activeJobId.first() ?: return
        val job = machine.currentJob.value ?: return
        if (job.jobId != activeJobId || !job.status.isLive) return
        if (job.drinkId != null && job.drinkId != cocktailId) return

        _uiState.update { it.copy(preparation = reducePour(it.preparation, job)) }
        follow(job.jobId)
    }

    private fun refreshPourNotes() {
        val cocktail = _uiState.value.cocktail ?: return
        viewModelScope.launch {
            val plan = plan(cocktail)
            _uiState.update { it.copy(pourNotes = plan.manualSteps + plan.warnings) }
        }
    }

    private suspend fun plan(cocktail: Cocktail): PourPlan = buildPourPlan(
        cocktail = cocktail,
        slots = preferences.slots.first(),
        maxPourMl = (machine.connection.value as? ConnectionState.Connected)?.snapshot?.maxPourMl
            ?: DEFAULT_MAX_POUR_ML,
    )

    fun startPreparation() {
        val cocktail = _uiState.value.cocktail ?: return
        if (_uiState.value.preparation.isRunning) return

        viewModelScope.launch {
            val plan = plan(cocktail)
            if (!plan.isPourable) {
                _uiState.update {
                    it.copy(
                        preparation = PreparationState(
                            errorMessage = "Nothing in this recipe can be poured by the machine",
                            isFinished = true,
                        ),
                    )
                }
                return@launch
            }

            // The app mints the job id, so retrying a request the machine already received
            // returns the running job instead of pouring a second drink.
            val jobId = UUID.randomUUID().toString()
            _uiState.update {
                it.copy(preparation = optimisticPreparation(plan, jobId, cocktail.glass))
            }
            preferences.setActiveJobId(jobId)

            machine.startPour(plan.request.copy(jobId = jobId))
                .onSuccess { job ->
                    _uiState.update { it.copy(preparation = reducePour(it.preparation, job)) }
                    follow(jobId)
                }
                .onFailure { throwable ->
                    preferences.setActiveJobId(null)
                    _uiState.update {
                        it.copy(
                            preparation = PreparationState(
                                isFinished = true,
                                errorMessage = throwable.message ?: "The machine refused that",
                            ),
                        )
                    }
                }
        }
    }

    /** From here on the screen is a mirror: every change comes from the machine. */
    private fun follow(jobId: String) {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            machine.currentJob
                .filterNotNull()
                .collect { job ->
                    if (job.jobId != jobId) return@collect
                    _uiState.update { it.copy(preparation = reducePour(it.preparation, job)) }
                }
        }
    }

    fun cancelPreparation() {
        val jobId = _uiState.value.preparation.jobId
        if (jobId == null) {
            resetPreparation()
            return
        }
        // Don't reset locally — the machine reports `aborting` and then `aborted`, and the
        // overlay should show that rather than pretending the pumps stopped instantly.
        _uiState.update { it.copy(preparation = it.preparation.copy(isAborting = true)) }
        viewModelScope.launch {
            machine.abort(jobId).onFailure { resetPreparation() }
        }
    }

    /** Dismisses the "your cocktail is ready" state and returns the screen to idle. */
    fun acknowledgePreparation() {
        resetPreparation()
        viewModelScope.launch { preferences.setActiveJobId(null) }
    }

    private fun resetPreparation() {
        followJob?.cancel()
        followJob = null
        _uiState.update { it.copy(preparation = PreparationState()) }
    }

    companion object {
        /** Used only until the machine tells us the real glass size. */
        private const val DEFAULT_MAX_POUR_ML = 250.0

        /** [SavedStateHandle] carries the `cocktailId` navigation argument. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val handle: SavedStateHandle = createSavedStateHandle()
                val id: String = requireNotNull(handle["cocktailId"]) { "cocktailId argument missing" }
                DetailViewModel(
                    appContainer.repository,
                    id,
                    appContainer.preferences,
                    appContainer.machine,
                )
            }
        }
    }
}
