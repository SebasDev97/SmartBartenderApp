package com.example.smartbartender.data.remote

import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface CocktailApi {

    @GET("search.php")
    suspend fun searchByName(@Query("s") name: String): CocktailResponse

    /**
     * Full recipes for every drink starting with [letter]. Returns complete records, which
     * makes it far cheaper than looking candidates up one by one.
     */
    @GET("search.php")
    suspend fun searchByFirstLetter(@Query("f") letter: String): CocktailResponse

    @GET("filter.php")
    suspend fun filterByIngredient(@Query("i") ingredient: String): DrinkSummaryResponse

    @GET("lookup.php")
    suspend fun lookupById(@Query("i") id: String): CocktailResponse

    @GET("random.php")
    suspend fun random(): CocktailResponse
}
