package com.example.smartbartender.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether anything collects this state right now, emitted on change. For a ViewModel's
 * `uiState` that means its screen is at least started: screens collect it with
 * `collectAsStateWithLifecycle`, which stops when the app goes to the background.
 */
fun MutableStateFlow<*>.isObserved(): Flow<Boolean> = subscriptionCount.map { it > 0 }.distinctUntilChanged()
