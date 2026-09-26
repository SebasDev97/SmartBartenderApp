package com.example.smartbartender.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.smartbartender.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/*
 * Every screen the app can show, as a type-safe route. A screen's arguments are the route's
 * properties, and its ViewModel reads them back with `SavedStateHandle.toRoute()`.
 */

@Serializable data object AvailableRoute

@Serializable data object LibraryRoute

@Serializable data object BottlesRoute

@Serializable data object StatsRoute

@Serializable data object SettingsRoute

@Serializable data class DetailRoute(val cocktailId: String)

/** Create a custom drink, or edit the one with [drinkId]. */
@Serializable data class CustomEditRoute(val drinkId: String? = null)

/** Tray reference, pump priming and calibration, pushed from Settings. */
@Serializable data object CalibrationRoute

/** Rinse the pump lines with warm water, pushed from Settings. */
@Serializable data object CleaningRoute

/** The bottom-bar destinations, in display order. */
enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    AVAILABLE(AvailableRoute, R.string.nav_available, R.string.app_name, Icons.Filled.AutoAwesome),
    LIBRARY(LibraryRoute, R.string.nav_library, R.string.nav_library, Icons.Filled.LocalBar),
    BOTTLES(BottlesRoute, R.string.nav_bottles, R.string.nav_bottles, Icons.Filled.Liquor),
    STATS(StatsRoute, R.string.nav_stats, R.string.nav_stats, Icons.Filled.Insights),
    SETTINGS(SettingsRoute, R.string.nav_settings, R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * Top-bar titles of the pushed screens that have a fixed one. The recipe screen has none —
 * its photo is its title — and the editor's depends on its argument.
 */
val PushedScreenTitles: Map<KClass<out Any>, Int> = mapOf(
    CalibrationRoute::class to R.string.title_calibration,
    CleaningRoute::class to R.string.title_cleaning,
)
