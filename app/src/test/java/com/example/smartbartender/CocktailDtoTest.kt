package com.example.smartbartender

import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CocktailDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `ingredients pair with their measures and empty slots are dropped`() {
        val payload = """
            {"drinks":[{
              "idDrink":"11007","strDrink":"Margarita",
              "strDrinkThumb":"https://example.com/margarita.jpg",
              "strCategory":"Ordinary Drink","strAlcoholic":"Alcoholic",
              "strGlass":"Cocktail glass","strInstructions":"Shake and serve.",
              "strIngredient1":"Tequila","strIngredient2":"Triple sec",
              "strIngredient3":"Lime juice","strIngredient4":"Salt","strIngredient5":"",
              "strIngredient6":null,
              "strMeasure1":"1 1/2 oz ","strMeasure2":"1/2 oz ",
              "strMeasure3":"1 oz ","strMeasure4":null,"strMeasure5":""
            }]}
        """.trimIndent()

        val cocktail = json.decodeFromString<CocktailResponse>(payload).drinks.single().toDomain()

        assertEquals("Margarita", cocktail.name)
        assertEquals("Cocktail glass", cocktail.glass)
        assertEquals(4, cocktail.ingredients.size)
        assertEquals("Tequila", cocktail.ingredients[0].name)
        assertEquals("1 1/2 oz", cocktail.ingredients[0].measure)
        // Salt has no measure in the source data.
        assertEquals("Salt", cocktail.ingredients[3].name)
        assertEquals(null, cocktail.ingredients[3].measure)
    }

    @Test
    fun `a null drinks field parses as no results`() {
        val response = json.decodeFromString<CocktailResponse>("""{"drinks":null}""")
        assertTrue(response.drinks.isEmpty())
    }

    @Test
    fun `the api's string miss response parses as no results`() {
        // TheCocktailDB answers misses with a bare string instead of an array.
        val response = json.decodeFromString<DrinkSummaryResponse>("""{"drinks":"no data found"}""")
        assertTrue(response.drinks.isEmpty())

        val full = json.decodeFromString<CocktailResponse>("""{"drinks":"None Found"}""")
        assertTrue(full.drinks.isEmpty())
    }
}
