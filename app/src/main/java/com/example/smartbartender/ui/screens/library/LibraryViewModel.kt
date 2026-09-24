package com.example.smartbartender.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.Favourites
import com.example.smartbartender.domain.model.toCocktail
import com.example.smartbartender.ui.screens.available.runCatchingCancellable
import com.example.smartbartender.ui.screens.available.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Which slice of drinks the Library shows. */
enum class LibraryFilter { ALL, FAVOURITES, MINE }

data class LibraryUiState(
    val query: String = "",
    val isLoading: Boolean = true,
    val results: List<CocktailSummary> = emptyList(),
    val errorMessage: String? = null,
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
    val visibleFavourites: List<CocktailSummary> = Favourites.filter(resolvedFavourites, query)

    val visibleMyDrinks: List<CocktailSummary> = Favourites.filter(myDrinks, query)

    val favouriteCount: Int get() = resolvedFavourites.size
}

/**
 * Browsing plus debounced name search against `search.php?s=`, and the user's favourites and
 * own drinks, which are filtered from storage rather than fetched.
 */
class LibraryViewModel(
    private val repository: CocktailRepository,
    private val preferences: BartenderPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        browse()
        viewModelScope.launch {
            preferences.favourites.collect { favourites ->
                _uiState.update { it.copy(favourites = favourites) }
            }
        }
        viewModelScope.launch {
            preferences.customDrinks.collect { drinks ->
                _uiState.update { it.copy(customDrinks = drinks) }
            }
        }
    }

    fun setFilter(filter: LibraryFilter) = _uiState.update { it.copy(filter = filter) }

    fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) {
        viewModelScope.launch { preferences.setFavourite(cocktail, favourite) }
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatchingCancellable { repository.searchByName(query.trim()) }
                .onSuccess { drinks ->
                    _uiState.update {
                        it.copy(isLoading = false, results = drinks.map { drink -> drink.summary })
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    fun clearQuery() = onQueryChange("")

    fun retry() = if (_uiState.value.query.isBlank()) browse() else onQueryChange(_uiState.value.query)

    /** Picks a random drink and hands its id back so the caller can navigate to it. */
    fun surpriseMe(onPicked: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.randomCocktail() }
                .onSuccess { onPicked(it.id) }
                .onFailure { throwable ->
                    _uiState.update { it.copy(errorMessage = throwable.userMessage()) }
                }
        }
    }

    private fun browse() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatchingCancellable { repository.browse() }
                .onSuccess { drinks ->
                    _uiState.update {
                        it.copy(isLoading = false, results = drinks.map { drink -> drink.summary })
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 350L

        val Factory = containerViewModelFactory { container ->
            LibraryViewModel(container.repository, container.preferences)
        }
    }
}
