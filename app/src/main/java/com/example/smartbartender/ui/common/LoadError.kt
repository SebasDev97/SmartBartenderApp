package com.example.smartbartender.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smartbartender.R
import com.example.smartbartender.data.repository.CocktailNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a screen's recipes could not be shown. [text] words it for a guest standing at the
 * machine; no exception message ever reaches the screen, since those are English and for logs.
 */
sealed interface LoadError {
    data object Offline : LoadError
    data object Timeout : LoadError

    /** TheCocktailDB answered, but not with the drink asked for. */
    data object NotFound : LoadError

    /** The request failed on its way: a dropped connection, or no recipe page loading at all. */
    data object Unreachable : LoadError

    /** A custom drink that was deleted while its screen was open. */
    data object Deleted : LoadError

    data object Other : LoadError
}

fun Throwable.toLoadError(): LoadError = when (this) {
    is UnknownHostException -> LoadError.Offline
    is SocketTimeoutException -> LoadError.Timeout
    is CocktailNotFoundException -> LoadError.NotFound
    // After the two above, which are IOExceptions too.
    is IOException -> LoadError.Unreachable
    else -> LoadError.Other
}

@Composable
fun LoadError.text(): String = when (this) {
    LoadError.Offline -> stringResource(R.string.error_offline)
    LoadError.Timeout -> stringResource(R.string.error_recipes_timeout)
    LoadError.NotFound -> stringResource(R.string.error_recipe_not_found)
    LoadError.Unreachable -> stringResource(R.string.error_recipes_unreachable)
    LoadError.Deleted -> stringResource(R.string.error_drink_deleted)
    LoadError.Other -> stringResource(R.string.error_recipes_generic)
}
