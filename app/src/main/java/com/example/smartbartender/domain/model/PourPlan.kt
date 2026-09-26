package com.example.smartbartender.domain.model

/**
 * A recipe turned into something a machine can execute.
 *
 * The app owns this half deliberately: it has the recipe, the bottle catalog and the
 * matching rules, so the Pi never needs to know TheCocktailDB exists. It receives bottle
 * ids and millilitres and resolves the pumps itself.
 */
data class PourPlan(
    val drinkId: String,
    val drinkName: String,
    val glass: String?,
    val items: List<PourItem>,
    /** Things a person has to do — ice, mint, a salted rim. Shown, never poured. */
    val manualSteps: List<String>,
    /** Ingredients that needed a guess, or could not be poured at all. Worth surfacing. */
    val warnings: List<PlanWarning>,
) {
    val isPourable: Boolean get() = items.isNotEmpty()

    val totalMl: Double get() = items.sumOf { it.ml }

    /** What to post to the machine. The caller mints [jobId], so a retry can replay the same one. */
    fun toRequest(jobId: String) = PourRequest(
        jobId = jobId,
        drinkId = drinkId,
        drinkName = drinkName,
        glass = glass,
        items = items,
        manualSteps = manualSteps,
    )
}

/** Something about a plan the user should know before pouring. The detail screen words each one. */
sealed interface PlanWarning {
    /** The recipe needs a bottle the catalog knows but the rack does not hold. */
    data class NotLoaded(val ingredient: String) : PlanWarning

    /** The recipe gave no usable measure, so [ml] was guessed from the bottle's category. */
    data class NoMeasure(val ingredient: String, val ml: Int) : PlanWarning

    /** The recipe overflowed the glass and every pour was shrunk to fit [glassMl]. */
    data class ScaledToGlass(val glassMl: Int) : PlanWarning
}

/** Never send a single pour larger than this, whatever the recipe or the parser says. */
const val MAX_ITEM_ML = 150.0

/** The glass size to plan for until the machine reports its real one. */
const val DEFAULT_MAX_POUR_ML = 250.0

/** One "part" when a recipe is written in parts and nothing anchors it to a real volume. */
private const val ML_PER_PART = 30.0

/** A "Fill" ingredient gets what's left of the glass, within these bounds. */
private const val MIN_FILL_ML = 20.0
private const val MAX_FILL_ML = MAX_ITEM_ML

/** What to pour when the recipe gives no usable measure. Rough, but drinkable. */
private fun defaultMl(category: BottleCategory): Double = when (category) {
    BottleCategory.SPIRIT -> 40.0
    BottleCategory.LIQUEUR -> 20.0
    BottleCategory.JUICE -> 30.0
    BottleCategory.MIXER -> 100.0
    BottleCategory.DAIRY -> 30.0
}

/**
 * Works out what to pour for [cocktail] given what is physically in the machine.
 *
 * [slots] is the rack in slot order (index 0 is slot 1 / pump 1); only membership matters
 * here, since the Pi maps bottle ids to pumps itself.
 */
fun buildPourPlan(
    cocktail: Cocktail,
    slots: List<String?>,
    maxPourMl: Double,
): PourPlan {
    val loaded = slots.filterNotNull().toSet()
    val items = mutableListOf<PourItem>()
    val manualSteps = mutableListOf<String>()
    val warnings = mutableListOf<PlanWarning>()

    // Pass one: classify every line, and work out the fixed volume so the relative and
    // "fill" lines have something to size themselves against.
    val pourable = mutableListOf<Pair<RecipeIngredient, Bottle>>()
    cocktail.ingredients.forEach { ingredient ->
        val bottle = BottleCatalog.resolveBottle(ingredient.name)
        when {
            bottle != null && bottle.id in loaded -> pourable += ingredient to bottle
            BottleCatalog.isPantryStaple(ingredient.name) -> manualSteps += manualLabel(ingredient)
            bottle != null -> warnings += PlanWarning.NotLoaded(ingredient.name)
            else -> manualSteps += manualLabel(ingredient)
        }
    }

    val measures = pourable.map { (ingredient, _) -> MeasureParser.parse(ingredient.measure) }
    val fixedMl = measures.filterIsInstance<Measure.Fixed>().sumOf { it.ml }
    val totalParts = measures.filterIsInstance<Measure.Relative>().sumOf { it.parts }

    pourable.forEachIndexed { index, (ingredient, bottle) ->
        val ml = when (val measure = measures[index]) {
            is Measure.Fixed -> measure.ml

            is Measure.Relative -> {
                // With no fixed volume to anchor to, a part is simply a shot's worth.
                val perPart = if (fixedMl > 0 && totalParts > 0) fixedMl / totalParts else ML_PER_PART
                measure.parts * perPart
            }

            Measure.Fill ->
                (maxPourMl - fixedMl).coerceIn(MIN_FILL_ML, MAX_FILL_ML)

            Measure.Unknown -> {
                val fallback = defaultMl(bottle.category)
                warnings += PlanWarning.NoMeasure(ingredient.name, fallback.toInt())
                fallback
            }
        }
        items += PourItem(
            bottleId = bottle.id,
            ingredientName = ingredient.name,
            ml = ml.coerceIn(1.0, MAX_ITEM_ML),
        )
    }

    return PourPlan(
        drinkId = cocktail.id,
        drinkName = cocktail.name,
        glass = cocktail.glass,
        items = scaleToGlass(items, maxPourMl, warnings),
        manualSteps = manualSteps,
        warnings = warnings,
    )
}

/** Recipes are written for a bar, not for whatever glass is on the tray. Shrink to fit. */
private fun scaleToGlass(
    items: List<PourItem>,
    maxPourMl: Double,
    warnings: MutableList<PlanWarning>,
): List<PourItem> {
    val total = items.sumOf { it.ml }
    if (total <= maxPourMl || total <= 0) return items

    val factor = maxPourMl / total
    warnings += PlanWarning.ScaledToGlass(maxPourMl.toInt())
    return items.map { item -> item.copy(ml = (item.ml * factor).coerceAtLeast(1.0)) }
}

private fun manualLabel(ingredient: RecipeIngredient): String =
    listOfNotNull(ingredient.measure?.takeIf { it.isNotBlank() }, ingredient.name)
        .joinToString(" ")
        .replaceFirstChar(Char::uppercase)
