package com.example.smartbartender.domain.model

/**
 * What a recipe line is actually asking for.
 *
 * TheCocktailDB's `strMeasure` is free text written by people, not a quantity:
 * `"1 1/2 oz"`, `"2-3 oz"`, `"4 cl"`, `"2 parts"`, `"Juice of 1 lime"`, `"Fill with cola"`,
 * `"2 dashes"`, `"Garnish"`, or simply absent. Until now the app only ever displayed it. A
 * pump needs a number of millilitres, so this is where the guessing is done — in one place,
 * explicitly, with tests.
 */
sealed interface Measure {

    /** A real volume. */
    data class Fixed(val ml: Double) : Measure

    /** `"2 parts"`, or a bare `"2"` — meaningful only relative to the rest of the drink. */
    data class Relative(val parts: Double) : Measure

    /** `"Fill"`, `"Top up"` — whatever is left in the glass. */
    data object Fill : Measure

    /** Absent, or prose no machine should act on. */
    data object Unknown : Measure
}

object MeasureParser {

    /** One US fluid ounce. TheCocktailDB is overwhelmingly imperial. */
    private const val ML_PER_OZ = 29.57

    private val UNITS: List<Pair<Regex, Double>> = listOf(
        Regex("""\b(fl\s*oz|ounces?|oz)\b""") to ML_PER_OZ,
        Regex("""\b(millilitres?|milliliters?|ml)\b""") to 1.0,
        Regex("""\b(centilitres?|centiliters?|cl)\b""") to 10.0,
        Regex("""\b(litres?|liters?|l)\b""") to 1000.0,
        Regex("""\b(tablespoons?|tbsp)\b""") to 14.79,
        Regex("""\b(teaspoons?|tsp)\b""") to 4.93,
        Regex("""\b(shots?|jiggers?)\b""") to 44.36,
        Regex("""\b(dashes|dash|drops?)\b""") to 0.92,
        Regex("""\b(splash(es)?)\b""") to 5.0,
        Regex("""\b(cups?)\b""") to 236.6,
        Regex("""\b(pints?)\b""") to 473.2,
        Regex("""\b(gills?)\b""") to 118.3,
    )

    private val FILL_WORDS = Regex("""\b(fill|top\s*up|topped|to\s*taste|as\s*needed)\b""")
    private val PARTS = Regex("""\bparts?\b""")
    private val JUICE_OF = Regex("""\bjuice\s+of\b""")

    /** Fractions arrive as both `1 1/2` and `1½`, sometimes in the same recipe. */
    private val VULGAR_FRACTIONS = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅐' to 1.0 / 7, '⅑' to 1.0 / 9, '⅒' to 0.1,
        '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8,
        '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )

    /** Rough juice yields, for `"Juice of 1 lime"` and friends. */
    private val JUICE_YIELD_ML = mapOf(
        "lime" to 30.0,
        "lemon" to 40.0,
        "orange" to 70.0,
        "grapefruit" to 110.0,
    )

    fun parse(measure: String?): Measure {
        val text = measure?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return Measure.Unknown

        val expanded = expandFractions(text)

        if (JUICE_OF.containsMatchIn(expanded)) {
            val fruit = JUICE_YIELD_ML.entries.firstOrNull { expanded.contains(it.key) }
            val count = firstNumber(expanded) ?: 1.0
            return fruit?.let { Measure.Fixed(count * it.value) } ?: Measure.Unknown
        }

        if (FILL_WORDS.containsMatchIn(expanded)) return Measure.Fill

        val amount = firstNumber(expanded) ?: return Measure.Unknown

        if (PARTS.containsMatchIn(expanded)) return Measure.Relative(amount)

        val unit = UNITS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(expanded) }
        // A bare number ("2", "2-4") has no unit and only makes sense against the other
        // ingredients, so it is relative rather than a guess at millilitres.
        return unit?.let { (_, ml) -> Measure.Fixed(amount * ml) } ?: Measure.Relative(amount)
    }

    /** `"1 1/2"` -> 1.5, `"1½"` -> 1.5, `"½"` -> 0.5. */
    private fun expandFractions(text: String): String {
        var result = text
        VULGAR_FRACTIONS.forEach { (glyph, value) ->
            if (result.contains(glyph)) {
                // "1½" is one and a half, "½" on its own is a half.
                result = result.replace(Regex("""(\d+)\s*$glyph"""), "${'$'}1 + $value")
                result = result.replace(glyph.toString(), " $value ")
            }
        }
        return result
    }

    /**
     * The first quantity in the string, expanding `a b/c` mixed fractions.
     *
     * A range (`"2-3 oz"`, `"2 to 3 oz"`) takes its **lower** bound: a short drink can be
     * topped up, an overflowing glass cannot be un-poured.
     */
    private fun firstNumber(text: String): Double? {
        Regex("""(\d+)\s*\+\s*([\d.]+)""").find(text)?.let { match ->
            return match.groupValues[1].toDouble() + match.groupValues[2].toDouble()
        }
        Regex("""(\d+)\s+(\d+)\s*/\s*(\d+)""").find(text)?.let { match ->
            val (whole, numerator, denominator) = match.destructured
            return whole.toDouble() + numerator.toDouble() / denominator.toDouble()
        }
        Regex("""(\d+)\s*/\s*(\d+)""").find(text)?.let { match ->
            val (numerator, denominator) = match.destructured
            return numerator.toDouble() / denominator.toDouble()
        }
        return Regex("""\d+(\.\d+)?""").find(text)?.value?.toDoubleOrNull()
    }
}
