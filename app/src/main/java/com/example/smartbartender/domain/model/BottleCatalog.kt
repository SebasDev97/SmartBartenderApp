package com.example.smartbartender.domain.model

/**
 * The bottles this machine prototype can be loaded with, plus the "pantry" items we assume
 * are always at hand (ice, sugar, garnishes) so that recipes are not marked unmakeable
 * because of a pinch of salt.
 */
object BottleCatalog {

    /** The machine has four physical bottle slots. Nothing may exceed this. */
    const val MAX_SLOTS = 4

    val bottles: List<Bottle> = listOf(
        // Spirits
        Bottle("vodka", "Vodka", "Vodka", BottleCategory.SPIRIT, listOf("Absolut vodka", "Vanilla vodka", "Citrus vodka")),
        Bottle("gin", "Gin", "Gin", BottleCategory.SPIRIT, listOf("Dry gin", "Sloe gin")),
        Bottle("light_rum", "White rum", "Light rum", BottleCategory.SPIRIT, listOf("Rum", "White rum", "Bacardi limon")),
        Bottle("dark_rum", "Dark rum", "Dark rum", BottleCategory.SPIRIT, listOf("Gold rum", "Spiced rum", "Blackstrap rum")),
        Bottle("tequila", "Tequila", "Tequila", BottleCategory.SPIRIT, listOf("Blanco tequila", "Gold tequila")),
        Bottle("whiskey", "Whiskey", "Blended whiskey", BottleCategory.SPIRIT, listOf("Whiskey", "Whisky", "Bourbon", "Scotch", "Rye whiskey", "Irish whiskey")),
        Bottle("brandy", "Brandy", "Brandy", BottleCategory.SPIRIT, listOf("Cognac")),

        // Liqueurs
        Bottle("triple_sec", "Triple sec", "Triple sec", BottleCategory.LIQUEUR, listOf("Cointreau", "Orange liqueur", "Grand Marnier")),
        Bottle("blue_curacao", "Blue curacao", "Blue Curacao", BottleCategory.LIQUEUR, listOf("Curacao")),
        Bottle("amaretto", "Amaretto", "Amaretto", BottleCategory.LIQUEUR),
        Bottle("coffee_liqueur", "Coffee liqueur", "Coffee liqueur", BottleCategory.LIQUEUR, listOf("Kahlua")),
        Bottle("irish_cream", "Irish cream", "Baileys irish cream", BottleCategory.LIQUEUR, listOf("Irish cream")),
        Bottle("peach_schnapps", "Peach schnapps", "Peach schnapps", BottleCategory.LIQUEUR, listOf("Peachtree schnapps")),
        Bottle("campari", "Campari", "Campari", BottleCategory.LIQUEUR),
        Bottle("dry_vermouth", "Dry vermouth", "Dry Vermouth", BottleCategory.LIQUEUR, listOf("Vermouth")),
        Bottle("sweet_vermouth", "Sweet vermouth", "Sweet Vermouth", BottleCategory.LIQUEUR, listOf("Red vermouth")),

        // Juices
        Bottle("lime_juice", "Lime juice", "Lime juice", BottleCategory.JUICE, listOf("Lime", "Juice of a lime", "Fresh lime juice", "Lime peel")),
        Bottle("lemon_juice", "Lemon juice", "Lemon juice", BottleCategory.JUICE, listOf("Lemon", "Juice of a lemon", "Lemon peel")),
        Bottle("orange_juice", "Orange juice", "Orange juice", BottleCategory.JUICE, listOf("Orange", "Orange peel")),
        Bottle("cranberry_juice", "Cranberry juice", "Cranberry juice", BottleCategory.JUICE),
        Bottle("pineapple_juice", "Pineapple juice", "Pineapple juice", BottleCategory.JUICE, listOf("Pineapple")),
        Bottle("tomato_juice", "Tomato juice", "Tomato juice", BottleCategory.JUICE),

        // Mixers & syrups
        Bottle("grenadine", "Grenadine", "Grenadine", BottleCategory.MIXER),
        Bottle("sugar_syrup", "Sugar syrup", "Sugar syrup", BottleCategory.MIXER, listOf("Simple syrup", "Syrup", "Sugar Syrup")),
        Bottle("soda_water", "Soda water", "Soda water", BottleCategory.MIXER, listOf("Club soda", "Carbonated water", "Sparkling water")),
        Bottle("tonic_water", "Tonic water", "Tonic water", BottleCategory.MIXER, listOf("Tonic")),
        Bottle("cola", "Cola", "Coca-Cola", BottleCategory.MIXER, listOf("Cola", "Pepsi cola")),
        Bottle("ginger_ale", "Ginger ale", "Ginger ale", BottleCategory.MIXER, listOf("Ginger beer")),
        Bottle("lemonade", "Lemonade", "Lemonade", BottleCategory.MIXER, listOf("Lemon-lime soda", "7-Up", "Sprite")),

        // Dairy
        Bottle("cream", "Cream", "Cream", BottleCategory.DAIRY, listOf("Heavy cream", "Light cream", "Double cream")),
        Bottle("milk", "Milk", "Milk", BottleCategory.DAIRY),
        Bottle("coconut_cream", "Coconut cream", "Coconut cream", BottleCategory.DAIRY, listOf("Cream of coconut", "Coconut milk")),
    )

