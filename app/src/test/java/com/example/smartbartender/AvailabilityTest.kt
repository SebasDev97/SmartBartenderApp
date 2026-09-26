package com.example.smartbartender

import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.data.remote.dto.CocktailDto
import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.domain.model.BottleCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** End-to-end check of the availability engine against a stubbed CocktailDB. */
class AvailabilityTest {

    private val margarita = CocktailDto(
        id = "1", name = "Margarita",
        ingredient1 = "Tequila", measure1 = "1 1/2 oz",
        ingredient2 = "Triple sec", measure2 = "1/2 oz",
        ingredient3 = "Lime juice", measure3 = "1 oz",
        ingredient4 = "Salt",
    )
    private val mojito = CocktailDto(
        id = "2", name = "Mojito",
        ingredient1 = "Light rum", measure1 = "2-3 oz",
        ingredient2 = "Lime", measure2 = "Juice of 1",
        ingredient3 = "Sugar", measure3 = "2 tsp",
        ingredient4 = "Mint", measure4 = "2-4",
        ingredient5 = "Soda water",
    )
    private val negroni = CocktailDto(
        id = "3", name = "Negroni",
        ingredient1 = "Gin", measure1 = "1 oz",
        ingredient2 = "Campari", measure2 = "1 oz",
        ingredient3 = "Sweet Vermouth", measure3 = "1 oz",
    )
    private val absintheDrink = CocktailDto(
        id = "4", name = "Green Hour",
        ingredient1 = "Absinthe", measure1 = "1 oz",
        ingredient2 = "Water", measure2 = "3 oz",
    )

    private val highball = CocktailDto(
        id = "5", name = "Whiskey Highball",
        ingredient1 = "Blended whiskey", measure1 = "2 oz",
        ingredient2 = "Carbonated water", measure2 = "Fill",
        ingredient3 = "Ice",
    )

    /** Serves the whole book from the first-letter endpoint, like the real API does. */
    private class FakeApi(private val drinks: List<CocktailDto>) : CocktailApi {
        override suspend fun searchByName(name: String) =
            CocktailResponse(drinks.filter { it.name.orEmpty().contains(name, ignoreCase = true) })

        override suspend fun searchByFirstLetter(letter: String) =
            CocktailResponse(drinks.filter { it.name.orEmpty().startsWith(letter, ignoreCase = true) })

        override suspend fun filterByIngredient(ingredient: String) = DrinkSummaryResponse(emptyList())

        override suspend fun lookupById(id: String) =
            CocktailResponse(drinks.filter { it.id == id })

        override suspend fun random() = CocktailResponse(drinks.take(1))
    }

    private fun repository() =
        CocktailRepository(FakeApi(listOf(margarita, mojito, negroni, absintheDrink, highball)))

    /**
     * The same book, except that some first-letter pages fail until [recover] is called. The
     * repository fetches pages in parallel on `Dispatchers.Default`, hence the atomic count.
     */
    private class FlakyApi(
        private val book: FakeApi,
        @Volatile private var failing: Set<String>,
    ) : CocktailApi by book {
        private val requests = AtomicInteger()
        val pageRequests: Int get() = requests.get()

        fun recover() { failing = emptySet() }

        override suspend fun searchByFirstLetter(letter: String): CocktailResponse {
            requests.incrementAndGet()
            if (letter in failing) throw IOException("page $letter timed out")
            return book.searchByFirstLetter(letter)
        }
    }

    @Test
    fun `a fully covered recipe can be made now`() = runTest {
        val bottles = listOf("tequila", "triple_sec", "lime_juice").mapNotNull(BottleCatalog::byId)

        val result = repository().findMakeable(bottles)

        // Salt is a bar-top staple, so Margarita needs nothing else.
        assertEquals(listOf("Margarita"), result.canMakeNow.map { it.cocktail.name })
    }

    @Test
    fun `a recipe missing exactly one bottle lands in almost`() = runTest {
        val bottles = listOf("light_rum", "lime_juice").mapNotNull(BottleCatalog::byId)

        val result = repository().findMakeable(bottles)

        val mojitoResult = result.almost.single { it.cocktail.name == "Mojito" }
        // Sugar and mint are staples; only the soda water is genuinely absent.
        assertEquals(listOf("Soda water"), mojitoResult.missingIngredients)
        assertTrue(result.canMakeNow.none { it.cocktail.name == "Mojito" })
    }

    @Test
    fun `recipes missing two or more bottles appear in neither section`() = runTest {
        val bottles = listOf("gin").mapNotNull(BottleCatalog::byId)

        val result = repository().findMakeable(bottles)

        // Negroni still needs Campari and sweet vermouth.
        assertTrue(result.canMakeNow.none { it.cocktail.name == "Negroni" })
        assertTrue(result.almost.none { it.cocktail.name == "Negroni" })
    }

    @Test
    fun `an ingredient the machine has no slot for is always missing`() = runTest {
        val bottles = BottleCatalog.bottles

        val result = repository().findMakeable(bottles)

        // Absinthe is not a loadable bottle, so this recipe can never be poured.
        assertTrue(result.canMakeNow.none { it.cocktail.name == "Green Hour" })
        assertEquals(
            listOf("Absinthe"),
            result.almost.single { it.cocktail.name == "Green Hour" }.missingIngredients,
        )
    }

    @Test
    fun `carbonated water still needs the soda bottle`() = runTest {
        val whiskeyOnly = listOf("whiskey").mapNotNull(BottleCatalog::byId)

        val result = repository().findMakeable(whiskeyOnly)

        assertTrue(result.canMakeNow.none { it.cocktail.name == "Whiskey Highball" })
        assertEquals(
            listOf("Carbonated water"),
            result.almost.single { it.cocktail.name == "Whiskey Highball" }.missingIngredients,
        )

        val withSoda = listOf("whiskey", "soda_water").mapNotNull(BottleCatalog::byId)
        assertTrue(
            repository().findMakeable(withSoda).canMakeNow.any { it.cocktail.name == "Whiskey Highball" },
        )
    }

    @Test
    fun `no bottles loaded yields nothing pourable`() = runTest {
        val result = repository().findMakeable(emptyList())
        assertTrue(result.canMakeNow.isEmpty())
    }

    @Test
    fun `pages that failed are fetched again rather than cached as missing`() = runTest {
        val api = FlakyApi(FakeApi(listOf(margarita, negroni)), failing = setOf("n"))
        val repository = CocktailRepository(api)
        val bottles = listOf("gin", "campari", "sweet_vermouth").mapNotNull(BottleCatalog::byId)

        assertTrue(repository.findMakeable(bottles).canMakeNow.none { it.cocktail.name == "Negroni" })

        api.recover()
        assertTrue(repository.findMakeable(bottles).canMakeNow.any { it.cocktail.name == "Negroni" })
        assertEquals(listOf("Margarita", "Negroni"), repository.browse().map { it.name })
    }

    @Test
    fun `only the failed pages are fetched again, and a complete book never`() = runTest {
        val api = FlakyApi(FakeApi(listOf(margarita, negroni)), failing = setOf("n"))
        val repository = CocktailRepository(api)

        repository.browse()
        assertEquals(26, api.pageRequests)

        api.recover()
        repository.browse()
        assertEquals(27, api.pageRequests)

        repository.browse()
        assertEquals(27, api.pageRequests)
    }
}
