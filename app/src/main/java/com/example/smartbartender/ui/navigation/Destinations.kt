package com.example.smartbartender.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.ui.graphics.vector.ImageVector

/** Every screen the app can show. */
object Routes {
    const val AVAILABLE = "available"
    const val LIBRARY = "library"
    const val BOTTLES = "bottles"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{cocktailId}"

    /** Tray reference, pump priming and calibration, pushed from Settings. */
    const val CALIBRATION = "calibration"

    /** Rinse the pump lines with warm water, pushed from Settings. */
    const val CLEANING = "cleaning"

    /** Create a custom drink, or edit one when `drinkId` is given. */
    const val CUSTOM_EDIT = "custom/edit?drinkId={drinkId}"

    fun detail(cocktailId: String) = "detail/$cocktailId"

    fun customEdit(drinkId: String? = null) = if (drinkId == null) "custom/edit" else "custom/edit?drinkId=$drinkId"
}

/** The bottom-bar destinations, in display order. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    AVAILABLE(Routes.AVAILABLE, "Available", Icons.Filled.AutoAwesome),
    LIBRARY(Routes.LIBRARY, "Library", Icons.Filled.LocalBar),
    BOTTLES(Routes.BOTTLES, "Bottles", Icons.Filled.Liquor),
    STATS(Routes.STATS, "Stats", Icons.Filled.Insights),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
