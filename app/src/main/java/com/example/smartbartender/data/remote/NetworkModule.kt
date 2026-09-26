package com.example.smartbartender.data.remote

import com.example.smartbartender.data.network.LenientJson
import com.example.smartbartender.data.network.converterFactory
import com.example.smartbartender.data.network.logRequestsIf
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    /** Test API key "1" is baked into the path, as documented by TheCocktailDB. */
    private const val BASE_URL = "https://www.thecocktaildb.com/api/json/v1/1/"

    private fun okHttpClient(debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .logRequestsIf(debug)
        .build()

    fun createApi(debug: Boolean): CocktailApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient(debug))
        .addConverterFactory(LenientJson.converterFactory())
        .build()
        .create(CocktailApi::class.java)
}
