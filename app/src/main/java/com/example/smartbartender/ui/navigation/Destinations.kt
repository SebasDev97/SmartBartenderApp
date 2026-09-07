package com.example.smartbartender.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.graphics.vector.ImageVector

/** Every screen the app can show. */
object Routes {
    const val AVAILABLE = "available"
    const val LIBRARY = "library"
    const val BOTTLES = "bottles"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{cocktailId}"

    fun detail(cocktailId: String) = "detail/$cocktailId"
}

/** The four bottom-bar destinations, in display order. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    AVAILABLE(Routes.AVAILABLE, "Available", Icons.Filled.AutoAwesome),
    LIBRARY(Routes.LIBRARY, "Library", Icons.Filled.LocalBar),
    BOTTLES(Routes.BOTTLES, "Bottles", Icons.Filled.Liquor),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
