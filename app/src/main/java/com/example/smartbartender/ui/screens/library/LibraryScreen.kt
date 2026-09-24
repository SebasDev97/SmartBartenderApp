package com.example.smartbartender.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.ui.components.CocktailGridCard
import com.example.smartbartender.ui.components.CocktailGridSkeleton
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.ErrorState
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonMagenta
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/** Browse the whole book, the favourites or the user's own drinks, and search any of them by name. */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    led: LedState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onCocktailClick: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onFavouriteChange: (CocktailSummary, Boolean) -> Unit,
    onCreateDrink: () -> Unit,
    onSurpriseMe: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = if (led.enabled) led.primary else NeonCyan
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = when (state.filter) {
                            LibraryFilter.ALL -> "Search cocktails"
                            LibraryFilter.FAVOURITES -> "Search favourites"
                            LibraryFilter.MINE -> "Search my drinks"
                        },
                        color = TextSecondary,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = accent)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = SteelOutline,
                    cursorColor = accent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = onSurpriseMe) {
                Icon(
                    imageVector = Icons.Filled.Casino,
                    contentDescription = "Surprise me with a random cocktail",
                    tint = accent,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filter == LibraryFilter.ALL,
                onClick = { onFilterChange(LibraryFilter.ALL) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent.copy(alpha = 0.16f),
                    selectedLabelColor = accent,
                ),
            )
            FilterChip(
                selected = state.filter == LibraryFilter.FAVOURITES,
                onClick = { onFilterChange(LibraryFilter.FAVOURITES) },
                label = {
                    Text(
                        if (state.favouriteCount == 0) "Favourites" else "Favourites (${state.favouriteCount})",
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.filter == LibraryFilter.FAVOURITES) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonMagenta.copy(alpha = 0.16f),
                    selectedLabelColor = NeonMagenta,
                    selectedLeadingIconColor = NeonMagenta,
                ),
            )
            FilterChip(
                selected = state.filter == LibraryFilter.MINE,
                onClick = { onFilterChange(LibraryFilter.MINE) },
                label = {
                    Text(if (state.customDrinks.isEmpty()) "My drinks" else "My drinks (${state.customDrinks.size})")
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonAmber.copy(alpha = 0.16f),
                    selectedLabelColor = NeonAmber,
                    selectedLeadingIconColor = NeonAmber,
                ),
            )
        }

        val gridPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )

        Box(Modifier.fillMaxSize()) {
            when {
                // Favourites and custom drinks come from storage, so the network's loading
                // and error states have nothing to say about them.
                state.filter == LibraryFilter.MINE -> if (state.visibleMyDrinks.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Science,
                        title = if (state.customDrinks.isEmpty()) "No drinks of your own yet" else "No matches",
                        message = if (state.customDrinks.isEmpty()) {
                            "Pick up to ${BottleCatalog.MAX_SLOTS} bottles, set the millilitres, and the machine pours it your way."
                        } else {
                            "None of your drinks is called \"${state.query}\"."
                        },
                        actionLabel = if (state.customDrinks.isEmpty()) "Create a drink" else null,
                        onAction = onCreateDrink,
                        accent = NeonAmber,
                    )
                } else {
                    CocktailGrid(
                        cocktails = state.visibleMyDrinks,
                        favouriteIds = state.favouriteIds,
                        accent = accent,
                        onCocktailClick = onCocktailClick,
                        onFavouriteChange = onFavouriteChange,
                        // Room under the last row so the button never covers a card.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = contentPadding.calculateBottomPadding() + 96.dp,
                        ),
                    )
                    ExtendedFloatingActionButton(
                        onClick = onCreateDrink,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("New drink") },
                        containerColor = NeonAmber,
                        contentColor = Obsidian,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    )
                }

                state.filter == LibraryFilter.FAVOURITES -> if (state.visibleFavourites.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.FavoriteBorder,
                        title = if (state.favouriteCount == 0) "No favourites yet" else "No matches",
                        message = if (state.favouriteCount == 0) {
                            "Tap the heart on any cocktail to keep it here."
                        } else {
                            "None of your favourites is called \"${state.query}\"."
                        },
                    )
                } else {
                    CocktailGrid(
                        cocktails = state.visibleFavourites,
                        favouriteIds = state.favouriteIds,
                        accent = accent,
                        onCocktailClick = onCocktailClick,
                        onFavouriteChange = onFavouriteChange,
                        contentPadding = gridPadding,
                    )
                }

                state.isLoading -> CocktailGridSkeleton(contentPadding = gridPadding)

                state.errorMessage != null -> ErrorState(message = state.errorMessage, onRetry = onRetry)

                state.results.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = if (state.isSearching) "No matches" else "Nothing to show",
                    message = if (state.isSearching) {
                        "No cocktail called \"${state.query}\" was found. Try a shorter name."
                    } else {
                        "The library could not be loaded."
                    },
                )

                else -> CocktailGrid(
                    cocktails = state.results,
                    favouriteIds = state.favouriteIds,
                    accent = accent,
                    onCocktailClick = onCocktailClick,
                    onFavouriteChange = onFavouriteChange,
                    contentPadding = gridPadding,
                )
            }
        }
    }
}

@Composable
private fun CocktailGrid(
    cocktails: List<CocktailSummary>,
    favouriteIds: Set<String>,
    accent: Color,
    onCocktailClick: (String) -> Unit,
    onFavouriteChange: (CocktailSummary, Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cocktails, key = { it.id }) { cocktail ->
            val isFavourite = cocktail.id in favouriteIds
            CocktailGridCard(
                cocktail = cocktail,
                accent = accent,
                onClick = { onCocktailClick(cocktail.id) },
                isFavourite = isFavourite,
                onToggleFavourite = { onFavouriteChange(cocktail, !isFavourite) },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
