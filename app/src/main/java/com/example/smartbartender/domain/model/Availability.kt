package com.example.smartbartender.domain.model

/** Every recipe the rack can pour now, and those exactly one bottle short. */
data class AvailabilityResult(
    val canMakeNow: List<MakeableCocktail>,
    val almost: List<MakeableCocktail>,
)

/**
 * Scores recipes against the loaded bottles: nothing missing means it can be made now, one
 * ingredient short means almost, and anything further is dropped. Online recipes and custom
 * drinks both go through here, so the two lists can never disagree on what "almost" means.
 */
object Availability {

    /** A recipe missing more ingredients than this is not worth showing. */
    const val MAX_MISSING = 1

    /** Each scorable recipe with what it is missing, in input order. Unpourable ones are dropped. */
    fun score(recipes: List<Cocktail>, loadedBottleIds: Set<String>): List<MakeableCocktail> =
        recipes
            .filter { it.ingredients.isNotEmpty() }
            .map { MakeableCocktail(it, missingIngredients(it, loadedBottleIds)) }
            .filter { it.missingIngredients.size <= MAX_MISSING }

    /** [score], split into the two sections and sorted for display. */
    fun classify(recipes: List<Cocktail>, loadedBottleIds: Set<String>): AvailabilityResult {
        val scored = score(recipes, loadedBottleIds)
        return AvailabilityResult(
            canMakeNow = scored.filter { it.canMakeNow }.sortedBy { it.cocktail.name },
            almost = scored
                .filterNot { it.canMakeNow }
                .sortedWith(compareBy({ it.missingIngredients.first() }, { it.cocktail.name })),
        )
    }

    private fun missingIngredients(cocktail: Cocktail, loadedBottleIds: Set<String>): List<String> =
        cocktail.ingredients
            .filterNot { isAvailable(it.name, loadedBottleIds) }
            .map { it.name }
            .distinct()

    /** Pantry staples are always on the bar top; anything else needs its bottle in the rack. */
    private fun isAvailable(ingredientName: String, loadedBottleIds: Set<String>): Boolean {
        if (BottleCatalog.isPantryStaple(ingredientName)) return true
        val bottleId = BottleCatalog.resolveBottle(ingredientName)?.id ?: return false
        return bottleId in loadedBottleIds
    }
}