    /**
     * Factory rack: the four bottles the machine ships loaded with. Chosen because they
     * cover the most recognisable drinks a four-slot machine can actually pour
     * (rum & coke, daiquiri, caipirissima, screwdriver-style highballs).
     */
    val defaultSelection: Set<String> = setOf("vodka", "light_rum", "lime_juice", "cola")

    /**
     * Ingredients we treat as always available on the bar top. They are not bottles in the
     * machine, but a recipe should not count as "missing an ingredient" because of ice.
     */
    val pantryStaples: Set<String> = setOf(
        "ice", "crushed ice", "ice cubes", "water", "hot water",
        "sugar", "powdered sugar", "brown sugar", "salt", "pepper", "black pepper",
        "nutmeg", "cinnamon", "mint", "cherry", "maraschino cherry", "olive", "celery salt",
        "egg white", "hot sauce", "worcestershire sauce", "honey", "vanilla extract",
    ).map { it.folded() }.toSet()

    /** The catalog grouped for display, in [BottleCategory] order. */
    val byCategory: Map<BottleCategory, List<Bottle>> =
        BottleCategory.entries.associateWith { category -> bottles.filter { it.category == category } }

    private val byMatchName: Map<String, Bottle> = buildMap {
        bottles.forEach { bottle ->
            bottle.matchNames.forEach { name -> putIfAbsent(name.folded(), bottle) }
        }
    }

    fun byId(id: String): Bottle? = bottles.firstOrNull { it.id == id }

    /** Loaded bottles in rack order, so slot 1 is always the same bottle across restarts. */
    fun inSlotOrder(bottleIds: Set<String>): List<Bottle> = bottles.filter { it.id in bottleIds }

    /**
     * Guards against more bottles than the machine has slots — persisted data from an
     * earlier build, or a race between two writes, is trimmed to the first [MAX_SLOTS].
     */
    fun clampToCapacity(bottleIds: Set<String>): Set<String> =
        if (bottleIds.size <= MAX_SLOTS) bottleIds
        else inSlotOrder(bottleIds).take(MAX_SLOTS).map { it.id }.toSet()

    /** Resolves a recipe ingredient name (any spelling) to the bottle that can pour it. */
    fun resolveBottle(recipeIngredient: String): Bottle? = byMatchName[recipeIngredient.folded()]

    fun isPantryStaple(recipeIngredient: String): Boolean =
        recipeIngredient.folded() in pantryStaples
}

