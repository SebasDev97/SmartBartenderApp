package com.example.smartbartender.data.hardware

import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * The bartender machine, as the rest of the app sees it.
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

    suspend fun setLed(enabled: Boolean, cycleMillis: Int): Result<Unit>
}
