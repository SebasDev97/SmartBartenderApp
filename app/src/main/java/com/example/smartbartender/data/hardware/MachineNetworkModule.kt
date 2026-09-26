package com.example.smartbartender.data.hardware

import com.example.smartbartender.data.network.LenientJson
import com.example.smartbartender.data.network.converterFactory
import com.example.smartbartender.data.network.logRequestsIf
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Networking for the bartender machine. Shares its JSON settings and debug logging with
 * [com.example.smartbartender.data.remote.NetworkModule], and differs in three ways:
 *
 *  - the base URL is a placeholder, because every call passes an absolute `@Url`;
 *  - timeouts are short: a box on the same LAN either answers immediately or is not there;
 *  - a ping interval keeps the event socket honest, so a silently dropped Wi-Fi link is
 *    noticed in seconds rather than hanging forever.
 */
object MachineNetworkModule {

    /** Never used: `@Url` on every call supplies the real address. Retrofit just needs one. */
    private const val PLACEHOLDER_BASE_URL = "http://localhost/"

    val json: Json = LenientJson

    fun createClient(debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .logRequestsIf(debug)
        .build()

    fun createApi(client: OkHttpClient): BartenderApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.converterFactory())
        .build()
        .create(BartenderApi::class.java)
}
