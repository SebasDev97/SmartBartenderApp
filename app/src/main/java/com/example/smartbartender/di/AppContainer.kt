package com.example.smartbartender.di

import android.content.Context
import com.example.smartbartender.BuildConfig
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.hardware.HttpBartenderMachine
import com.example.smartbartender.data.hardware.MachineNetworkModule
import com.example.smartbartender.data.hardware.PourRecorder
import com.example.smartbartender.data.hardware.RackSync
import com.example.smartbartender.data.local.ActiveJobStore
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.DataStoreBartenderPreferences
import com.example.smartbartender.data.local.FavouritesStore
import com.example.smartbartender.data.local.MachineSettingsStore
import com.example.smartbartender.data.local.PourHistoryStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.data.remote.NetworkModule
import com.example.smartbartender.data.repository.CocktailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    private val preferences = DataStoreBartenderPreferences(context)
    val rack: RackStore = preferences
    val machineSettings: MachineSettingsStore = preferences
    val activeJob: ActiveJobStore = preferences
    val favourites: FavouritesStore = preferences
    val customDrinks: CustomDrinkStore = preferences
    val pourHistory: PourHistoryStore = preferences

    val repository: CocktailRepository = CocktailRepository(NetworkModule.createApi(debug = BuildConfig.DEBUG))

    /**
     * Application-lifetime: the machine's event socket has to outlive
     * the detail screen, or leaving it mid-pour would blind the app to the rest of the pour.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpMachine: HttpBartenderMachine = MachineNetworkModule.createClient(debug = BuildConfig.DEBUG)
        .let { client ->
            HttpBartenderMachine(
                api = MachineNetworkModule.createApi(client),
                client = client,
                rack = rack,
                settings = machineSettings,
                scope = appScope,
            )
        }
    val machine: BartenderMachine = httpMachine

    /** Feeds the Stats tab. Same app-lifetime scope, so a pour is counted even off-screen. */
    private val pourRecorder = PourRecorder(machine, activeJob, pourHistory, rack, appScope)

    private val rackSync = RackSync(rack, machine, appScope)

    /** Starts the app-lifetime work: the machine link, the Stats recorder and the rack sync. */
    fun start() {
        httpMachine.start()
        pourRecorder.start()
        rackSync.start()
    }
}
