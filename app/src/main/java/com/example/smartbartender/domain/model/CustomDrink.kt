package com.example.smartbartender.domain.model

import java.util.UUID

/** One line of a custom drink: a catalog bottle and exactly how much of it to pour. */
data class CustomItem(val bottleId: String, val ml: Int)

/**
 * A drink the user made up, saved on the phone.
 *
 * Items are stored by bottle, never by slot, so a drink survives the rack being rearranged:
 * the Pi resolves bottle ids to pumps itself. List order is pour order.
 */
data class CustomDrink(
    val id: String,
    val name: String,
    val emoji: String,
    val colorArgb: Long,
    val notes: String = "",
    val items: List<CustomItem>,
) {
    val totalMl: Int get() = items.sumOf { it.ml }

    val look: DrinkLook get() = DrinkLook(emoji, colorArgb)
}

/** What stands in for a photo on a drink that has none: an emoji on a colour. */
data class DrinkLook(val emoji: String, val colorArgb: Long)

/** Something that stops a custom drink from being saved. The editor words each one. */
sealed interface DrinkProblem {
    data object NoName : DrinkProblem
    data object NoItems : DrinkProblem
    data class TooManyItems(val maxItems: Int) : DrinkProblem
    data object BottleMissing : DrinkProblem
    data object BottleRepeated : DrinkProblem
    data class VolumeOutOfRange(val minMl: Int, val maxMl: Int) : DrinkProblem
    data class OverGlass(val totalMl: Int, val glassMl: Int) : DrinkProblem
}

/**
 * A custom drink presented as an ordinary [Cocktail], so the detail screen, favourites, the
 * pour planner and stats need no second code path. Each measure is written as `"N ml"`,
 * which [MeasureParser] reads back as exactly N millilitres.
 */
fun CustomDrink.toCocktail(): Cocktail = Cocktail(
    id = id,
    name = name,
    thumbUrl = null,
    category = CustomDrinks.CATEGORY,
    alcohol = null,
    glass = null,
    instructions = notes.takeIf { it.isNotBlank() },
    ingredients = items.mapNotNull { item ->
        BottleCatalog.byId(item.bottleId)?.let { RecipeIngredient(it.displayName, "${item.ml} ml") }
    },
    look = look,
)

object CustomDrinks {
    const val ID_PREFIX = "custom-"

    const val CATEGORY = "Custom"

    const val MIN_ITEM_ML = 1

    fun isCustomId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

    fun newId(): String = ID_PREFIX + UUID.randomUUID().toString()

    /** Replaces the drink with the same id in place, or adds a new one to the front. */
    fun upsert(drinks: List<CustomDrink>, drink: CustomDrink): List<CustomDrink> =
        if (drinks.any { it.id == drink.id }) {
            drinks.map { if (it.id == drink.id) drink else it }
        } else {
            listOf(drink) + drinks
        }

    fun remove(drinks: List<CustomDrink>, id: String): List<CustomDrink> = drinks.filterNot { it.id == id }

    /**
     * Everything that stops [drink] from being saved; empty means it is valid. [glassMl] is
     * the glass the machine reports, or its default.
     */
    fun validate(drink: CustomDrink, glassMl: Double): List<DrinkProblem> = buildList {
        if (drink.name.isBlank()) add(DrinkProblem.NoName)
        if (drink.items.isEmpty()) add(DrinkProblem.NoItems)
        if (drink.items.size > BottleCatalog.MAX_SLOTS) add(DrinkProblem.TooManyItems(BottleCatalog.MAX_SLOTS))
        if (drink.items.any { BottleCatalog.byId(it.bottleId) == null }) add(DrinkProblem.BottleMissing)
        if (drink.items.map { it.bottleId }.toSet().size != drink.items.size) add(DrinkProblem.BottleRepeated)
        if (drink.items.any { it.ml < MIN_ITEM_ML || it.ml > MAX_ITEM_ML }) {
            add(DrinkProblem.VolumeOutOfRange(MIN_ITEM_ML, MAX_ITEM_ML.toInt()))
        }
        if (drink.totalMl > glassMl) add(DrinkProblem.OverGlass(drink.totalMl, glassMl.toInt()))
    }
}
