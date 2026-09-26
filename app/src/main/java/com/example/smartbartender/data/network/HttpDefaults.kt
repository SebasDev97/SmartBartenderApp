package com.example.smartbartender.data.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * What TheCocktailDB and the machine share: both may send fields, or values, this build has
 * never heard of, and neither is allowed to turn that into a crash.
 */
val LenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

fun Json.converterFactory(): Converter.Factory = asConverterFactory("application/json".toMediaType())

/** One line per request in debug builds; nothing in release. */
fun OkHttpClient.Builder.logRequestsIf(debug: Boolean): OkHttpClient.Builder = apply {
    if (debug) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
}
