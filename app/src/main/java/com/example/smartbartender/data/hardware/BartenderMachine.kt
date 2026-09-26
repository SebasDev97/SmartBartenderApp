package com.example.smartbartender.data.hardware

import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineException
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import kotlinx.coroutines.flow.StateFlow

/**
 * The bartender machine, as the rest of the app sees it.
 *
 * Every command returns a [Result] whose failure is a [MachineException], so callers read
 * the typed reason with `toMachineError()` rather than parsing a message.
 *
 * An interface rather than a class so tests can drive a fake through a scripted pour without
 * a socket — see `MachinePourTest`.
 */
interface BartenderMachine {

    val connection: StateFlow<ConnectionState>

    /** The pour the machine is running, pushed from the machine itself. */
    val currentJob: StateFlow<PourJob?>

    /** One-shot probe for the Settings screen's Test button. Does not change the connection. */
    suspend fun testConnection(host: String, port: Int): Result<MachineSnapshot>

    /** Tells the machine which bottle is in which pump. Index 0 is pump 1. */
    suspend fun pushSlots(slots: List<String?>): Result<Unit>

    suspend fun startPour(request: PourRequest): Result<PourJob>

    suspend fun abort(jobId: String): Result<Unit>

    /** A job the machine still remembers (it keeps the last 20), finished or not. */
    suspend fun fetchJob(jobId: String): Result<PourJob>

    /** Turns the strip's spectrum cycle on, in phase with the phone's preview, or turns it off. */
    suspend fun setLed(enabled: Boolean): Result<Unit>

    /** Runs one pump for [seconds] — to prime its tube, or to hear the relay click. */
    suspend fun jog(pump: Int, seconds: Double): Result<Unit>

    /** One live reading of the glass sensor. */
    suspend fun readSensor(): Result<SensorReading>

    /** Measures the empty tray, which every glass is detected against. No glass on it first! */
    suspend fun measureReference(): Result<SensorReading>

    /**
     * Starts a calibration run into the empty glass. Progress then arrives in the snapshot's
     * `calibration`, pushed by the machine like a pour. Null [pumps] means every pump.
     */
    suspend fun startCalibration(pumps: List<Int>?, seconds: Double? = null): Result<CalibrationRun>

    suspend fun abortCalibration(): Result<Unit>

    /**
     * Rinses the pump lines: each pump in turn runs for [seconds], [rounds] times over, into a
     * container the user has put under the nozzle. The glass sensor is not used. Progress then
     * arrives in the snapshot's `cleaning`. Nulls take the machine's defaults (every pump).
     */
    suspend fun startCleaning(pumps: List<Int>?, seconds: Double? = null, rounds: Int? = null): Result<CleaningRun>

    suspend fun abortCleaning(): Result<Unit>
}
