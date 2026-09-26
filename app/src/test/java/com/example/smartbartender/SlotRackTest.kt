package com.example.smartbartender

import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.SlotRack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Slot order is the difference between pouring vodka and pouring gin, so it gets its own
 * tests. The interesting property is that a bottle keeps its slot when a *different* bottle
 * is ejected — and, because the assignment is persisted, that survives a restart.
 */
class SlotRackTest {

    @Test
    fun `a bottle keeps its slot when a neighbour is ejected`() {
        val rack = SlotRack.reconcile(SlotRack.empty, setOf("vodka", "cola", "lime_juice"))
        assertEquals(listOf("vodka", "lime_juice", "cola", null), rack)

        val afterEject = SlotRack.reconcile(rack, setOf("vodka", "cola"))
        // cola stays in slot 3 rather than shuffling up into the gap lime_juice left.
        assertEquals(listOf("vodka", null, "cola", null), afterEject)
    }

    @Test
    fun `a new bottle takes the first free slot`() {
        val rack = listOf("vodka", null, "cola", null)
        val afterLoad = SlotRack.reconcile(rack, setOf("vodka", "cola", "gin"))
        assertEquals(listOf("vodka", "gin", "cola", null), afterLoad)
    }

    @Test
    fun `the assignment survives a round trip through storage`() {
        val rack = listOf("vodka", null, "cola", "gin")
        assertEquals(rack, SlotRack.parse(SlotRack.encode(rack)))
    }

    @Test
    fun `an empty or unknown stored value degrades to an empty rack`() {
        assertEquals(SlotRack.empty, SlotRack.parse(""))
        // A bottle id from a build that no longer has it must not occupy a real pump.
        assertEquals(listOf("vodka", null, null, null), SlotRack.parse("vodka|unobtainium||"))
    }

    @Test
    fun `a stored rack longer than the machine is trimmed`() {
        val stored = "vodka|gin|cola|lime_juice|whiskey|brandy"
        assertEquals(BottleCatalog.MAX_SLOTS, SlotRack.parse(stored).size)
    }

    @Test
    fun `pump numbers are one-based, as the machine counts them`() {
        val rack = listOf("vodka", null, "cola", null)
        assertEquals(1, SlotRack.pumpFor(rack, "vodka"))
        assertEquals(3, SlotRack.pumpFor(rack, "cola"))
        assertNull(SlotRack.pumpFor(rack, "gin"))
    }
}
