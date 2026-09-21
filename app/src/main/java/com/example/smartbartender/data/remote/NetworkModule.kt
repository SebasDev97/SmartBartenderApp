package com.example.smartbartender.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {

    /** Test API key "1" is baked into the path, as documented by TheCocktailDB. */
    private const val BASE_URL = "https://www.thecocktaildb.com/api/json/v1/1/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun okHttpClient(debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                )
            }
        }
        .build()

    fun createApi(debug: Boolean): CocktailApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient(debug))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CocktailApi::class.java)
}
