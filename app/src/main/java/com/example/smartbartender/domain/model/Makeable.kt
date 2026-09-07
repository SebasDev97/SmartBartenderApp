package com.example.smartbartender.domain.model

/**
 * A cocktail measured against the currently loaded bottles.
 * [missingIngredients] is empty when the machine can pour it right now.
 */
data class MakeableCocktail(
    val cocktail: Cocktail,
    val missingIngredients: List<String>,
) {
    val canMakeNow: Boolean get() = missingIngredients.isEmpty()
}
