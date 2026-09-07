package com.example.smartbartender.domain.model

/** A single ingredient line of a recipe, e.g. "Tequila" + "1 1/2 oz". */
data class RecipeIngredient(
    val name: String,
    val measure: String?,
)

/** Lightweight cocktail entry as returned by search/filter endpoints. */
data class CocktailSummary(
    val id: String,
    val name: String,
    val thumbUrl: String?,
)

/** Full recipe as returned by lookup.php / search.php. */
data class Cocktail(
    val id: String,
    val name: String,
    val thumbUrl: String?,
    val category: String?,
    val alcoholic: String?,
    val glass: String?,
    val instructions: String?,
    val ingredients: List<RecipeIngredient>,
) {
    val summary: CocktailSummary get() = CocktailSummary(id, name, thumbUrl)
}
