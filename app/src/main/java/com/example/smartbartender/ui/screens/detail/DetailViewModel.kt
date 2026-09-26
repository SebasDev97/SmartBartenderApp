package com.example.smartbartender.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.ActiveJobStore
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.FavouritesStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.appContainer
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.DEFAULT_MAX_POUR_ML
import com.example.smartbartender.domain.model.PlanWarning
import com.example.smartbartender.domain.model.PourPlan
import com.example.smartbartender.domain.model.buildPourPlan
import com.example.smartbartender.domain.model.toCocktail
import com.example.smartbartender.domain.model.toMachineError
import com.example.smartbartender.ui.common.LoadError
import com.example.smartbartender.ui.common.toLoadError
import com.example.smartbartender.ui.navigation.DetailRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DetailUiState(
    val isLoading: Boolean = true,
    val cocktail: Cocktail? = null,
    /** Why the recipe could not be shown. Pour problems live in [pour] instead. */
    val loadError: LoadError? = null,
    val machineOnline: Boolean = false,
    val isFavourite: Boolean = false,
    val pour: PourPhase = PourPhase.Idle,
    /** Ingredients a person adds by hand — ice, mint, a salted rim. Shown before pouring. */
    val manualSteps: List<String> = emptyList(),
    /** What the pour plan had to guess, or could not pour. Shown before pouring. */
    val planWarnings: List<PlanWarning> = emptyList(),
    /** A drink the user made, which can be edited. */
    val isCustom: Boolean = false,
)

/**
 * Loads one recipe and drives the machine that pours it.
 *
 * The machine is the source of truth once a pour starts: this class posts a plan, then does
 * nothing but render the events that come back. Walking away from the screen mid-pour is
 * therefore harmless — the drink is still being made, and [reattach] picks the overlay back
 * up where it left off.
 */
