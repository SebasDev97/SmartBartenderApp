package com.example.smartbartender.data.hardware

import com.example.smartbartender.data.hardware.dto.HealthDto
import com.example.smartbartender.data.hardware.dto.JogRequestDto
import com.example.smartbartender.data.hardware.dto.JogResponseDto
import com.example.smartbartender.data.hardware.dto.LedDto
import com.example.smartbartender.data.hardware.dto.LedRequestDto
import com.example.smartbartender.data.hardware.dto.MachineStatusDto
import com.example.smartbartender.data.hardware.dto.PourJobDto
import com.example.smartbartender.data.hardware.dto.PourRequestDto
import com.example.smartbartender.data.hardware.dto.SlotsRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * As documented in Pi/API.md.
 * Every call takes an absolute [Url]: the machine's address is typed in by the user at
 * runtime, and this way one Retrofit instance serves whatever address is current instead of
 * being rebuilt on every change.
 */
interface BartenderApi {

    @GET
    suspend fun health(@Url url: String): HealthDto

    @GET
    suspend fun status(@Url url: String): MachineStatusDto

    @PUT
    suspend fun putSlots(@Url url: String, @Body body: SlotsRequestDto): MachineStatusDto

    /**
     * Starting a pour is idempotent on `jobId`: replaying the same id returns the job that is
     * already running rather than pouring a second drink, so a retry after a timeout is safe.
     */
    @POST
    suspend fun startPour(
        @Url url: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PourRequestDto,
    ): PourJobDto

    @GET
    suspend fun pour(@Url url: String): PourJobDto

    @POST
    suspend fun abort(@Url url: String): PourJobDto

    @PUT
    suspend fun putLed(@Url url: String, @Body body: LedRequestDto): LedDto

    @POST
    suspend fun jog(@Url url: String, @Body body: JogRequestDto): JogResponseDto
}
