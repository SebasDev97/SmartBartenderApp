package com.example.smartbartender

import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.ui.common.LoadError
import com.example.smartbartender.ui.common.toLoadError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Every way a recipe load fails gets its own wording, and no exception message reaches the screen. */
class LoadErrorTest {

    /** Knows no drinks, and fails every catalog page with [pageFailure]. */
    private class EmptyBar(private val pageFailure: Exception? = null) : CocktailApi {
        override suspend fun searchByFirstLetter(letter: String): CocktailResponse =
            pageFailure?.let { throw it } ?: CocktailResponse(emptyList())
        override suspend fun lookupById(id: String) = CocktailResponse(emptyList())
        override suspend fun random() = CocktailResponse(emptyList())
        override suspend fun searchByName(name: String) = CocktailResponse(emptyList())
        override suspend fun filterByIngredient(ingredient: String) = DrinkSummaryResponse(emptyList())
    }

    private suspend fun failureOf(block: suspend () -> Unit): LoadError =
        runCatching { block() }.exceptionOrNull()!!.toLoadError()

    @Test
    fun `a drink the bar does not have is not found`() = runTest {
        val repository = CocktailRepository(EmptyBar())

        assertEquals(LoadError.NotFound, failureOf { repository.cocktailById("404") })
        assertEquals(LoadError.NotFound, failureOf { repository.randomCocktail() })
    }

    @Test
    fun `a catalog with no page loaded is unreachable`() = runTest {
        val repository = CocktailRepository(EmptyBar(pageFailure = IOException("connection reset")))

        assertEquals(LoadError.Unreachable, failureOf { repository.browse() })
    }

    @Test
    fun `network failures keep their own wording`() {
        assertEquals(LoadError.Offline, UnknownHostException("thecocktaildb.com").toLoadError())
        assertEquals(LoadError.Timeout, SocketTimeoutException("read timed out").toLoadError())
    }

    @Test
    fun `anything else is worded generically`() {
        assertEquals(LoadError.Other, SerializationException("Unexpected JSON token").toLoadError())
    }
}
