package com.example.smartbartender.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.smartbartender.R
import com.example.smartbartender.ui.common.ObserveAsEvents
import com.example.smartbartender.ui.components.LedAmbience
import com.example.smartbartender.ui.components.rememberLedState
import com.example.smartbartender.ui.screens.available.AvailableScreen
import com.example.smartbartender.ui.screens.available.AvailableViewModel
import com.example.smartbartender.ui.screens.bottles.BottlesScreen
import com.example.smartbartender.ui.screens.bottles.BottlesViewModel
import com.example.smartbartender.ui.screens.calibration.CalibrationActions
import com.example.smartbartender.ui.screens.calibration.CalibrationScreen
import com.example.smartbartender.ui.screens.calibration.CalibrationViewModel
import com.example.smartbartender.ui.screens.cleaning.CleaningActions
import com.example.smartbartender.ui.screens.cleaning.CleaningScreen
import com.example.smartbartender.ui.screens.cleaning.CleaningViewModel
import com.example.smartbartender.ui.screens.custom.CustomDrinkEditorActions
import com.example.smartbartender.ui.screens.custom.CustomDrinkEditorScreen
import com.example.smartbartender.ui.screens.custom.CustomDrinkEditorViewModel
import com.example.smartbartender.ui.screens.custom.EditorEvent
import com.example.smartbartender.ui.screens.detail.DetailScreen
import com.example.smartbartender.ui.screens.detail.DetailViewModel
import com.example.smartbartender.ui.screens.library.LibraryEvent
import com.example.smartbartender.ui.screens.library.LibraryScreen
import com.example.smartbartender.ui.screens.library.LibraryViewModel
import com.example.smartbartender.ui.screens.settings.SettingsScreen
import com.example.smartbartender.ui.screens.settings.SettingsViewModel
import com.example.smartbartender.ui.screens.stats.StatsScreen
import com.example.smartbartender.ui.screens.stats.StatsViewModel
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextPrimary

