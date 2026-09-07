package com.example.smartbartender.domain.model

/**
 * A bottle slot the machine can be loaded with.
 *
 * [apiName] is the exact ingredient name TheCocktailDB knows (used for `filter.php?i=`),
 * [aliases] are the other spellings the API uses for the same liquid inside recipes
 * (e.g. a recipe may ask for "Light rum" while another asks for "White rum").
 */
data class Bottle(
    val id: String,
    val displayName: String,
    val apiName: String,
    val category: BottleCategory,
    val aliases: List<String> = emptyList(),
) {
    /** Every spelling that should be considered "this bottle". */
    val matchNames: List<String> get() = listOf(apiName, displayName) + aliases
}

enum class BottleCategory(val label: String) {
    SPIRIT("Spirits"),
    LIQUEUR("Liqueurs"),
    JUICE("Juices"),
    MIXER("Mixers & syrups"),
    DAIRY("Dairy & cream"),
}
