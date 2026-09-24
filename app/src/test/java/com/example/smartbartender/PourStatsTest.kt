package com.example.smartbartender

import com.example.smartbartender.domain.model.PourOutcome
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.PourStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PourStatsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** Thursday 24 September 2026, 22:00 UTC. The week (UK locale) began Monday the 21st. */
    private val now = at(2026, Calendar.SEPTEMBER, 24, 22)

    private fun compute(records: List<PourRecord>) = PourStats.compute(records, now, utc, Locale.UK)

    @Test
    fun `no history is empty`() {
        val stats = compute(emptyList())

        assertTrue(stats.isEmpty)
        assertEquals(0, stats.cocktailsMade)
        assertNull(stats.happyHour)
        assertNull(stats.averageDrinkMl)
        assertTrue(stats.milestones.none { it.achieved })
    }

    @Test
    fun `only finished pours count as cocktails, but every drop counts as poured`() {
        val stats = compute(
            listOf(
                pour("a", ml = mapOf("tequila" to 44.0, "triple_sec" to 15.0)),
                pour("b", outcome = PourOutcome.ABORTED, ml = mapOf("tequila" to 20.0)),
                pour("c", outcome = PourOutcome.FAILED, ml = emptyMap()),
            ),
        )

        assertEquals(1, stats.cocktailsMade)
        assertEquals(1, stats.stoppedEarly)
        assertEquals(1, stats.failed)
        assertEquals(79.0, stats.totalMl, 0.001)
        assertEquals(59.0, stats.averageDrinkMl!!, 0.001)
        assertFalse(stats.isEmpty)
    }

    @Test
    fun `top cocktails are ranked by count, ties going to the most recent`() {
        val stats = compute(
            listOf(
                pour("1", drinkId = "11007", name = "Margarita", atMs = at(2026, Calendar.SEPTEMBER, 1, 20)),
                pour("2", drinkId = "11007", name = "Margarita", atMs = at(2026, Calendar.SEPTEMBER, 2, 20)),
                pour("3", drinkId = "11000", name = "Mojito", atMs = at(2026, Calendar.SEPTEMBER, 3, 20)),
                pour("4", drinkId = "17222", name = "Cuba Libre", atMs = at(2026, Calendar.SEPTEMBER, 5, 20)),
                pour("5", drinkId = "11000", name = "Mojito", outcome = PourOutcome.ABORTED),
            ),
        )

        assertEquals(listOf("Margarita", "Cuba Libre", "Mojito"), stats.topCocktails.map { it.name })
        assertEquals(listOf(2, 1, 1), stats.topCocktails.map { it.count })
        assertEquals(3, stats.recipesTried)
    }

    @Test
    fun `bottles are ranked by volume through the pump`() {
        val stats = compute(
            listOf(
                pour("a", ml = mapOf("tequila" to 44.0, "lime_juice" to 30.0)),
                pour("b", ml = mapOf("lime_juice" to 30.0)),
            ),
        )

        assertEquals(listOf("lime_juice", "tequila"), stats.bottles.map { it.bottleId })
        assertEquals("Lime juice", stats.bottles.first().name)
        assertEquals(60.0 / 700.0, stats.bottles.first().bottleEquivalents, 0.0001)
    }

    @Test
    fun `happy hour and favourite day come from finished pours in local time`() {
        val saturday = Calendar.SATURDAY
        val stats = compute(
            listOf(
                pour("a", atMs = at(2026, Calendar.SEPTEMBER, 19, 21)), // Saturday
                pour("b", atMs = at(2026, Calendar.SEPTEMBER, 12, 21)), // Saturday
                pour("c", atMs = at(2026, Calendar.SEPTEMBER, 16, 18)), // Wednesday
                pour("d", atMs = at(2026, Calendar.SEPTEMBER, 16, 18), outcome = PourOutcome.ABORTED),
                pour("e", atMs = at(2026, Calendar.SEPTEMBER, 16, 18), outcome = PourOutcome.ABORTED),
            ),
        )

        assertEquals(21, stats.happyHour)
        assertEquals(saturday, stats.favouriteWeekday)
    }

    @Test
    fun `this week and this month follow the calendar`() {
        val stats = compute(
            listOf(
                pour("mon", atMs = at(2026, Calendar.SEPTEMBER, 21, 0)),
                pour("thu", atMs = at(2026, Calendar.SEPTEMBER, 24, 20)),
                pour("sun-before", atMs = at(2026, Calendar.SEPTEMBER, 20, 23)),
                pour("august", atMs = at(2026, Calendar.AUGUST, 31, 23)),
            ),
        )

        assertEquals(2, stats.thisWeek)
        assertEquals(3, stats.thisMonth)
        assertEquals(at(2026, Calendar.AUGUST, 31, 23), stats.firstPourAtMs)
        assertEquals(at(2026, Calendar.SEPTEMBER, 24, 20), stats.lastPourAtMs)
    }

    @Test
    fun `a drink poured only from alcohol-free bottles is a mocktail`() {
        val stats = compute(
            listOf(
                pour("virgin", ml = mapOf("orange_juice" to 100.0, "grenadine" to 10.0)),
                pour("cream", ml = mapOf("cream" to 30.0, "cola" to 100.0)),
                pour("spiked", ml = mapOf("orange_juice" to 100.0, "vodka" to 40.0)),
                pour("mystery", ml = mapOf("not_in_catalog" to 40.0)),
            ),
        )

        assertEquals(2, stats.mocktails)
        assertEquals(0.5f, stats.mocktailShare, 0.001f)
    }

    @Test
    fun `milestones track progress towards their targets`() {
        val records = (1..10).map { pour("p$it", drinkId = "d$it", ml = mapOf("vodka" to 110.0)) }
        val milestones = compute(records).milestones.associateBy { it.title }

        assertTrue(milestones.getValue("First pour").achieved)
        assertTrue(milestones.getValue("Regular").achieved)
        assertEquals(0.2f, milestones.getValue("Party host").progress, 0.001f)
        assertTrue(milestones.getValue("First litre").achieved) // 1100 ml
        assertTrue(milestones.getValue("Explorer").achieved)
        assertEquals(0f, milestones.getValue("Designated driver").progress, 0.001f)
    }

    private fun pour(
        jobId: String,
        drinkId: String = "11007",
        name: String = "Margarita",
        outcome: PourOutcome = PourOutcome.FINISHED,
        atMs: Long = at(2026, Calendar.SEPTEMBER, 1, 20),
        ml: Map<String, Double> = mapOf("tequila" to 44.0),
    ) = PourRecord(jobId, drinkId, name, outcome, atMs, ml)

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(utc, Locale.UK).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
}
