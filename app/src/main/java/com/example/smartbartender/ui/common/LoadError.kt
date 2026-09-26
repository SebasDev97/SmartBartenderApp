package com.example.smartbartender.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smartbartender.R
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a screen's recipes could not be shown. [text] words it for a guest standing at the machine. */
sealed interface LoadError {
    data object Offline : LoadError
    data object Timeout : LoadError

    /** A custom drink that was deleted while its screen was open. */
    data object Deleted : LoadError

    data class Other(val detail: String?) : LoadError
}

fun Throwable.toLoadError(): LoadError = when (this) {
    is UnknownHostException -> LoadError.Offline
    is SocketTimeoutException -> LoadError.Timeout
    else -> LoadError.Other(message?.takeIf { it.isNotBlank() })
}

@Composable
fun LoadError.text(): String = when (this) {
    LoadError.Offline -> stringResource(R.string.error_offline)
    LoadError.Timeout -> stringResource(R.string.error_recipes_timeout)
    LoadError.Deleted -> stringResource(R.string.error_drink_deleted)
    is LoadError.Other -> detail ?: stringResource(R.string.error_recipes_generic)
}
