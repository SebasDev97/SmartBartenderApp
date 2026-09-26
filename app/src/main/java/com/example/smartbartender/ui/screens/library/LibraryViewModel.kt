package com.example.smartbartender.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.FavouritesStore
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.filterByName
import com.example.smartbartender.domain.model.toCocktail
import com.example.smartbartender.ui.common.LoadError
import com.example.smartbartender.ui.common.toLoadError
import com.example.smartbartender.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Which slice of drinks the Library shows. */
enum class LibraryFilter { ALL, FAVOURITES, MINE }

/** Something the Library asks its host to do once, rather than a state to render. */
sealed interface LibraryEvent {
    data class OpenCocktail(val id: String) : LibraryEvent

    /** Surprise me could not pick a drink. Said once, without touching the list on screen. */
    data object SurpriseFailed : LibraryEvent
}

data class LibraryUiState(
    val query: String = "",
    val isLoading: Boolean = true,
    val results: List<CocktailSummary> = emptyList(),
    val loadError: LoadError? = null,
    val favourites: List<CocktailSummary> = emptyList(),
    val customDrinks: List<CustomDrink> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.ALL,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    val favouriteIds: Set<String> = favourites.mapTo(HashSet()) { it.id }

    /** The user's own drinks, as cards. */
    val myDrinks: List<CocktailSummary> = customDrinks.map { it.toCocktail().summary }

    /**
     * Favourites as stored, except that a custom drink is drawn from its live copy — so a
     * rename or a new colour shows up here too, and a deleted one never does.
     */
    private val resolvedFavourites: List<CocktailSummary> = run {
        val mine = myDrinks.associateBy { it.id }
        favourites.mapNotNull { if (CustomDrinks.isCustomId(it.id)) mine[it.id] else it }
    }

    /** Favourites and custom drinks are searched locally, so those views never wait on the network. */
    val visibleFavourites: List<CocktailSummary> = resolvedFavourites.filterByName(query) { it.name }

    val visibleMyDrinks: List<CocktailSummary> = myDrinks.filterByName(query) { it.name }

    val favouriteCount: Int get() = resolvedFavourites.size
}

/**
 * Browsing plus debounced name search against `search.php?s=`, and the user's favourites and
 * own drinks, which are filtered from storage rather than fetched.
 */
class LibraryViewModel(
    private val repository: CocktailRepository,
    private val favourites: FavouritesStore,
    customDrinks: CustomDrinkStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        browse()
        viewModelScope.launch {
            favourites.favourites.collect { list -> _uiState.update { it.copy(favourites = list) } }
        }
        viewModelScope.launch {
            customDrinks.customDrinks.collect { drinks -> _uiState.update { it.copy(customDrinks = drinks) } }
        }
    }

    fun setFilter(filter: LibraryFilter) = _uiState.update { it.copy(filter = filter) }

    fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) {
        viewModelScope.launch { favourites.setFavourite(cocktail, favourite) }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            browse()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS.milliseconds)
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            runCatchingCancellable { repository.searchByName(query.trim()) }
                .onSuccess { drinks ->
                    _uiState.update { it.copy(isLoading = false, results = drinks.map { drink -> drink.summary }) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, loadError = throwable.toLoadError()) }
                }
        }
    }

    fun clearQuery() = onQueryChange("")

    fun retry() = if (_uiState.value.query.isBlank()) browse() else onQueryChange(_uiState.value.query)

    /** Picks a random drink and asks the host to open it. */
    fun surpriseMe() {
        viewModelScope.launch {
            runCatchingCancellable { repository.randomCocktail() }
                .onSuccess { _events.send(LibraryEvent.OpenCocktail(it.id)) }
                .onFailure { _events.send(LibraryEvent.SurpriseFailed) }
        }
    }

    private fun browse() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            runCatchingCancellable { repository.browse() }
                .onSuccess { drinks ->
                    _uiState.update { it.copy(isLoading = false, results = drinks.map { drink -> drink.summary }) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, loadError = throwable.toLoadError()) }
                }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 350L

        val Factory = containerViewModelFactory { container ->
            LibraryViewModel(container.repository, container.favourites, container.customDrinks)
        }
    }
}
