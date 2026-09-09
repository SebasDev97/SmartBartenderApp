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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.smartbartender.ui.components.LedAmbience
import com.example.smartbartender.ui.components.rememberLedState
import com.example.smartbartender.ui.screens.available.AvailableScreen
import com.example.smartbartender.ui.screens.available.AvailableViewModel
import com.example.smartbartender.ui.screens.bottles.BottlesScreen
import com.example.smartbartender.ui.screens.bottles.BottlesViewModel
import com.example.smartbartender.ui.screens.detail.DetailScreen
import com.example.smartbartender.ui.screens.detail.DetailViewModel
import com.example.smartbartender.ui.screens.library.LibraryScreen
import com.example.smartbartender.ui.screens.library.LibraryViewModel
import com.example.smartbartender.ui.screens.settings.SettingsScreen
import com.example.smartbartender.ui.screens.settings.SettingsViewModel
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextPrimary

/** Root composable: bottom navigation over the four tabs, with a pushed detail screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartBartenderApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The LED show state lives at the root so every screen shares one animation clock.
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val led = rememberLedState(enabled = settingsState.ledShowEnabled)

    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }
    val title = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }?.let {
        when (it) {
            TopLevelDestination.AVAILABLE -> "Smart Bartender"
            TopLevelDestination.LIBRARY -> "Library"
            TopLevelDestination.BOTTLES -> "Bottles"
            TopLevelDestination.SETTINGS -> "Settings"
        }
    } ?: ""

    LedAmbience(led = led, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                if (currentRoute != Routes.DETAIL) {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                } else {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }
            },
            bottomBar = {
                if (isTopLevel) {
                    BartenderNavigationBar(
                        currentRoute = currentRoute,
                        accent = if (led.enabled) led.primary else MaterialTheme.colorScheme.primary,
                        onSelect = { destination ->
                            navController.navigate(destination.route) {
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
                startDestination = Routes.AVAILABLE,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.AVAILABLE) {
                    val viewModel: AvailableViewModel = viewModel(factory = AvailableViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    AvailableScreen(
                        state = state,
                        led = led,
                        onCocktailClick = { id -> navController.navigate(Routes.detail(id)) },
                        onOpenBottles = { navController.navigate(Routes.BOTTLES) },
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }

                composable(Routes.LIBRARY) {
                    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LibraryScreen(
                        state = state,
                        led = led,
                        onQueryChange = viewModel::onQueryChange,
                        onClearQuery = viewModel::clearQuery,
                        onCocktailClick = { id -> navController.navigate(Routes.detail(id)) },
                        onSurpriseMe = { viewModel.surpriseMe { id -> navController.navigate(Routes.detail(id)) } },
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }

                composable(Routes.BOTTLES) {
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

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        state = settingsState,
                        led = led,
                        onLedShowChange = settingsViewModel::setLedShowEnabled,
                        contentPadding = innerPadding,
                    )
                }

                composable(
                    route = Routes.DETAIL,
                    arguments = listOf(navArgument("cocktailId") { type = NavType.StringType }),
                ) {
                    val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory())
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    DetailScreen(
                        state = state,
                        led = led,
                        onStartPreparation = viewModel::startPreparation,
                        onCancelPreparation = viewModel::cancelPreparation,
                        onFinishAcknowledged = viewModel::acknowledgePreparation,
                        onRetry = viewModel::retry,
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun BartenderNavigationBar(
    currentRoute: String?,
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
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                val scale by animateFloatAsState(if (selected) 1.05f else 1f, label = "navScale")
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            modifier = Modifier.height(24.dp * scale),
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
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
