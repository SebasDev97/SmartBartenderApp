package com.example.smartbartender

import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.CustomItem
import com.example.smartbartender.domain.model.buildPourPlan
import com.example.smartbartender.domain.model.toCocktail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drinks the user builds by hand. They go through the same pour planner as online recipes,
 * so the tests that matter most check that what was typed is exactly what gets poured.
 */
class CustomDrinksTest {

    private fun drink(
        vararg items: Pair<String, Int>,
        id: String = "custom-1",
        name: String = "Sunset Cooler",
    ) = CustomDrink(
        id = id,
        name = name,
        emoji = "🍹",
        colorArgb = 0xFFFF3DD8,
        notes = "Add ice first",
        items = items.map { (bottleId, ml) -> CustomItem(bottleId, ml) },
    )

    // ------------------------------------------------------------------ storage

    @Test
    fun `drinks survive a round trip through storage, in order`() {
        val drinks = listOf(
            drink("vodka" to 40, "cola" to 120),
            drink("light_rum" to 50, "lime_juice" to 20, id = "custom-2", name = "Daiquiri-ish"),
        )
        assertEquals(drinks, CustomDrinks.parse(CustomDrinks.encode(drinks)))
    }

    @Test
    fun `a blank or corrupt stored value degrades to no drinks`() {
        assertEquals(emptyList<CustomDrink>(), CustomDrinks.parse(""))
        assertEquals(emptyList<CustomDrink>(), CustomDrinks.parse("{not json"))
    }

    @Test
    fun `a bottle that left the catalog is dropped, and a drink left empty goes with it`() {
        val stored = CustomDrinks.encode(
            listOf(
                drink("vodka" to 40, "unobtainium" to 20),
                drink("unobtainium" to 20, id = "custom-2"),
            ),
        )
        val parsed = CustomDrinks.parse(stored)
        assertEquals(listOf("custom-1"), parsed.map { it.id })
        assertEquals(listOf(CustomItem("vodka", 40)), parsed.single().items)
    }

    @Test
    fun `upsert replaces a drink in place and adds a new one to the front`() {
        val first = drink("vodka" to 40, id = "custom-1")
        val second = drink("gin" to 40, id = "custom-2")
        val renamed = first.copy(name = "Renamed")

        assertEquals(listOf(renamed, second), CustomDrinks.upsert(listOf(first, second), renamed))
        assertEquals(listOf(second, first), CustomDrinks.upsert(listOf(first), second))
    }

    @Test
    fun `remove drops only that drink`() {
        val first = drink("vodka" to 40, id = "custom-1")
        val second = drink("gin" to 40, id = "custom-2")
        assertEquals(listOf(second), CustomDrinks.remove(listOf(first, second), "custom-1"))
    }

    @Test
    fun `new ids are custom and never look like a TheCocktailDB id`() {
        val id = CustomDrinks.newId()
        assertTrue(CustomDrinks.isCustomId(id))
        assertFalse(CustomDrinks.isCustomId("11007"))
        assertFalse(CustomDrinks.isCustomId(null))
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `a sensible drink is valid`() {
        assertEquals(emptyList<String>(), CustomDrinks.validate(drink("vodka" to 40, "cola" to 120), 250.0))
    }

    @Test
    fun `a drink needs a name and at least one ingredient`() {
        assertEquals(1, CustomDrinks.validate(drink("vodka" to 40, name = "  "), 250.0).size)
        assertEquals(1, CustomDrinks.validate(drink(), 250.0).size)
    }

    @Test
    fun `no more ingredients than the machine has slots`() {
        val tooMany = drink("vodka" to 10, "gin" to 10, "cola" to 10, "lime_juice" to 10, "tonic_water" to 10)
        assertEquals(BottleCatalog.MAX_SLOTS + 1, tooMany.items.size)
        assertEquals(1, CustomDrinks.validate(tooMany, 250.0).size)
    }

    @Test
    fun `a bottle can be used only once`() {
        assertEquals(1, CustomDrinks.validate(drink("vodka" to 40, "vodka" to 20), 250.0).size)
    }

    @Test
    fun `every row needs a real bottle`() {
        assertEquals(1, CustomDrinks.validate(drink("" to 40), 250.0).size)
    }

    @Test
    fun `each pour stays within the per-item bounds`() {
        assertEquals(1, CustomDrinks.validate(drink("vodka" to 0), 250.0).size)
        assertEquals(1, CustomDrinks.validate(drink("cola" to 151), 250.0).size)
        assertEquals(emptyList<String>(), CustomDrinks.validate(drink("cola" to 150), 250.0))
    }

    @Test
    fun `the total must fit the glass the machine reports`() {
        val big = drink("vodka" to 100, "cola" to 150)
        assertEquals(emptyList<String>(), CustomDrinks.validate(big, 250.0))
        assertEquals(1, CustomDrinks.validate(big, 200.0).size)
    }

    // ------------------------------------------------------------------ availability

    @Test
    fun `drinks are scored against the rack like any recipe`() {
        val ready = drink("vodka" to 40, "cola" to 120, id = "custom-1")
        val oneShort = drink("vodka" to 40, "tonic_water" to 120, id = "custom-2")
        val twoShort = drink("gin" to 40, "tonic_water" to 120, id = "custom-3")

        val scored = CustomDrinks.evaluate(listOf(ready, oneShort, twoShort), setOf("vodka", "cola"))

        assertEquals(listOf("custom-1", "custom-2"), scored.map { it.cocktail.id })
        assertTrue(scored[0].canMakeNow)
        assertEquals(listOf("Tonic water"), scored[1].missingIngredients)
    }

    // ------------------------------------------------------------------ pouring

    @Test
    fun `every catalog bottle resolves back to itself from its display name`() {
        // toCocktail() names each ingredient after its bottle's displayName, and the planner
        // resolves it back. A collision here would pour the wrong liquid.
        BottleCatalog.bottles.forEach { bottle ->
            assertEquals(bottle.id, BottleCatalog.resolveBottle(bottle.displayName)?.id)
        }
    }

    @Test
    fun `a custom drink pours exactly what was typed, in the order given`() {
        val custom = drink("cola" to 120, "vodka" to 40, "lime_juice" to 15)
        val plan = buildPourPlan(custom.toCocktail(), listOf("vodka", "lime_juice", "cola", null), maxPourMl = 250.0)

        assertEquals(listOf("cola", "vodka", "lime_juice"), plan.request.items.map { it.bottleId })
        assertEquals(listOf(120.0, 40.0, 15.0), plan.request.items.map { it.ml })
        assertEquals("custom-1", plan.request.drinkId)
        assertEquals("Sunset Cooler", plan.request.drinkName)
        assertTrue(plan.warnings.isEmpty())
        assertTrue(plan.manualSteps.isEmpty())
    }

    @Test
    fun `a bottle missing from the rack is flagged, not poured`() {
        val custom = drink("vodka" to 40, "tonic_water" to 120)
        val plan = buildPourPlan(custom.toCocktail(), listOf("vodka", null, null, null), maxPourMl = 250.0)

        assertEquals(listOf("vodka"), plan.request.items.map { it.bottleId })
        assertTrue(plan.warnings.any { it.contains("Tonic water") })
    }

    @Test
    fun `notes become the preparation text and the look carries over`() {
        val cocktail = drink("vodka" to 40).toCocktail()
        assertEquals("Add ice first", cocktail.instructions)
        assertEquals("🍹", cocktail.look?.emoji)
        assertEquals(cocktail.look, cocktail.summary.look)
    }
}
