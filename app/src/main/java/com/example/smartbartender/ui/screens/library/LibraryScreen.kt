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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
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
import com.example.smartbartender.ui.components.CocktailGridCard
import com.example.smartbartender.ui.components.CocktailGridSkeleton
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.ErrorState
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/** Browse the whole book and search it by name. */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    led: LedState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onCocktailClick: (String) -> Unit,
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
                placeholder = { Text("Search cocktails", color = TextSecondary) },
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

        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> CocktailGridSkeleton(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                )

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

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.results, key = { it.id }) { cocktail ->
                        CocktailGridCard(
                            cocktail = cocktail,
                            accent = accent,
                            onClick = { onCocktailClick(cocktail.id) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
