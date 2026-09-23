package com.example.smartbartender

import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.MAX_ITEM_ML
import com.example.smartbartender.domain.model.RecipeIngredient
import com.example.smartbartender.domain.model.buildPourPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step between "a recipe" and "millilitres from pump 2". Everything that could put the
 * wrong amount of liquid in a glass goes through here, so it is tested hard.
 */
class PourPlanTest {

    private val rack = listOf("tequila", "triple_sec", "lime_juice", null)

    private fun cocktail(vararg ingredients: Pair<String, String?>) = Cocktail(
        id = "11007",
        name = "Margarita",
        thumbUrl = null,
        category = "Ordinary Drink",
        alcoholic = "Alcoholic",
        glass = "Cocktail glass",
        instructions = null,
        ingredients = ingredients.map { (name, measure) -> RecipeIngredient(name, measure) },
    )

    @Test
    fun `a margarita maps to the loaded bottles`() {
        val plan = buildPourPlan(
            cocktail(
                "Tequila" to "1 1/2 oz",
                "Triple sec" to "1/2 oz",
                "Lime juice" to "1 oz",
            ),
            rack,
            maxPourMl = 250.0,
        )

        assertEquals(listOf("tequila", "triple_sec", "lime_juice"), plan.request.items.map { it.bottleId })
        assertEquals(44.36, plan.request.items[0].ml, 0.01)
        assertTrue(plan.isPourable)
    }

    @Test
    fun `pantry staples become steps for the human, not pours`() {
        val plan = buildPourPlan(
            cocktail("Tequila" to "1 oz", "Salt" to null, "Ice" to null),
            rack,
            maxPourMl = 250.0,
        )

        assertEquals(listOf("tequila"), plan.request.items.map { it.bottleId })
        assertEquals(listOf("Salt", "Ice"), plan.manualSteps)
    }

    @Test
    fun `an ingredient whose bottle is not loaded is flagged, not silently dropped`() {
        val plan = buildPourPlan(
            cocktail("Tequila" to "1 oz", "Vodka" to "1 oz"),
            rack,
            maxPourMl = 250.0,
        )

        assertEquals(listOf("tequila"), plan.request.items.map { it.bottleId })
        assertTrue(plan.warnings.any { it.contains("Vodka") && it.contains("not loaded") })
    }

    @Test
    fun `accents and casing resolve through the catalog's normalisation`() {
        val plan = buildPourPlan(
            cocktail("TEQUILA" to "1 oz", "triple  sec" to "1 oz"),
            rack,
            maxPourMl = 250.0,
        )
        assertEquals(listOf("tequila", "triple_sec"), plan.request.items.map { it.bottleId })
    }

    @Test
    fun `a missing measure falls back to the bottle's category and says so`() {
        val plan = buildPourPlan(cocktail("Tequila" to null), rack, maxPourMl = 250.0)

        assertEquals(40.0, plan.request.items.single().ml, 0.01)
        assertTrue(plan.warnings.any { it.contains("no measure") })
    }

    @Test
    fun `fill takes what is left of the glass`() {
        val plan = buildPourPlan(
            cocktail("Tequila" to "1 oz", "Lime juice" to "Fill"),
            rack,
            maxPourMl = 100.0,
        )
        // 100 ml glass minus the 29.57 ml of tequila.
        assertEquals(70.43, plan.request.items[1].ml, 0.01)
    }

    @Test
    fun `a recipe that overflows the glass is scaled down proportionally`() {
        val plan = buildPourPlan(
            cocktail("Tequila" to "4 oz", "Triple sec" to "4 oz", "Lime juice" to "4 oz"),
            rack,
            maxPourMl = 120.0,
        )

        assertEquals(120.0, plan.totalMl, 0.5)
        // Still equal thirds after scaling.
        assertEquals(plan.request.items[0].ml, plan.request.items[1].ml, 0.01)
        assertTrue(plan.warnings.any { it.contains("Scaled down") })
    }

    @Test
    fun `no single pour can ever exceed the hard cap`() {
        val plan = buildPourPlan(cocktail("Tequila" to "2 pints"), rack, maxPourMl = 1000.0)
        assertTrue(plan.request.items.single().ml <= MAX_ITEM_ML)
    }

    @Test
    fun `a drink with nothing pourable is not pourable`() {
        val plan = buildPourPlan(cocktail("Ice" to null, "Mint" to null), rack, maxPourMl = 250.0)
        assertFalse(plan.isPourable)
    }

    @Test
    fun `parts are sized against the fixed volumes in the same recipe`() {
        val plan = buildPourPlan(
            cocktail("Tequila" to "60 ml", "Triple sec" to "2 parts"),
            rack,
            maxPourMl = 250.0,
        )
        // 60 ml of fixed volume over 2 parts is 30 ml a part, so two parts is 60 ml.
        assertEquals(60.0, plan.request.items[1].ml, 0.01)
    }
}
