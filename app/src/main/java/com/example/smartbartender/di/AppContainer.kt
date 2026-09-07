package com.example.smartbartender.di

import android.content.Context
import com.example.smartbartender.BuildConfig
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.remote.NetworkModule
import com.example.smartbartender.data.repository.CocktailRepository

/**
 * Tiny manual DI container. The app is small enough that a DI framework would cost more
 * than it saves; everything is created once and handed to the ViewModel factories.
 */
class AppContainer(context: Context) {
    val preferences: BartenderPreferences = BartenderPreferences(context)
    val repository: CocktailRepository = CocktailRepository(NetworkModule.createApi(debug = BuildConfig.DEBUG))
}
