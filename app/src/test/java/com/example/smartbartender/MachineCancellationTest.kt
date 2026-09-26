package com.example.smartbartender

import com.example.smartbartender.data.hardware.BartenderApi
import com.example.smartbartender.data.hardware.HttpBartenderMachine
import com.example.smartbartender.data.hardware.dto.CalibrationRequestDto
import com.example.smartbartender.data.hardware.dto.CalibrationRunDto
import com.example.smartbartender.data.hardware.dto.CleaningRequestDto
import com.example.smartbartender.data.hardware.dto.CleaningRunDto
import com.example.smartbartender.data.hardware.dto.HealthDto
import com.example.smartbartender.data.hardware.dto.JogRequestDto
import com.example.smartbartender.data.hardware.dto.JogResponseDto
import com.example.smartbartender.data.hardware.dto.LedDto
import com.example.smartbartender.data.hardware.dto.LedRequestDto
import com.example.smartbartender.data.hardware.dto.MachineStatusDto
import com.example.smartbartender.data.hardware.dto.PourJobDto
import com.example.smartbartender.data.hardware.dto.PourRequestDto
import com.example.smartbartender.data.hardware.dto.SensorReadingDto
import com.example.smartbartender.data.hardware.dto.SlotsRequestDto
import com.example.smartbartender.data.local.MachineSettingsStore
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.MachineSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A machine command whose caller goes away must end as a cancellation, not as a failure:
 * the pour's failure branch clears `activeJobId`, and a cancelled `startPour` used to take it.
 */
class MachineCancellationTest {

    /** A machine that never answers. Only [health] is reached; `testConnection` calls it first. */
    private class SilentApi : BartenderApi {
        override suspend fun health(url: String): HealthDto = awaitCancellation()
        override suspend fun status(url: String): MachineStatusDto = error("not used")
        override suspend fun putSlots(url: String, body: SlotsRequestDto): MachineStatusDto = error("not used")
        override suspend fun startPour(url: String, idempotencyKey: String, body: PourRequestDto): PourJobDto =
            error("not used")
        override suspend fun pour(url: String): PourJobDto = error("not used")
        override suspend fun abort(url: String): PourJobDto = error("not used")
        override suspend fun putLed(url: String, body: LedRequestDto): LedDto = error("not used")
        override suspend fun jog(url: String, body: JogRequestDto): JogResponseDto = error("not used")
        override suspend fun sensor(url: String): SensorReadingDto = error("not used")
        override suspend fun measureReference(url: String): SensorReadingDto = error("not used")
        override suspend fun startCalibration(url: String, body: CalibrationRequestDto): CalibrationRunDto =
            error("not used")
        override suspend fun abortCalibration(url: String): CalibrationRunDto = error("not used")
        override suspend fun startCleaning(url: String, body: CleaningRequestDto): CleaningRunDto = error("not used")
        override suspend fun abortCleaning(url: String): CleaningRunDto = error("not used")
    }

    /** The machine reads its settings only once started, which these tests never do. */
    private object UnusedSettings : MachineSettingsStore {
        override val ledShowEnabled: Flow<Boolean> = emptyFlow()
        override val machineAddress: Flow<MachineAddress> = emptyFlow()
        override suspend fun setLedShowEnabled(enabled: Boolean) = Unit
        override suspend fun setMachineAddress(host: String, port: Int, enabled: Boolean) = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled command propagates the cancellation instead of returning a failure`() = runTest {
        val machine = HttpBartenderMachine(SilentApi(), OkHttpClient(), UnusedSettings, backgroundScope)

        var result: Result<MachineSnapshot>? = null
        val caller = launch { result = machine.testConnection("bartender.local", MachineAddress.DEFAULT_PORT) }
        runCurrent()
        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertNull("a cancelled call must not hand its caller a Result", result)
    }
}
