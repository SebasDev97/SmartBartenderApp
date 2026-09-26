package com.example.smartbartender.data.local

import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.PourRecord
import kotlinx.coroutines.flow.Flow

/*
 * State that must survive an app restart, split by concern so each class depends only on
 * what it reads. [DataStoreBartenderPreferences] implements them all and is the only
 * implementation that ships; they are interfaces so the pour logic can be driven in a JVM
 * test without a `Context` or a real DataStore.
 */

/** The bottles in the machine's slots. */
interface RackStore {

    /**
     * Ids of the [com.example.smartbartender.domain.model.Bottle]s currently in the machine's
     * slots. Clamped on read as well as on write, so data persisted by an earlier build can
     * never present more bottles than the machine physically has.
     */
    val loadedBottleIds: Flow<Set<String>>

    /**
     * The same bottles as [loadedBottleIds], but in slot order: index 0 is slot 1, which is
     * pump 1 on the machine. A set cannot carry that order, and getting it wrong means
     * pouring the wrong liquid, so it is stored separately and reconciled on read.
     */
    val slots: Flow<List<String?>>

    /**
     * Loads or ejects one bottle. Returns false when nothing changed — most usefully when
     * loading into a rack that is already full.
     */
    suspend fun setBottleLoaded(bottleId: String, loaded: Boolean): Boolean

    /** Replaces the whole rack, trimmed to the machine's slot count. */
    suspend fun setLoadedBottles(bottleIds: Set<String>)
}

/** Where the machine lives, and whether its LED show runs. */
interface MachineSettingsStore {
    val ledShowEnabled: Flow<Boolean>

    val machineAddress: Flow<MachineAddress>

    suspend fun setLedShowEnabled(enabled: Boolean)

    suspend fun setMachineAddress(host: String, port: Int, enabled: Boolean)
}

/** The pour this app started and has not yet dismissed, so it can re-attach after a restart. */
interface ActiveJobStore {
    val activeJobId: Flow<String?>

    suspend fun setActiveJobId(jobId: String?)
}

interface FavouritesStore {
    /** Starred cocktails, most recently added first. */
    val favourites: Flow<List<CocktailSummary>>

    suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean)
}

interface CustomDrinkStore {
    /** Drinks the user made up, most recently created first. */
    val customDrinks: Flow<List<CustomDrink>>

    /** Adds [drink], or replaces the one with the same id. */
    suspend fun saveCustomDrink(drink: CustomDrink)

    /** Deletes the drink and un-stars it, so it never lingers in Favourites. */
    suspend fun deleteCustomDrink(id: String)
}

interface PourHistoryStore {
    /** Every pour this phone started that has ended, oldest first. Feeds the Stats tab. */
    val pourHistory: Flow<List<PourRecord>>

    /** Appends [record]. A job already recorded is ignored, so a replayed frame never double-counts. */
    suspend fun recordPour(record: PourRecord)

    suspend fun clearPourHistory()
}
