package com.example.smartbartender.di

import android.content.Context
import com.example.smartbartender.BuildConfig
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.remote.NetworkModule
import com.example.smartbartender.data.repository.CocktailRepository

class AppContainer(context: Context) {
    val preferences: BartenderPreferences = BartenderPreferences(context)
    val repository: CocktailRepository = CocktailRepository(NetworkModule.createApi(debug = BuildConfig.DEBUG))
}
