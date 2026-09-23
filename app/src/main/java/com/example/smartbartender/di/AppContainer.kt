package com.example.smartbartender.di

import android.content.Context
import com.example.smartbartender.BuildConfig
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.hardware.HttpBartenderMachine
import com.example.smartbartender.data.hardware.MachineNetworkModule
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.data.local.DataStoreBartenderPreferences
import com.example.smartbartender.data.remote.NetworkModule
import com.example.smartbartender.data.repository.CocktailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val preferences: BartenderPreferences = DataStoreBartenderPreferences(context)
    val repository: CocktailRepository = CocktailRepository(NetworkModule.createApi(debug = BuildConfig.DEBUG))

    /**
     * Application-lifetime: the machine's event socket has to outlive
     * the detail screen, or leaving it mid-pour would blind the app to the rest of the pour.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val machine: BartenderMachine = MachineNetworkModule.createClient(debug = BuildConfig.DEBUG)
        .let { client ->
            HttpBartenderMachine(
                api = MachineNetworkModule.createApi(client),
                client = client,
                preferences = preferences,
                scope = appScope,
            )
        }
}
