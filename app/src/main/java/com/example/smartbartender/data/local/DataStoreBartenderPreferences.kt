package com.example.smartbartender.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.Favourites
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.PourHistory
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.SlotRack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_bartender_settings")

/**
 * Pour history lives in its own file: it grows to tens of kilobytes, and DataStore rewrites
 * the whole file on every edit — no reason to pay that on each rack or LED toggle.
 */
private val Context.historyStore: DataStore<Preferences> by preferencesDataStore(name = "pour_history")

/** Every store the app has, backed by two DataStore files. */
class DataStoreBartenderPreferences(context: Context) :
    RackStore,
    MachineSettingsStore,
    ActiveJobStore,
    FavouritesStore,
    CustomDrinkStore,
    PourHistoryStore {

    private val dataStore = context.applicationContext.dataStore
    private val historyStore = context.applicationContext.historyStore

    private object Keys {
        val LOADED_BOTTLES = stringSetPreferencesKey("loaded_bottles")
        val SLOT_ASSIGNMENT = stringPreferencesKey("slot_assignment")
        val LED_SHOW_ENABLED = booleanPreferencesKey("led_show_enabled")
        val MACHINE_HOST = stringPreferencesKey("machine_host")
        val MACHINE_PORT = intPreferencesKey("machine_port")
        val MACHINE_ENABLED = booleanPreferencesKey("machine_enabled")
        val ACTIVE_JOB_ID = stringPreferencesKey("active_job_id")
        val FAVOURITES = stringPreferencesKey("favourite_cocktails")
        val POUR_HISTORY = stringPreferencesKey("pour_history")
        val CUSTOM_DRINKS = stringPreferencesKey("custom_drinks")
    }

    private val preferences: Flow<Preferences> = dataStore.data.orEmptyOnIoError()

    // ------------------------------------------------------------------ rack

    override val loadedBottleIds: Flow<Set<String>> = preferences.map { it.loadedBottles() }

    override val slots: Flow<List<String?>> = preferences.map { prefs ->
        SlotRack.reconcile(
            assignment = SlotRack.parse(prefs[Keys.SLOT_ASSIGNMENT].orEmpty()),
            loaded = prefs.loadedBottles(),
        )
    }.distinctUntilChanged()

    override suspend fun setBottleLoaded(bottleId: String, loaded: Boolean): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val current = prefs.loadedBottles()
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

    /** The rack as stored, or the factory rack on first launch, never more than the machine holds. */
    private fun Preferences.loadedBottles(): Set<String> =
        BottleCatalog.clampToCapacity(this[Keys.LOADED_BOTTLES] ?: BottleCatalog.defaultSelection)

    /**
     * The only place the rack is written. Membership and slot order always move together,
     * in one [edit], so a bottle can never be loaded without the machine knowing which pump
     * it is in.
     */
    private fun MutablePreferences.writeRack(loaded: Set<String>) {
        val assignment = SlotRack.parse(this[Keys.SLOT_ASSIGNMENT].orEmpty())
        this[Keys.LOADED_BOTTLES] = loaded
        this[Keys.SLOT_ASSIGNMENT] = SlotRack.encode(SlotRack.reconcile(assignment, loaded))
    }

    // ------------------------------------------------------------------ machine settings

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

    // ------------------------------------------------------------------ active job

    override val activeJobId: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.ACTIVE_JOB_ID]?.takeIf { it.isNotBlank() }
    }.distinctUntilChanged()

    override suspend fun setActiveJobId(jobId: String?) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_JOB_ID] = jobId.orEmpty() }
    }

    // ------------------------------------------------------------------ favourites

    override val favourites: Flow<List<CocktailSummary>> =
        preferences.decodedList(Keys.FAVOURITES, FavouritesCodec::decode)

    override suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) {
        dataStore.edit { prefs ->
            val current = FavouritesCodec.decode(prefs[Keys.FAVOURITES].orEmpty())
            prefs[Keys.FAVOURITES] = FavouritesCodec.encode(Favourites.toggle(current, cocktail, favourite))
        }
    }

    // ------------------------------------------------------------------ custom drinks

    override val customDrinks: Flow<List<CustomDrink>> =
        preferences.decodedList(Keys.CUSTOM_DRINKS, CustomDrinksCodec::decode)

    override suspend fun saveCustomDrink(drink: CustomDrink) {
        dataStore.edit { prefs ->
            val current = CustomDrinksCodec.decode(prefs[Keys.CUSTOM_DRINKS].orEmpty())
            prefs[Keys.CUSTOM_DRINKS] = CustomDrinksCodec.encode(CustomDrinks.upsert(current, drink))
        }
    }

    /** One [edit], so the drink and its favourite disappear together. */
    override suspend fun deleteCustomDrink(id: String) {
        dataStore.edit { prefs ->
            val current = CustomDrinksCodec.decode(prefs[Keys.CUSTOM_DRINKS].orEmpty())
            prefs[Keys.CUSTOM_DRINKS] = CustomDrinksCodec.encode(CustomDrinks.remove(current, id))
            val favourites = FavouritesCodec.decode(prefs[Keys.FAVOURITES].orEmpty())
            prefs[Keys.FAVOURITES] = FavouritesCodec.encode(favourites.filterNot { it.id == id })
        }
    }

    // ------------------------------------------------------------------ pour history

    override val pourHistory: Flow<List<PourRecord>> =
        historyStore.data.orEmptyOnIoError().decodedList(Keys.POUR_HISTORY, PourHistoryCodec::decode)

    override suspend fun recordPour(record: PourRecord) {
        historyStore.edit { prefs ->
            val current = PourHistoryCodec.decode(prefs[Keys.POUR_HISTORY].orEmpty())
            prefs[Keys.POUR_HISTORY] = PourHistoryCodec.encode(PourHistory.append(current, record))
        }
    }

    override suspend fun clearPourHistory() {
        historyStore.edit { prefs -> prefs.remove(Keys.POUR_HISTORY) }
    }
}

/** A corrupt/unreadable file should degrade to defaults, not crash the app. */
private fun Flow<Preferences>.orEmptyOnIoError(): Flow<Preferences> = catch { throwable ->
    if (throwable is IOException) emit(emptyPreferences()) else throw throwable
}

/**
 * The JSON list stored under [key], decoded only when its text changes — not on every write
 * to an unrelated key in the same file.
 */
private fun <T> Flow<Preferences>.decodedList(
    key: Preferences.Key<String>,
    decode: (String) -> List<T>,
): Flow<List<T>> = map { it[key].orEmpty() }.distinctUntilChanged().map(decode)
