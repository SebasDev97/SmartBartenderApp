package com.example.smartbartender.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets cancellation through. A load that was cancelled because a newer
 * one started must not report a failure: its state update is not itself cancellable, so the
 * error would land after the replacement has already cleared it and strand the screen.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    val result = runCatching(block)
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    return result
}
