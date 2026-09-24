package com.example.smartbartender

import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.Favourites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {

    private val margarita = CocktailSummary("11007", "Margarita", "https://example.com/m.jpg")
    private val mojito = CocktailSummary("11000", "Mojito", null)
    private val pinaColada = CocktailSummary("17207", "Piña Colada", null)

    @Test
    fun `a new favourite goes to the front`() {
        val favourites = Favourites.toggle(listOf(margarita), mojito, favourite = true)
        assertEquals(listOf(mojito, margarita), favourites)
    }

    @Test
    fun `adding one already held keeps its place instead of duplicating it`() {
        val favourites = listOf(mojito, margarita)
        assertEquals(favourites, Favourites.toggle(favourites, margarita, favourite = true))
    }

    @Test
    fun `removing drops only that cocktail`() {
        val favourites = Favourites.toggle(listOf(mojito, margarita), mojito, favourite = false)
        assertEquals(listOf(margarita), favourites)
    }

    @Test
    fun `favourites survive a round trip through storage, in order`() {
        val favourites = listOf(pinaColada, mojito, margarita)
        assertEquals(favourites, Favourites.parse(Favourites.encode(favourites)))
    }

    @Test
    fun `a blank or corrupt stored value degrades to no favourites`() {
        assertTrue(Favourites.parse("").isEmpty())
        assertTrue(Favourites.parse("not json").isEmpty())
        assertTrue(Favourites.parse("""{"id":"11007"}""").isEmpty())
    }

    @Test
    fun `search ignores case and accents`() {
        val favourites = listOf(pinaColada, mojito, margarita)
        assertEquals(listOf(pinaColada), Favourites.filter(favourites, "PINA"))
        assertEquals(listOf(margarita), Favourites.filter(favourites, "garit"))
        assertEquals(favourites, Favourites.filter(favourites, "  "))
    }
}
