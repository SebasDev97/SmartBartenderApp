package com.example.smartbartender.data.repository

import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.MakeableCocktail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/**
 * Single source of truth for cocktail data.
 *
 * TheCocktailDB has no "what can I make with these bottles" endpoint, so [findMakeable]
 * builds the answer client-side. Everything fetched is cached in memory for the process
 * lifetime, so returning to the Available tab is instant after the first load.
 */
class CocktailRepository(private val api: CocktailApi) {

    private val cacheLock = Mutex()

    /** Serialises catalog loading so two tabs opening at once do not fetch it twice. */
    private val catalogLock = Mutex()
    private val cocktailsById = mutableMapOf<String, Cocktail>()
    private val candidatesByIngredient = mutableMapOf<String, List<CocktailSummary>>()
    private var recipeCatalog: List<Cocktail>? = null
    private var lastAvailability: Pair<Set<String>, AvailabilityResult>? = null

    /** Bounds parallel requests so we stay polite to the public API. */
    private val requestLimiter = Semaphore(permits = 6)

    data class AvailabilityResult(
        val canMakeNow: List<MakeableCocktail>,
        val almost: List<MakeableCocktail>,
    )

    suspend fun searchByName(query: String): List<Cocktail> {
        val drinks = requestLimiter.withPermit { api.searchByName(query).drinks.map { it.toDomain() } }
        cacheLock.withLock { drinks.forEach { cocktailsById[it.id] = it } }
        return drinks
    }

    suspend fun cocktailById(id: String): Cocktail {
        cacheLock.withLock { cocktailsById[id] }?.let { return it }
        val cocktail = requestLimiter.withPermit { api.lookupById(id).drinks.firstOrNull()?.toDomain() }
            ?: throw NoSuchElementException("No cocktail found with id $id")
        cacheLock.withLock { cocktailsById[id] = cocktail }
        return cocktail
    }

    suspend fun randomCocktail(): Cocktail {
        val cocktail = requestLimiter.withPermit { api.random().drinks.firstOrNull()?.toDomain() }
            ?: throw NoSuchElementException("The bar returned no random cocktail")
        cacheLock.withLock { cocktailsById[cocktail.id] = cocktail }
        return cocktail
    }

    /**
     * A browsable slice of the catalog for the Library landing state.
     */
    suspend fun browse(): List<Cocktail> = withContext(Dispatchers.Default) {
        recipeCatalog().sortedBy { it.name }
    }

    /**
     * Works out what the machine can pour with [loadedBottles].
     *
     * Two sources feed the candidate pool:
     *  - `filter.php?i={bottle}` per loaded bottle, exactly as the machine spec describes.
     *    Ids it returns that we do not already hold are fetched with `lookup.php?i=`.
     *  - the bulk recipe catalog (`search.php?f={a..z}`), because the public test key caps
     *    `filter.php?i=` at a single drink per ingredient — without this the answer would be
     *    a handful of drinks instead of the whole book.
     *
     * Each recipe is then scored against the loaded bottles plus the pantry staples that are
     * always on the bar top (ice, sugar, garnishes).
     */
    suspend fun findMakeable(loadedBottles: List<Bottle>): AvailabilityResult = withContext(Dispatchers.Default) {
        val key = loadedBottles.map { it.id }.toSet()
        cacheLock.withLock { lastAvailability }
            ?.let { (cachedKey, cachedResult) -> if (cachedKey == key) return@withContext cachedResult }

        val catalog = recipeCatalog()
        val filtered = filterCandidates(loadedBottles)
        val extraIds = filtered.map { it.id }.toSet() - catalog.map { it.id }.toSet()
        val extras = supervisorScope {
            extraIds.take(MAX_EXTRA_LOOKUPS)
                .map { id -> async { runCatching { cocktailById(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }

        val recipes = (catalog + extras).distinctBy { it.id }
        val evaluated = recipes
            .filter { it.ingredients.isNotEmpty() }
            .map { cocktail -> evaluate(cocktail, key) }

        val result = AvailabilityResult(
            canMakeNow = evaluated.filter { it.canMakeNow }.sortedBy { it.cocktail.name },
            almost = evaluated
                .filter { it.missingIngredients.size == 1 }
                .sortedWith(compareBy({ it.missingIngredients.first() }, { it.cocktail.name })),
        )
        cacheLock.withLock { lastAvailability = key to result }
        result
    }

    /** Full recipe book, fetched once per process across the 26 first-letter pages. */
    private suspend fun recipeCatalog(): List<Cocktail> = catalogLock.withLock {
        cacheLock.withLock { recipeCatalog }?.let { return@withLock it }

        val pages: List<List<Cocktail>?> = supervisorScope {
            ('a'..'z').map { letter ->
                async {
                    runCatching {
                        requestLimiter.withPermit {
                            api.searchByFirstLetter(letter.toString()).drinks.map { it.toDomain() }
                        }
                    }.getOrNull()
                }
            }.awaitAll()
        }
        if (pages.all { it == null }) throw IOException("Could not reach TheCocktailDB")

        val catalog = pages.filterNotNull().flatten().distinctBy { it.id }
        cacheLock.withLock {
            recipeCatalog = catalog
            catalog.forEach { cocktailsById[it.id] = it }
        }
        catalog
    }

    /** `filter.php?i=` for every loaded bottle, unioned. */
    private suspend fun filterCandidates(loadedBottles: List<Bottle>): List<CocktailSummary> {
        val perBottle: List<List<CocktailSummary>?> = supervisorScope {
            loadedBottles
                .map { bottle -> async { runCatching { candidatesFor(bottle) }.getOrNull() } }
                .awaitAll()
        }
        return perBottle.filterNotNull().flatten().distinctBy { it.id }
    }

    private suspend fun candidatesFor(bottle: Bottle): List<CocktailSummary> {
        cacheLock.withLock { candidatesByIngredient[bottle.apiName] }?.let { return it }
        val summaries = requestLimiter.withPermit {
            api.filterByIngredient(bottle.apiName).drinks.map { it.toDomain() }
        }
        cacheLock.withLock { candidatesByIngredient[bottle.apiName] = summaries }
        return summaries
    }

    private fun evaluate(cocktail: Cocktail, loadedBottleIds: Set<String>): MakeableCocktail {
        val missing = cocktail.ingredients
            .filterNot { ingredient -> isAvailable(ingredient.name, loadedBottleIds) }
            .map { it.name }
            .distinct()
        return MakeableCocktail(cocktail = cocktail, missingIngredients = missing)
    }

    private fun isAvailable(ingredientName: String, loadedBottleIds: Set<String>): Boolean {
        if (BottleCatalog.isPantryStaple(ingredientName)) return true
        val bottleId = BottleCatalog.resolveBottle(ingredientName)?.id ?: return false
        return bottleId in loadedBottleIds
    }

    /** Drops the derived availability cache; recipes themselves stay cached. */
    suspend fun invalidateAvailability() {
        cacheLock.withLock { lastAvailability = null }
    }

    private companion object {
        /** Safety valve on the per-bottle `filter.php` ids we still have to look up one by one. */
        const val MAX_EXTRA_LOOKUPS = 60
    }
}
