package com.example.smartbartender.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One line of a custom drink: a catalog bottle and exactly how much of it to pour. */
data class CustomItem(val bottleId: String, val ml: Int)

/**
 * A drink the user made up, saved on the phone.
 *
 * Items are stored by bottle, never by slot, so a drink survives the rack being rearranged.
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
    alcoholic = null,
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

    @Serializable
    private data class Entry(
        val id: String,
        val name: String,
        val emoji: String = "",
        val colorArgb: Long = 0,
        val notes: String = "",
        val items: List<ItemEntry> = emptyList(),
    )

    @Serializable
    private data class ItemEntry(val bottleId: String, val ml: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

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
     * Everything that stops [drink] from being saved, as sentences for the editor. Empty
     * means it is valid. [maxPourMl] is the glass the machine reports, or its default.
     */
    fun validate(drink: CustomDrink, maxPourMl: Double): List<String> = buildList {
        if (drink.name.isBlank()) add("Give the drink a name")
        if (drink.items.isEmpty()) add("Add at least one ingredient")
        if (drink.items.size > BottleCatalog.MAX_SLOTS) {
            add("The machine has only ${BottleCatalog.MAX_SLOTS} slots")
        }
        if (drink.items.any { BottleCatalog.byId(it.bottleId) == null }) add("Pick a bottle for every ingredient")
        if (drink.items.map { it.bottleId }.toSet().size != drink.items.size) {
            add("Each bottle can be used only once")
        }
        if (drink.items.any { it.ml < MIN_ITEM_ML || it.ml > MAX_ITEM_ML }) {
            add("Each ingredient must be $MIN_ITEM_ML–${MAX_ITEM_ML.toInt()} ml")
        }
        if (drink.totalMl > maxPourMl) add("That's ${drink.totalMl} ml — the glass holds ${maxPourMl.toInt()} ml")
    }

    /**
     * Scores every drink against the rack, like the online recipes: nothing missing means
     * it can be made now, one bottle short means almost, and anything further is dropped.
     */
    fun evaluate(drinks: List<CustomDrink>, loadedBottleIds: Set<String>): List<MakeableCocktail> =
        drinks.mapNotNull { drink ->
            val missing = drink.items
                .filterNot { it.bottleId in loadedBottleIds }
                .mapNotNull { BottleCatalog.byId(it.bottleId)?.displayName }
            if (missing.size > 1) null else MakeableCocktail(drink.toCocktail(), missing)
        }

    fun encode(drinks: List<CustomDrink>): String = json.encodeToString(
        serializer,
        drinks.map { drink ->
            Entry(
                id = drink.id,
                name = drink.name,
                emoji = drink.emoji,
                colorArgb = drink.colorArgb,
                notes = drink.notes,
                items = drink.items.map { ItemEntry(it.bottleId, it.ml) },
            )
        },
    )

    /**
     * A blank or unreadable value degrades to no drinks rather than a crash. Items whose
     * bottle has left the catalog are dropped, and so is a drink left with nothing to pour.
     */
    fun parse(stored: String): List<CustomDrink> {
        if (stored.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }
            .getOrDefault(emptyList())
            .filter { isCustomId(it.id) }
            .distinctBy { it.id }
            .mapNotNull { entry ->
                val items = entry.items
                    .filter { BottleCatalog.byId(it.bottleId) != null }
                    .map { CustomItem(it.bottleId, it.ml) }
                if (items.isEmpty()) return@mapNotNull null
                CustomDrink(
                    id = entry.id,
                    name = entry.name,
                    emoji = entry.emoji,
                    colorArgb = entry.colorArgb,
                    notes = entry.notes,
                    items = items,
                )
            }
    }
}
