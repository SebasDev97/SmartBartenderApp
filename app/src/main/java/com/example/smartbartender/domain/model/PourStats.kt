package com.example.smartbartender.domain.model

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A standard 70 cl bottle — only used to turn millilitres into something relatable. */
const val STANDARD_BOTTLE_ML = 700.0

/** Categories with no alcohol in them. A drink poured only from these is a mocktail. */
private val ALCOHOL_FREE = setOf(BottleCategory.JUICE, BottleCategory.MIXER, BottleCategory.DAIRY)

data class DrinkCount(val drinkId: String?, val name: String, val count: Int)

data class BottleVolume(val bottleId: String, val name: String, val ml: Double) {
    /** How many standard bottles' worth have gone through the pump. Not what is left. */
    val bottleEquivalents: Double get() = ml / STANDARD_BOTTLE_ML
}

data class Milestone(
    val title: String,
    val detail: String,
    /** 0..1, clamped. */
    val progress: Float,
) {
    val achieved: Boolean get() = progress >= 1f
}

/**
 * Everything the Stats tab shows, derived from the pour history in one pure pass.
 *
 * Every number here is something the machine actually measured or the app actually did —
 * there is deliberately nothing about how full a bottle is, because nothing can know that.
 */
data class PourStats(
    /** Finished pours. Stopped and failed pours are counted separately. */
    val cocktailsMade: Int,
    /** Everything that came out of a pump, including the part of a pour that was stopped. */
    val totalMl: Double,
    val recipesTried: Int,
    val thisWeek: Int,
    val thisMonth: Int,
    val topCocktails: List<DrinkCount>,
    val bottles: List<BottleVolume>,
    /** Hour of day, 0–23, with the most finished pours. */
    val happyHour: Int?,
    /** A [Calendar.DAY_OF_WEEK] value. */
    val favouriteWeekday: Int?,
    val mocktails: Int,
    val averageDrinkMl: Double?,
    val stoppedEarly: Int,
    val failed: Int,
    val firstPourAtMs: Long?,
    val lastPourAtMs: Long?,
    val milestones: List<Milestone>,
) {
    val isEmpty: Boolean get() = cocktailsMade == 0 && stoppedEarly == 0 && failed == 0

    val mocktailShare: Float get() = if (cocktailsMade == 0) 0f else mocktails.toFloat() / cocktailsMade

    companion object {
        const val TOP_COCKTAILS = 5

        fun compute(
            records: List<PourRecord>,
            nowMs: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
            locale: Locale = Locale.getDefault(),
        ): PourStats {
            val finished = records.filter { it.isFinished }
            val calendar = Calendar.getInstance(timeZone, locale)

            val weekStart = calendar.startOfWeek(nowMs)
            val monthStart = calendar.startOfMonth(nowMs)

            val byDrink = finished.groupBy { it.drinkId ?: it.drinkName }
            val topCocktails = byDrink.values
                .map { pours ->
                    val latest = pours.maxBy { it.finishedAtMs }
                    DrinkCount(latest.drinkId, latest.drinkName, pours.size) to latest.finishedAtMs
                }
                .sortedWith(compareByDescending<Pair<DrinkCount, Long>> { it.first.count }.thenByDescending { it.second })
                .take(TOP_COCKTAILS)
                .map { it.first }

            val mlByBottle = mutableMapOf<String, Double>()
            records.forEach { record ->
                record.mlByBottle.forEach { (id, ml) -> mlByBottle[id] = (mlByBottle[id] ?: 0.0) + ml }
            }
            val bottles = mlByBottle
                .map { (id, ml) -> BottleVolume(id, BottleCatalog.byId(id)?.displayName ?: id, ml) }
                .sortedByDescending { it.ml }

            val hours = finished.groupingBy { calendar.field(it.finishedAtMs, Calendar.HOUR_OF_DAY) }.eachCount()
            val weekdays = finished.groupingBy { calendar.field(it.finishedAtMs, Calendar.DAY_OF_WEEK) }.eachCount()

            val mocktails = finished.count { it.isMocktail() }
            val totalMl = records.sumOf { it.totalMl }

            return PourStats(
                cocktailsMade = finished.size,
                totalMl = totalMl,
                recipesTried = byDrink.size,
                thisWeek = finished.count { it.finishedAtMs >= weekStart },
                thisMonth = finished.count { it.finishedAtMs >= monthStart },
                topCocktails = topCocktails,
                bottles = bottles,
                happyHour = hours.busiest(),
                favouriteWeekday = weekdays.busiest(),
                mocktails = mocktails,
                averageDrinkMl = finished.takeIf { it.isNotEmpty() }?.map { it.totalMl }?.average(),
                stoppedEarly = records.count { it.outcome == PourOutcome.ABORTED },
                failed = records.count { it.outcome == PourOutcome.FAILED },
                firstPourAtMs = records.minOfOrNull { it.finishedAtMs },
                lastPourAtMs = records.maxOfOrNull { it.finishedAtMs },
                milestones = milestones(finished.size, totalMl, byDrink.size, mocktails),
            )
        }

        private fun milestones(cocktails: Int, totalMl: Double, recipes: Int, mocktails: Int) = listOf(
            Milestone("First pour", "Make your first cocktail", fraction(cocktails, 1)),
            Milestone("Regular", "Make 10 cocktails", fraction(cocktails, 10)),
            Milestone("Party host", "Make 50 cocktails", fraction(cocktails, 50)),
            Milestone("Centurion", "Make 100 cocktails", fraction(cocktails, 100)),
            Milestone("First litre", "Pour 1 litre in total", (totalMl / 1000.0).toFloat().coerceIn(0f, 1f)),
            Milestone("Five litres", "Pour 5 litres in total", (totalMl / 5000.0).toFloat().coerceIn(0f, 1f)),
            Milestone("Explorer", "Try 10 different recipes", fraction(recipes, 10)),
            Milestone("Connoisseur", "Try 25 different recipes", fraction(recipes, 25)),
            Milestone("Designated driver", "Make 5 alcohol-free drinks", fraction(mocktails, 5)),
        )

        private fun fraction(value: Int, target: Int): Float = (value.toFloat() / target).coerceIn(0f, 1f)
    }
}

/** True when everything poured came from an alcohol-free bottle. An unknown bottle counts as alcoholic. */
private fun PourRecord.isMocktail(): Boolean =
    mlByBottle.isNotEmpty() &&
        mlByBottle.keys.all { id -> BottleCatalog.byId(id)?.category in ALCOHOL_FREE }

/** The key with the highest count; a tie goes to the smaller key, so the answer is stable. */
private fun Map<Int, Int>.busiest(): Int? =
    entries.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key

private fun Calendar.field(atMs: Long, field: Int): Int {
    timeInMillis = atMs
    return get(field)
}

private fun Calendar.startOfDay(atMs: Long) {
    timeInMillis = atMs
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/** Midnight at the start of the current week, honouring the locale's first day (Monday in NL). */
private fun Calendar.startOfWeek(atMs: Long): Long {
    startOfDay(atMs)
    val daysIntoWeek = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
    add(Calendar.DAY_OF_MONTH, -daysIntoWeek)
    return timeInMillis
}

private fun Calendar.startOfMonth(atMs: Long): Long {
    startOfDay(atMs)
    set(Calendar.DAY_OF_MONTH, 1)
    return timeInMillis
}
