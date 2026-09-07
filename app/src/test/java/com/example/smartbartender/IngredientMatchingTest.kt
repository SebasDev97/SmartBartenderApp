package com.example.smartbartender

import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.normalizedIngredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The matcher is the heart of the "what can I pour" feature, so it is tested directly. */
class IngredientMatchingTest {

    @Test
    fun `normalisation ignores case punctuation and spacing`() {
        assertEquals("light rum", "  Light  Rum ".normalizedIngredient())
        assertEquals("coca cola", "Coca-Cola".normalizedIngredient())
        assertEquals("blue curacao", "Blue Curaçao".normalizedIngredient())
    }

    @Test
    fun `api spellings resolve to the bottle that pours them`() {
        assertEquals("light_rum", BottleCatalog.resolveBottle("Light rum")?.id)
        assertEquals("light_rum", BottleCatalog.resolveBottle("White rum")?.id)
        assertEquals("whiskey", BottleCatalog.resolveBottle("Bourbon")?.id)
        assertEquals("triple_sec", BottleCatalog.resolveBottle("Cointreau")?.id)
        assertEquals("lime_juice", BottleCatalog.resolveBottle("Juice of a Lime")?.id)
        assertEquals("cola", BottleCatalog.resolveBottle("Coca-Cola")?.id)
        assertEquals("blue_curacao", BottleCatalog.resolveBottle("Blue Curaçao")?.id)
    }

    @Test
    fun `unknown ingredients resolve to no bottle`() {
        assertNull(BottleCatalog.resolveBottle("Absinthe"))
        assertNull(BottleCatalog.resolveBottle(""))
    }

    @Test
    fun `bar top staples never count as missing`() {
        assertTrue(BottleCatalog.isPantryStaple("Ice"))
        assertTrue(BottleCatalog.isPantryStaple("Sugar"))
        assertTrue(BottleCatalog.isPantryStaple("Salt"))
        assertTrue(BottleCatalog.isPantryStaple("Maraschino cherry"))
    }

    @Test
    fun `a staple never shadows a liquid the machine has to pour`() {
        // "Carbonated water" is soda water: treating it as a bar-top staple would make the
        // app claim it can pour highballs with no soda bottle loaded.
        assertEquals("soda_water", BottleCatalog.resolveBottle("Carbonated water")?.id)

        val bottleNames = BottleCatalog.bottles
            .flatMap { it.matchNames }
            .map { it.normalizedIngredient() }
        assertEquals(
            emptyList<String>(),
            BottleCatalog.pantryStaples.filter { it in bottleNames },
        )
    }

    @Test
    fun `every catalogue bottle has a unique id`() {
        val ids = BottleCatalog.bottles.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `default selection only names bottles that exist`() {
        BottleCatalog.defaultSelection.forEach { id ->
            assertTrue("Unknown default bottle: $id", BottleCatalog.byId(id) != null)
        }
    }

    @Test
    fun `the factory rack fits the machine`() {
        assertEquals(BottleCatalog.MAX_SLOTS, BottleCatalog.defaultSelection.size)
    }

    @Test
    fun `an oversized rack is trimmed to the machine's slot count`() {
        val tooMany = BottleCatalog.bottles.take(9).map { it.id }.toSet()

        val clamped = BottleCatalog.clampToCapacity(tooMany)

        assertEquals(BottleCatalog.MAX_SLOTS, clamped.size)
        assertTrue(clamped.all { it in tooMany })
    }

    @Test
    fun `clamping keeps a legal rack untouched and is stable`() {
        val rack = setOf("vodka", "cola")

        assertEquals(rack, BottleCatalog.clampToCapacity(rack))
        // Slot order follows the catalogue, so slot 1 is the same bottle after a restart.
        assertEquals(
            BottleCatalog.inSlotOrder(rack).map { it.id },
            BottleCatalog.inSlotOrder(rack).map { it.id },
        )
        assertEquals(listOf("vodka", "cola"), BottleCatalog.inSlotOrder(rack).map { it.id })
    }
}
