package com.example.smartbartender

import com.example.smartbartender.domain.model.Measure
import com.example.smartbartender.domain.model.MeasureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TheCocktailDB's measures are free text written by people. Every string here is a shape that
 * actually appears in the data, so this is the record of what the machine will do with it.
 */
class MeasureParserTest {

    private fun ml(measure: String?): Double {
        val parsed = MeasureParser.parse(measure)
        assertTrue("expected a fixed volume for \"$measure\", got $parsed", parsed is Measure.Fixed)
        return (parsed as Measure.Fixed).ml
    }

    @Test
    fun `ounces are the common case`() {
        assertEquals(29.57, ml("1 oz"), 0.01)
        assertEquals(59.14, ml("2 oz"), 0.01)
        assertEquals(29.57, ml("1 fl oz"), 0.01)
    }

    @Test
    fun `mixed fractions are expanded`() {
        assertEquals(44.36, ml("1 1/2 oz"), 0.01)
        assertEquals(14.79, ml("1/2 oz"), 0.01)
        assertEquals(7.39, ml("1/4 oz"), 0.01)
    }

    @Test
    fun `vulgar fraction glyphs work too`() {
        assertEquals(14.79, ml("½ oz"), 0.01)
        assertEquals(44.36, ml("1½ oz"), 0.01)
        assertEquals(22.18, ml("¾ oz"), 0.01)
    }

    @Test
    fun `metric measures are understood`() {
        assertEquals(40.0, ml("4 cl"), 0.01)
        assertEquals(30.0, ml("30 ml"), 0.01)
        assertEquals(1000.0, ml("1 l"), 0.01)
    }

    @Test
    fun `spoons dashes and splashes are small`() {
        assertEquals(9.86, ml("2 tsp"), 0.01)
        assertEquals(14.79, ml("1 tbsp"), 0.01)
        assertEquals(0.92, ml("1 dash"), 0.01)
        assertEquals(1.84, ml("2 dashes"), 0.01)
        assertEquals(5.0, ml("1 splash"), 0.01)
    }

    @Test
    fun `a range takes its lower bound`() {
        // A short drink can be topped up. An overflowing glass cannot be un-poured.
        assertEquals(ml("2 oz"), ml("2-3 oz"), 0.01)
        assertEquals(ml("1 oz"), ml("1 to 2 oz"), 0.01)
    }

    @Test
    fun `juice of a fruit uses a rough yield`() {
        assertEquals(30.0, ml("Juice of 1 lime"), 0.01)
        assertEquals(40.0, ml("Juice of 1 lemon"), 0.01)
        assertEquals(15.0, ml("Juice of 1/2 lime"), 0.01)
    }

    @Test
    fun `fill means whatever is left`() {
        assertEquals(Measure.Fill, MeasureParser.parse("Fill with cola"))
        assertEquals(Measure.Fill, MeasureParser.parse("Top up"))
        assertEquals(Measure.Fill, MeasureParser.parse("To taste"))
    }

    @Test
    fun `parts and bare numbers are relative, not millilitres`() {
        assertEquals(Measure.Relative(2.0), MeasureParser.parse("2 parts"))
        // "2-4" with no unit: a guess at ml would be a fabrication, so it stays relative.
        assertEquals(Measure.Relative(2.0), MeasureParser.parse("2-4"))
    }

    @Test
    fun `an absent or unusable measure is unknown`() {
        assertEquals(Measure.Unknown, MeasureParser.parse(null))
        assertEquals(Measure.Unknown, MeasureParser.parse(""))
        assertEquals(Measure.Unknown, MeasureParser.parse("Garnish"))
    }
}
