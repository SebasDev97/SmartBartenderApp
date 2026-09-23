package com.example.smartbartender.data.hardware

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Networking for the bartender machine. Mirrors [com.example.smartbartender.data.remote.NetworkModule]
 * deliberately — same JSON settings, same debug-only logging — with three differences:
 *
 *  - the base URL is a placeholder, because every call passes an absolute `@Url`;
 *  - timeouts are short: a box on the same LAN either answers immediately or is not there;
 *  - a ping interval keeps the event socket honest, so a silently dropped Wi-Fi link is
 *    noticed in seconds rather than hanging forever.
 */
object MachineNetworkModule {

    /** Never used: `@Url` on every call supplies the real address. Retrofit just needs one. */
    private const val PLACEHOLDER_BASE_URL = "http://localhost/"

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun createClient(debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                )
            }
        }
        .build()

    fun createApi(client: OkHttpClient): BartenderApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BartenderApi::class.java)
}