class DetailViewModel(
    private val repository: CocktailRepository,
    private val cocktailId: String,
    private val rack: RackStore,
    private val activeJob: ActiveJobStore,
    private val favourites: FavouritesStore,
    private val customDrinks: CustomDrinkStore,
    private val machine: BartenderMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState(isCustom = CustomDrinks.isCustomId(cocktailId)))
    val uiState = _uiState.asStateFlow()

    private var followJob: Job? = null
    private var loadJob: Job? = null

    init {
        loadCocktail()
        observeMachine()
        observeFavourite()
    }

    fun retry() = loadCocktail()

    private fun loadCocktail() {
        loadJob?.cancel()
        if (_uiState.value.isCustom) {
            loadJob = observeCustomDrink()
            return
        }
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            runCatching { repository.cocktailById(cocktailId) }
                .onSuccess { cocktail ->
                    _uiState.update { it.copy(isLoading = false, cocktail = cocktail) }
                    showPlanPreview(rack.slots.first())
                    reattach()
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, loadError = throwable.toLoadError()) }
                }
        }
    }

    /**
     * A custom drink lives on the phone, not on TheCocktailDB. It is followed rather than read
     * once, so coming back from the editor shows the change straight away.
     */
    private fun observeCustomDrink(): Job = viewModelScope.launch {
        var reattached = false
        customDrinks.customDrinks
            .map { drinks -> drinks.firstOrNull { it.id == cocktailId } }
            .distinctUntilChanged()
            .collect { drink ->
                if (drink == null) {
                    _uiState.update { it.copy(isLoading = false, cocktail = null, loadError = LoadError.Deleted) }
                    return@collect
                }
                _uiState.update { it.copy(isLoading = false, cocktail = drink.toCocktail(), loadError = null) }
                showPlanPreview(rack.slots.first())
                if (!reattached) {
                    reattached = true
                    reattach()
                }
            }
    }

    /** The rack or the machine's glass changing changes what the plan would pour. */
    private fun observeMachine() {
        viewModelScope.launch {
            combine(machine.connection, rack.slots, ::Pair).collect { (connection, slots) ->
                _uiState.update { it.copy(machineOnline = connection.isConnected) }
                showPlanPreview(slots, connection)
            }
        }
    }

    private fun observeFavourite() {
        viewModelScope.launch {
            favourites.favourites
                .map { list -> list.any { it.id == cocktailId } }
                .collect { favourite -> _uiState.update { it.copy(isFavourite = favourite) } }
        }
    }

    fun toggleFavourite() {
        val state = _uiState.value
        val cocktail = state.cocktail ?: return
        viewModelScope.launch { favourites.setFavourite(cocktail.summary, !state.isFavourite) }
    }

    /**
     * Picks a pour back up after the screen — or the whole app — went away.
     *
     * The machine kept pouring regardless, so the honest thing is to show where it actually
     * got to rather than start from zero or pretend nothing happened.
     */
    private suspend fun reattach() {
        val activeJobId = activeJob.activeJobId.first() ?: return
        val job = machine.currentJob.value ?: return
        if (job.jobId != activeJobId || !job.status.isLive) return
        if (job.drinkId != null && job.drinkId != cocktailId) return

        _uiState.update { it.copy(pour = reducePour(it.pour, job)) }
        follow(job.jobId)
    }

    /** What the machine would be asked to do by hand or guess at, shown before pouring. */
    private fun showPlanPreview(slots: List<String?>, connection: ConnectionState = machine.connection.value) {
        val cocktail = _uiState.value.cocktail ?: return
        val plan = buildPourPlan(cocktail, slots, connection.maxPourMl())
        _uiState.update { it.copy(manualSteps = plan.manualSteps, planWarnings = plan.warnings) }
    }

    private suspend fun plan(cocktail: Cocktail): PourPlan =
        buildPourPlan(cocktail, rack.slots.first(), machine.connection.value.maxPourMl())

    fun startPreparation() {
        val cocktail = _uiState.value.cocktail ?: return
        if (_uiState.value.pour is PourPhase.Pouring) return

        viewModelScope.launch {
            val plan = plan(cocktail)
            if (!plan.isPourable) {
                _uiState.update { it.copy(pour = PourPhase.Failed(PourFailure.NothingPourable)) }
                return@launch
            }

            // The app mints the job id, so retrying a request the machine already received
            // returns the running job instead of pouring a second drink.
            val jobId = UUID.randomUUID().toString()
            _uiState.update { it.copy(pour = PourPhase.Pouring.starting(jobId)) }
            activeJob.setActiveJobId(jobId)

            machine.startPour(plan.toRequest(jobId))
                .onSuccess { job ->
                    _uiState.update { it.copy(pour = reducePour(it.pour, job)) }
                    follow(jobId)
                }
                .onFailure { throwable ->
                    activeJob.setActiveJobId(null)
                    _uiState.update { it.copy(pour = PourPhase.Failed(PourFailure.Refused(throwable.toMachineError()))) }
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
                    _uiState.update { it.copy(pour = reducePour(it.pour, job)) }
                }
        }
    }

    fun cancelPreparation() {
        val pouring = _uiState.value.pour as? PourPhase.Pouring ?: return
        // Don't reset locally — the machine reports `aborting` and then `aborted`, and the
        // overlay should show that rather than pretending the pumps stopped instantly.
        _uiState.update { it.copy(pour = pouring.copy(aborting = true)) }
        viewModelScope.launch {
            machine.abort(pouring.jobId).onFailure { resetPreparation() }
        }
    }

    /** Dismisses a finished or failed pour and returns the screen to idle. */
    fun acknowledgePreparation() {
        resetPreparation()
        viewModelScope.launch { activeJob.setActiveJobId(null) }
    }

    private fun resetPreparation() {
        followJob?.cancel()
        followJob = null
        _uiState.update { it.copy(pour = PourPhase.Idle) }
    }

    companion object {
        /** The cocktail id arrives as the [DetailRoute] navigation argument. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer
                DetailViewModel(
                    repository = container.repository,
                    cocktailId = createSavedStateHandle().toRoute<DetailRoute>().cocktailId,
                    rack = container.rack,
                    activeJob = container.activeJob,
                    favourites = container.favourites,
                    customDrinks = container.customDrinks,
                    machine = container.machine,
                )
            }
        }
    }
}

private fun ConnectionState.maxPourMl(): Double = snapshotOrNull?.maxPourMl ?: DEFAULT_MAX_POUR_ML