/** Root composable: bottom navigation over the top-level tabs, with pushed screens on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartBartenderApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    // The LED show state lives at the root so every screen shares one animation clock.
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val led = rememberLedState(enabled = settingsState.ledShowEnabled)

    val isTopLevel = destination?.isTopLevel() == true

    LedAmbience(led = led, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = screenTitle(backStackEntry),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    navigationIcon = {
                        if (destination != null && !isTopLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                if (isTopLevel) {
                    BartenderNavigationBar(
                        destination = destination,
                        accent = led.primary,
                        onSelect = { target ->
                            navController.navigate(target.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = AvailableRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<AvailableRoute> {
                    val viewModel: AvailableViewModel = viewModel(factory = AvailableViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    AvailableScreen(
                        state = state,
                        led = led,
                        onCocktailClick = { id -> navController.navigate(DetailRoute(id)) },
                        onOpenBottles = { navController.navigate(BottlesRoute) },
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }

                composable<LibraryRoute> {
                    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    ObserveAsEvents(viewModel.events) { event ->
                        when (event) {
                            is LibraryEvent.OpenCocktail -> navController.navigate(DetailRoute(event.id))
                        }
                    }
                    LibraryScreen(
                        state = state,
                        led = led,
                        onQueryChange = viewModel::onQueryChange,
                        onClearQuery = viewModel::clearQuery,
                        onCocktailClick = { id -> navController.navigate(DetailRoute(id)) },
                        onFilterChange = viewModel::setFilter,
                        onFavouriteChange = viewModel::setFavourite,
                        onCreateDrink = { navController.navigate(CustomEditRoute()) },
                        onSurpriseMe = viewModel::surpriseMe,
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }

                composable<BottlesRoute> {
                    val viewModel: BottlesViewModel = viewModel(factory = BottlesViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    BottlesScreen(
                        state = state,
                        led = led,
                        onToggleBottle = viewModel::toggleBottle,
                        onEjectSlot = viewModel::ejectSlot,
                        onEjectAll = viewModel::ejectAll,
                        onLoadDefaults = viewModel::loadDefaults,
                        contentPadding = innerPadding,
                    )
                }

                composable<StatsRoute> {
                    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    StatsScreen(
                        state = state,
                        led = led,
                        onCocktailClick = { id -> navController.navigate(DetailRoute(id)) },
                        onResetRequest = viewModel::requestReset,
                        onResetConfirm = viewModel::confirmReset,
                        onResetDismiss = viewModel::dismissReset,
                        contentPadding = innerPadding,
                    )
                }

                composable<SettingsRoute> {
                    SettingsScreen(
                        state = settingsState,
                        led = led,
                        onLedShowChange = settingsViewModel::setLedShowEnabled,
                        onMachineHostChange = settingsViewModel::setMachineHost,
                        onMachinePortChange = settingsViewModel::setMachinePort,
                        onMachineEnabledChange = settingsViewModel::setMachineEnabled,
                        onConnect = settingsViewModel::connect,
                        onTestConnection = settingsViewModel::testConnection,
                        onOpenCalibration = { navController.navigate(CalibrationRoute) },
                        onOpenCleaning = { navController.navigate(CleaningRoute) },
                        contentPadding = innerPadding,
                    )
                }

                composable<CalibrationRoute> {
                    val viewModel: CalibrationViewModel = viewModel(factory = CalibrationViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    CalibrationScreen(
                        state = state,
                        led = led,
                        actions = CalibrationActions(
                            onMeasureReference = viewModel::measureReference,
                            onTogglePump = viewModel::togglePump,
                            onJog = viewModel::jog,
                            onStart = viewModel::start,
                            onAbort = viewModel::abort,
                        ),
                        contentPadding = innerPadding,
                    )
                }

                composable<CleaningRoute> {
                    val viewModel: CleaningViewModel = viewModel(factory = CleaningViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    CleaningScreen(
                        state = state,
                        led = led,
                        actions = CleaningActions(
                            onTogglePump = viewModel::togglePump,
                            onSecondsChange = viewModel::setSeconds,
                            onRoundsChange = viewModel::setRounds,
                            onContainerConfirmedChange = viewModel::setContainerConfirmed,
                            onStart = viewModel::start,
                            onAbort = viewModel::abort,
                        ),
                        contentPadding = innerPadding,
                    )
                }

                composable<DetailRoute> {
                    val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory())
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    DetailScreen(
                        state = state,
                        led = led,
                        onStartPreparation = viewModel::startPreparation,
                        onToggleFavourite = viewModel::toggleFavourite,
                        onEdit = { state.cocktail?.let { navController.navigate(CustomEditRoute(it.id)) } },
                        onCancelPreparation = viewModel::cancelPreparation,
                        onFinishAcknowledged = viewModel::acknowledgePreparation,
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }

                composable<CustomEditRoute> {
                    val viewModel: CustomDrinkEditorViewModel = viewModel(factory = CustomDrinkEditorViewModel.factory())
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    ObserveAsEvents(viewModel.events) { event ->
                        when (event) {
                            // Land on a new drink, with the editor gone from the back stack.
                            is EditorEvent.Saved -> if (event.wasNew) {
                                navController.navigate(DetailRoute(event.id)) {
                                    popUpTo<CustomEditRoute> { inclusive = true }
                                }
                            } else {
                                navController.popBackStack()
                            }

                            // The detail screen it was opened from shows a drink that is gone.
                            EditorEvent.Deleted -> if (!navController.popBackStack<DetailRoute>(inclusive = true)) {
                                navController.popBackStack()
                            }
                        }
                    }
                    CustomDrinkEditorScreen(
                        state = state,
                        led = led,
                        actions = CustomDrinkEditorActions(
                            onNameChange = viewModel::onNameChange,
                            onEmojiChange = viewModel::onEmojiChange,
                            onColourChange = viewModel::onColourChange,
                            onNotesChange = viewModel::onNotesChange,
                            onAddItem = viewModel::addItem,
                            onRemoveItem = viewModel::removeItem,
                            onMoveItem = viewModel::moveItem,
                            onMlTextChange = viewModel::onMlTextChange,
                            onStepMl = viewModel::stepMl,
                            onOpenBottlePicker = viewModel::openBottlePicker,
                            onDismissBottlePicker = viewModel::dismissBottlePicker,
                            onBottlePicked = viewModel::onBottlePicked,
                            onSave = viewModel::save,
                            onDeleteRequest = viewModel::requestDelete,
                            onDeleteConfirm = viewModel::confirmDelete,
                            onDeleteDismiss = viewModel::dismissDelete,
                        ),
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }
}

private fun NavDestination.isTopLevel(): Boolean =
    TopLevelDestination.entries.any { hasRoute(it.route::class) }

/** The top-bar title for whatever is on screen; empty while nothing is, and on the recipe screen. */
@Composable
private fun screenTitle(entry: NavBackStackEntry?): String {
    val destination = entry?.destination ?: return ""
    if (destination.hasRoute<CustomEditRoute>()) {
        val isNew = entry.toRoute<CustomEditRoute>().drinkId == null
        return stringResource(if (isNew) R.string.title_new_drink else R.string.title_edit_drink)
    }
    val titleRes = TopLevelDestination.entries.firstOrNull { destination.hasRoute(it.route::class) }?.titleRes
        ?: PushedScreenTitles.entries.firstOrNull { destination.hasRoute(it.key) }?.value
        ?: return ""
    return stringResource(titleRes)
}

@Composable
private fun BartenderNavigationBar(
    destination: NavDestination?,
    accent: Color,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Box(
        Modifier.background(
            Brush.verticalGradient(
                0f to Obsidian.copy(alpha = 0.90f),
                0.22f to Obsidian,
                1f to Obsidian,
            ),
        ),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestination.entries.forEach { target ->
                val selected = destination?.hasRoute(target.route::class) == true
                val scale by animateFloatAsState(if (selected) 1.05f else 1f, label = "navScale")
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(target) },
                    icon = {
                        Icon(
                            imageVector = target.icon,
                            contentDescription = stringResource(target.labelRes),
                            modifier = Modifier.height(24.dp * scale),
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(target.labelRes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                letterSpacing = 0.2.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = accent,
                        // The LED cycle dips through blue/violet, which read poorly as text on
                        // Obsidian - lift the label towards white while keeping the live hue.
                        selectedTextColor = lerp(accent, Color.White, 0.35f),
                        indicatorColor = accent.copy(alpha = 0.14f),
                        unselectedIconColor = TextPrimary.copy(alpha = 0.80f),
                        unselectedTextColor = TextPrimary.copy(alpha = 0.80f),
                    ),
                )
            }
        }
    }
}
