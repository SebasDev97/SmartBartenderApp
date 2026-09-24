package com.example.smartbartender.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.di.containerViewModelFactory
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.Favourites
import com.example.smartbartender.ui.screens.available.runCatchingCancellable
import com.example.smartbartender.ui.screens.available.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class LibraryUiState(
    val query: String = "",
    val isLoading: Boolean = true,
    val results: List<CocktailSummary> = emptyList(),
    val errorMessage: String? = null,
    val favourites: List<CocktailSummary> = emptyList(),
    val showFavourites: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    val favouriteIds: Set<String> = favourites.mapTo(HashSet()) { it.id }

    /** Favourites are searched locally, so that view never waits on the network. */
    val visibleFavourites: List<CocktailSummary> = Favourites.filter(favourites, query)
}

/**
 * Browsing plus debounced name search against `search.php?s=`, and the user's favourites,
 * which are filtered from storage rather than fetched.
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
    }

    fun setShowFavourites(show: Boolean) = _uiState.update { it.copy(showFavourites = show) }

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
