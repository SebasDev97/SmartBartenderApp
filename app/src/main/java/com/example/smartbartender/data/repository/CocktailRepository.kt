package com.example.smartbartender.data.repository

import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.domain.model.Availability
import com.example.smartbartender.domain.model.AvailabilityResult
import com.example.smartbartender.domain.model.Bottle
import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.util.runCatchingCancellable
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
 * TheCocktailDB answered, but without the drink that was asked for. The message is for logs;
 * the screen words it from the type (see `LoadError.NotFound`).
 */
class CocktailNotFoundException(message: String) : NoSuchElementException(message)

class CocktailRepository(private val api: CocktailApi) {

    private val cacheLock = Mutex()

    /** Serialises catalog loading so two tabs opening at once do not fetch it twice. */
    private val catalogLock = Mutex()
    private val cocktailsById = mutableMapOf<String, Cocktail>()
    private val candidatesByIngredient = mutableMapOf<String, List<CocktailSummary>>()

    /** First-letter pages fetched so far. The book counts as whole only once all 26 are in. */
    private val catalogPages = mutableMapOf<Char, List<Cocktail>>()
    private var recipeCatalog: List<Cocktail>? = null
    private var lastAvailability: Pair<Set<String>, AvailabilityResult>? = null

    /** Bounds parallel requests so we stay polite to the public API. */
    private val requestLimiter = Semaphore(permits = 6)

    suspend fun searchByName(query: String): List<Cocktail> {
        val drinks = requestLimiter.withPermit { api.searchByName(query).drinks.map { it.toDomain() } }
        cacheLock.withLock { drinks.forEach { cocktailsById[it.id] = it } }
        return drinks
    }

    suspend fun cocktailById(id: String): Cocktail {
        cacheLock.withLock { cocktailsById[id] }?.let { return it }
        val cocktail = requestLimiter.withPermit { api.lookupById(id).drinks.firstOrNull()?.toDomain() }
            ?: throw CocktailNotFoundException("No cocktail found with id $id")
        cacheLock.withLock { cocktailsById[id] = cocktail }
        return cocktail
    }

    suspend fun randomCocktail(): Cocktail {
        val cocktail = requestLimiter.withPermit { api.random().drinks.firstOrNull()?.toDomain() }
            ?: throw CocktailNotFoundException("The bar returned no random cocktail")
        cacheLock.withLock { cocktailsById[cocktail.id] = cocktail }
        return cocktail
    }

    /** The whole recipe book, sorted by name, for the Library before anything is searched. */
    suspend fun browse(): List<Cocktail> = withContext(Dispatchers.Default) {
        recipeCatalog().recipes.sortedBy { it.name }
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
     * Each recipe is then scored by [Availability]. The result is cached per set of loaded
     * bottles, so changing the rack recomputes it and returning to the tab does not — unless
     * part of the book failed to load, in which case the next call tries those pages again.
     */
    suspend fun findMakeable(loadedBottles: List<Bottle>): AvailabilityResult = withContext(Dispatchers.Default) {
        val key = loadedBottles.map { it.id }.toSet()
        cacheLock.withLock { lastAvailability }
            ?.let { (cachedKey, cachedResult) -> if (cachedKey == key) return@withContext cachedResult }

        val catalog = recipeCatalog()
        val filtered = filterCandidates(loadedBottles)
        val extraIds = filtered.map { it.id }.toSet() - catalog.recipes.map { it.id }.toSet()
        val extras = supervisorScope {
            extraIds.take(MAX_EXTRA_LOOKUPS)
                .map { id -> async { runCatchingCancellable { cocktailById(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }

        val result = Availability.classify((catalog.recipes + extras).distinctBy { it.id }, key)
        if (catalog.isComplete) cacheLock.withLock { lastAvailability = key to result }
        result
    }

    /**
     * The full recipe book, across the 26 first-letter pages.
     *
     * A page that fails is left out for now and fetched again on the next call, so a flaky
     * moment degrades the answer once instead of for the rest of the process. The book is
     * cached whole, and never fetched again, once every page is in. Only a call that ends
     * with no page at all throws.
     */
    private suspend fun recipeCatalog(): Catalog = catalogLock.withLock {
        cacheLock.withLock { recipeCatalog }?.let { return@withLock Catalog(it, isComplete = true) }

        val missing = cacheLock.withLock { LETTERS.filterNot { it in catalogPages } }
        val fetched: List<Pair<Char, List<Cocktail>>> = supervisorScope {
            missing.map { letter ->
                async {
                    runCatchingCancellable {
                        requestLimiter.withPermit {
                            letter to api.searchByFirstLetter(letter.toString()).drinks.map { it.toDomain() }
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        cacheLock.withLock {
            catalogPages.putAll(fetched)
            if (catalogPages.isEmpty()) throw IOException("Could not reach TheCocktailDB")
            val recipes = catalogPages.values.flatten().distinctBy { it.id }
            recipes.forEach { cocktailsById[it.id] = it }
            val isComplete = catalogPages.size == LETTERS.size
            if (isComplete) recipeCatalog = recipes
            Catalog(recipes, isComplete)
        }
    }

    /** `filter.php?i=` for every loaded bottle, unioned. */
    private suspend fun filterCandidates(loadedBottles: List<Bottle>): List<CocktailSummary> {
        val perBottle: List<List<CocktailSummary>?> = supervisorScope {
            loadedBottles
                .map { bottle -> async { runCatchingCancellable { candidatesFor(bottle) }.getOrNull() } }
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

    /** The recipe book as far as it loaded. */
    private class Catalog(val recipes: List<Cocktail>, val isComplete: Boolean)

    private companion object {
        /** Safety valve on the per-bottle `filter.php` ids we still have to look up one by one. */
        const val MAX_EXTRA_LOOKUPS = 60

        /** TheCocktailDB's first-letter pages. */
        val LETTERS = ('a'..'z').toList()
    }
}
