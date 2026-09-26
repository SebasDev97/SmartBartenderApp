package com.example.smartbartender.domain.model

/** The cocktails the user has starred, newest first. */
object Favourites {

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
}
