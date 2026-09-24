package com.example.smartbartender.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.Favourites
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.SlotRack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_bartender_settings")

/**
 * State that must survive an app restart: the rack, the LED show switch, where the machine
 * lives on the network, which pour is in flight, and the user's favourite cocktails.
 *
 * An interface so the pour logic can be driven in a JVM test without a `Context` or a real
 * DataStore. [DataStoreBartenderPreferences] is the only implementation that ships.
 */
interface BartenderPreferences {

    /**
     * Ids of the [com.example.smartbartender.domain.model.Bottle]s currently in the machine's
     * four slots. Clamped on read as well as on write, so data persisted by an earlier build
     * can never present more bottles than the machine physically has.
     */
    val loadedBottleIds: Flow<Set<String>>

    /**
     * The same bottles as [loadedBottleIds], but in slot order: index 0 is slot 1, which is
     * pump 1 on the machine. A set cannot carry that order, and getting it wrong means
     * pouring the wrong liquid, so it is stored separately and reconciled on read.
     */
    val slots: Flow<List<String?>>

    val ledShowEnabled: Flow<Boolean>

    val machineAddress: Flow<MachineAddress>

    /** The pour this app started and has not yet dismissed, so it can re-attach after a restart. */
    val activeJobId: Flow<String?>

    /** Starred cocktails, most recently added first. */
    val favourites: Flow<List<CocktailSummary>>

    suspend fun setBottleLoaded(bottleId: String, loaded: Boolean): Boolean

    suspend fun setLoadedBottles(bottleIds: Set<String>)

    suspend fun setSlots(slots: List<String?>)

    suspend fun setLedShowEnabled(enabled: Boolean)

    suspend fun setMachineAddress(host: String, port: Int, enabled: Boolean)

    suspend fun setActiveJobId(jobId: String?)

    suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean)
}

class DataStoreBartenderPreferences(context: Context) : BartenderPreferences {

    private val dataStore = context.applicationContext.dataStore

    private object Keys {
        val LOADED_BOTTLES = stringSetPreferencesKey("loaded_bottles")
        val SLOT_ASSIGNMENT = stringPreferencesKey("slot_assignment")
        val LED_SHOW_ENABLED = booleanPreferencesKey("led_show_enabled")
        val MACHINE_HOST = stringPreferencesKey("machine_host")
        val MACHINE_PORT = intPreferencesKey("machine_port")
        val MACHINE_ENABLED = booleanPreferencesKey("machine_enabled")
        val ACTIVE_JOB_ID = stringPreferencesKey("active_job_id")
        val FAVOURITES = stringPreferencesKey("favourite_cocktails")
    }

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { throwable ->
            // A corrupt/unreadable file should degrade to defaults, not crash the app.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    override val loadedBottleIds: Flow<Set<String>> = preferences.map { prefs ->
        BottleCatalog.clampToCapacity(prefs[Keys.LOADED_BOTTLES] ?: BottleCatalog.defaultSelection)
    }

    override val slots: Flow<List<String?>> = preferences.map { prefs ->
        SlotRack.reconcile(
            assignment = SlotRack.parse(prefs[Keys.SLOT_ASSIGNMENT].orEmpty()),
            loaded = BottleCatalog.clampToCapacity(
                prefs[Keys.LOADED_BOTTLES] ?: BottleCatalog.defaultSelection,
            ),
        )
    }.distinctUntilChanged()

    override val ledShowEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.LED_SHOW_ENABLED] ?: true
    }

    override val machineAddress: Flow<MachineAddress> = preferences.map { prefs ->
        MachineAddress(
            host = prefs[Keys.MACHINE_HOST].orEmpty(),
            port = prefs[Keys.MACHINE_PORT] ?: MachineAddress.DEFAULT_PORT,
            enabled = prefs[Keys.MACHINE_ENABLED] ?: false,
        )
    }.distinctUntilChanged()

    override val activeJobId: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.ACTIVE_JOB_ID]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged()

    override val favourites: Flow<List<CocktailSummary>> = preferences.map { prefs ->
        Favourites.parse(prefs[Keys.FAVOURITES].orEmpty())
    }.distinctUntilChanged()

    override suspend fun setBottleLoaded(bottleId: String, loaded: Boolean): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val current = BottleCatalog.clampToCapacity(
                prefs[Keys.LOADED_BOTTLES] ?: BottleCatalog.defaultSelection,
            )
            val updated = when {
                !loaded -> current - bottleId
                bottleId in current -> current
                current.size >= BottleCatalog.MAX_SLOTS -> current
                else -> current + bottleId
            }
            changed = updated != current
            prefs.writeRack(updated)
        }
        return changed
    }

    override suspend fun setLoadedBottles(bottleIds: Set<String>) {
        dataStore.edit { prefs -> prefs.writeRack(BottleCatalog.clampToCapacity(bottleIds)) }
    }

    /**
     * Writes the rack and its slot order together. One [edit] block, so the set of loaded
     * bottles and the slot they sit in can never drift apart.
     */
    override suspend fun setSlots(slots: List<String?>) {
        dataStore.edit { prefs ->
            prefs.writeRack(
                loaded = BottleCatalog.clampToCapacity(slots.filterNotNull().toSet()),
                assignment = slots,
            )
        }
    }

    /**
     * The only place the rack is written. Membership and slot order always move together,
     * so a bottle can never be loaded without the machine knowing which pump it is in.
     */
    private fun MutablePreferences.writeRack(
        loaded: Set<String>,
        assignment: List<String?> = SlotRack.parse(this[Keys.SLOT_ASSIGNMENT].orEmpty()),
    ) {
        val reconciled = SlotRack.reconcile(assignment, loaded)
        this[Keys.LOADED_BOTTLES] = loaded
        this[Keys.SLOT_ASSIGNMENT] = SlotRack.encode(reconciled)
    }

    override suspend fun setLedShowEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.LED_SHOW_ENABLED] = enabled }
    }

    override suspend fun setMachineAddress(host: String, port: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.MACHINE_HOST] = host.trim()
            prefs[Keys.MACHINE_PORT] = port
            prefs[Keys.MACHINE_ENABLED] = enabled
        }
    }

    override suspend fun setActiveJobId(jobId: String?) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_JOB_ID] = jobId.orEmpty() }
    }

    override suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) {
        dataStore.edit { prefs ->
            val current = Favourites.parse(prefs[Keys.FAVOURITES].orEmpty())
            prefs[Keys.FAVOURITES] = Favourites.encode(Favourites.toggle(current, cocktail, favourite))
        }
    }
}
