package com.example.smartbartender.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartbartender.SmartBartenderApplication

/** Resolves the app-wide [AppContainer] from within a ViewModel factory initializer. */
val CreationExtras.appContainer: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SmartBartenderApplication).container

/** Convenience wrapper: `containerViewModelFactory { container -> MyViewModel(container.repository) }`. */
inline fun <reified VM : androidx.lifecycle.ViewModel> containerViewModelFactory(
    crossinline create: (AppContainer) -> VM,
) = viewModelFactory {
    initializer { create(appContainer) }
}
