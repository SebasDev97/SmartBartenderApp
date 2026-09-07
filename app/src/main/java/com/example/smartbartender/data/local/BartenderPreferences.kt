package com.example.smartbartender.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartbartender.domain.model.BottleCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_bartender_settings")

/** Machine state that must survive an app restart: loaded bottles and the LED show switch. */
class BartenderPreferences(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    private object Keys {
        val LOADED_BOTTLES = stringSetPreferencesKey("loaded_bottles")
        val LED_SHOW_ENABLED = booleanPreferencesKey("led_show_enabled")
    }

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { throwable ->
            // A corrupt/unreadable file should degrade to defaults, not crash the app.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    /**
     * Ids of the [com.example.smartbartender.domain.model.Bottle]s currently in the machine's
     * four slots. Clamped on read as well as on write, so data persisted by an earlier build
     * can never present more bottles than the machine physically has.
     */
    val loadedBottleIds: Flow<Set<String>> = preferences.map { prefs ->
        BottleCatalog.clampToCapacity(prefs[Keys.LOADED_BOTTLES] ?: BottleCatalog.defaultSelection)
    }

    val ledShowEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.LED_SHOW_ENABLED] ?: true
    }

    /**
     * Loads or ejects one bottle. Loading is refused when all slots are taken — on the real
     * machine you have to pull a bottle out before another one fits.
     *
     * @return true when the rack changed.
     */
    suspend fun setBottleLoaded(bottleId: String, loaded: Boolean): Boolean {
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
            prefs[Keys.LOADED_BOTTLES] = updated
        }
        return changed
    }

    suspend fun setLoadedBottles(bottleIds: Set<String>) {
        dataStore.edit { prefs -> prefs[Keys.LOADED_BOTTLES] = BottleCatalog.clampToCapacity(bottleIds) }
    }

    suspend fun setLedShowEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.LED_SHOW_ENABLED] = enabled }
    }
}
