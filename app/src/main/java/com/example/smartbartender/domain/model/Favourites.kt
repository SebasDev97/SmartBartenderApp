package com.example.smartbartender.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The cocktails the user has starred, newest first.
 *
 * Each entry keeps the name and thumbnail alongside the id, so the Favourites list draws
 * straight from storage instead of paying one `lookup.php?i=` per drink against the public
 * API — and still shows something when that API is unreachable.
 */
object Favourites {

    @Serializable
    private data class Entry(val id: String, val name: String, val thumbUrl: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    /** Adds [cocktail] to the front, or removes it. Adding one already held is a no-op. */
    fun toggle(
        favourites: List<CocktailSummary>,
        cocktail: CocktailSummary,
        favourite: Boolean,
    ): List<CocktailSummary> {
        val without = favourites.filterNot { it.id == cocktail.id }
        return when {
            !favourite -> without
            without.size != favourites.size -> favourites
            else -> listOf(cocktail) + favourites
        }
    }

    /** Name search over favourites, held locally, with the same folding as ingredients. */
    fun filter(favourites: List<CocktailSummary>, query: String): List<CocktailSummary> {
        val needle = query.normalizedIngredient()
        if (needle.isEmpty()) return favourites
        return favourites.filter { needle in it.name.normalizedIngredient() }
    }

    fun encode(favourites: List<CocktailSummary>): String =
        json.encodeToString(serializer, favourites.map { Entry(it.id, it.name, it.thumbUrl) })

    /** A blank or unreadable value degrades to no favourites rather than a crash. */
    fun parse(stored: String): List<CocktailSummary> {
        if (stored.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { CocktailSummary(it.id, it.name, it.thumbUrl) }
    }
}
