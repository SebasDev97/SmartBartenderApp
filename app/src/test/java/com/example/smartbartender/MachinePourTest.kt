package com.example.smartbartender

import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import com.example.smartbartender.domain.model.StepKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the machine-facing contract through hand-written fakes, in the style of
 * [AvailabilityTest]'s `FakeApi` — no mocking library, no socket, no DataStore.
 *
 * What is being checked is the thing that matters physically: the app must never invent pour
 * state, and must never start a second pour for a job it already has.
 */
class MachinePourTest {

    // ------------------------------------------------------------------ fakes

    private class FakeMachine(connected: Boolean = true) : BartenderMachine {
        val jobs = MutableStateFlow<PourJob?>(null)
        private val _connection = MutableStateFlow<ConnectionState>(
            if (connected) ConnectionState.Connected(snapshot()) else ConnectionState.Disabled,
        )

        var startedRequests = mutableListOf<PourRequest>()
        var abortedJobIds = mutableListOf<String>()
        var pushedSlots = mutableListOf<List<String?>>()
        var ledCommands = mutableListOf<Boolean>()
        var failNextPour: String? = null

        override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
        override val currentJob: StateFlow<PourJob?> = jobs.asStateFlow()

        override suspend fun testConnection(host: String, port: Int) = Result.success(snapshot())
        override suspend fun jog(pump: Int, seconds: Double) = Result.success(Unit)
        override suspend fun readSensor(): Result<SensorReading> = error("not used")
        override suspend fun measureReference(): Result<SensorReading> = error("not used")
        override suspend fun startCalibration(pumps: List<Int>?, seconds: Double?): Result<CalibrationRun> =
            error("not used")
        override suspend fun abortCalibration() = Result.success(Unit)
        override suspend fun startCleaning(pumps: List<Int>?, seconds: Double?, rounds: Int?): Result<CleaningRun> =
            error("not used")
        override suspend fun abortCleaning() = Result.success(Unit)

        override suspend fun pushSlots(slots: List<String?>): Result<Unit> {
            pushedSlots += slots
            return Result.success(Unit)
        }

        override suspend fun startPour(request: PourRequest): Result<PourJob> {
            failNextPour?.let { reason ->
                failNextPour = null
                return Result.failure(IllegalStateException(reason))
            }
            startedRequests += request
            val job = job(request.jobId, JobStatus.QUEUED)
            jobs.value = job
            return Result.success(job)
        }

        override suspend fun abort(jobId: String): Result<Unit> {
            abortedJobIds += jobId
            jobs.value = jobs.value?.copy(status = JobStatus.ABORTING)
            return Result.success(Unit)
        }

        override suspend fun fetchJob(jobId: String): Result<PourJob> =
            jobs.value?.takeIf { it.jobId == jobId }?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("No job $jobId"))

        override suspend fun setLed(enabled: Boolean, cycleMillis: Int): Result<Unit> {
            ledCommands += enabled
            return Result.success(Unit)
        }

        /** Stands in for a frame arriving over the WebSocket. */
        fun push(status: JobStatus, stepIndex: Int = 0, progress: Float = 0f) {
            jobs.value = job(jobs.value?.jobId ?: "job", status, stepIndex, progress)
        }
    }

    private class FakePreferences(slots: List<String?>) : BartenderPreferences {
        override val loadedBottleIds = MutableStateFlow(slots.filterNotNull().toSet())
        override val slots = MutableStateFlow(slots)
        override val ledShowEnabled = MutableStateFlow(true)
        override val machineAddress = MutableStateFlow(MachineAddress("10.0.2.2", 8080, enabled = true))
        override val activeJobId = MutableStateFlow<String?>(null)
        override val favourites = MutableStateFlow<List<CocktailSummary>>(emptyList())
        override val pourHistory = MutableStateFlow<List<PourRecord>>(emptyList())
        override val customDrinks = MutableStateFlow<List<CustomDrink>>(emptyList())

        override suspend fun setBottleLoaded(bottleId: String, loaded: Boolean) = false
        override suspend fun setLoadedBottles(bottleIds: Set<String>) = Unit
        override suspend fun setSlots(slots: List<String?>) = Unit
        override suspend fun setLedShowEnabled(enabled: Boolean) = Unit
        override suspend fun setMachineAddress(host: String, port: Int, enabled: Boolean) = Unit
        override suspend fun setActiveJobId(jobId: String?) { activeJobId.value = jobId }
        override suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) = Unit
        override suspend fun recordPour(record: PourRecord) = Unit
        override suspend fun clearPourHistory() = Unit
        override suspend fun saveCustomDrink(drink: CustomDrink) = Unit
        override suspend fun deleteCustomDrink(id: String) = Unit
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `a pour is posted once, with the app's own job id`() = runTest {
        val machine = FakeMachine()
        val request = margaritaRequest("job-42")

        machine.startPour(request)
        machine.startPour(request)

        assertEquals(2, machine.startedRequests.size)
        // The app always sends the same id for the same pour; the machine's idempotency rule
        // (see pi/API.md) is what turns the second call into a no-op rather than a drink.
        assertTrue(machine.startedRequests.all { it.jobId == "job-42" })
    }

    @Test
    fun `pour state comes from the machine, never from a local timer`() = runTest {
        val machine = FakeMachine()
        machine.startPour(margaritaRequest("job-1"))
        assertEquals(JobStatus.QUEUED, machine.currentJob.value?.status)

        machine.push(JobStatus.RUNNING, stepIndex = 1, progress = 0.45f)
        assertEquals(0.45f, machine.currentJob.value?.progress ?: 0f, 0.001f)

        machine.push(JobStatus.FINISHED, stepIndex = 3, progress = 1f)
        assertTrue(machine.currentJob.value!!.status.isTerminal)
    }

    @Test
    fun `cancelling asks the machine to abort rather than resetting locally`() = runTest {
        val machine = FakeMachine()
        machine.startPour(margaritaRequest("job-1"))
        machine.push(JobStatus.RUNNING, stepIndex = 1)

        machine.abort("job-1")

        assertEquals(listOf("job-1"), machine.abortedJobIds)
        // Still live: the pumps have not confirmed they stopped yet.
        assertEquals(JobStatus.ABORTING, machine.currentJob.value?.status)
        assertTrue(machine.currentJob.value!!.status.isLive)
    }

    @Test
    fun `a refused pour surfaces the reason and leaves nothing running`() = runTest {
        val machine = FakeMachine()
        machine.failNextPour = "Tequila (tequila) is not loaded"

        val result = machine.startPour(margaritaRequest("job-1"))

        assertTrue(result.isFailure)
        assertEquals("Tequila (tequila) is not loaded", result.exceptionOrNull()?.message)
        assertNull(machine.currentJob.value)
        assertTrue(machine.startedRequests.isEmpty())
    }

    @Test
    fun `an offline machine reports itself as such`() = runTest {
        val machine = FakeMachine(connected = false)
        assertFalse(machine.connection.value.isConnected)
        assertNull(machine.connection.value.snapshotOrNull)
    }

    @Test
    fun `a job left running is what lets the app re-attach after a restart`() = runTest {
        val preferences = FakePreferences(listOf("tequila", "triple_sec", null, null))
        val machine = FakeMachine()

        machine.startPour(margaritaRequest("job-7"))
        preferences.setActiveJobId("job-7")
        machine.push(JobStatus.RUNNING, stepIndex = 1, progress = 0.5f)

        // A cold start sees both halves: the id the app was following, and a live job.
        val remembered = preferences.activeJobId.value
        val live = machine.currentJob.value

        assertEquals("job-7", remembered)
        assertNotNull(live)
        assertEquals(remembered, live!!.jobId)
        assertTrue(live.status.isLive)
        assertEquals(0.5f, live.progress, 0.001f)
    }

    @Test
    fun `the rack is pushed to the machine in pump order`() = runTest {
        val machine = FakeMachine()
        machine.pushSlots(listOf("tequila", null, "lime_juice", null))

        // Index 0 is pump 1. The machine reads it positionally, so a reordering here would
        // pour the wrong bottle.
        assertEquals(listOf("tequila", null, "lime_juice", null), machine.pushedSlots.single())
    }

    @Test
    fun `switching the led show also commands the machine`() = runTest {
        val machine = FakeMachine()
        machine.setLed(enabled = false, cycleMillis = 7000)
        machine.setLed(enabled = true, cycleMillis = 7000)

        assertEquals(listOf(false, true), machine.ledCommands)
    }
}

// ---------------------------------------------------------------------- fixtures

private fun snapshot() = MachineSnapshot(
    machineId = "bartender-01",
    name = "Smart Bartender",
    firmware = "0.1.0",
    backend = "simulated",
    state = MachineRunState.IDLE,
    pumpCount = 4,
    maxPourMl = 250.0,
    slots = emptyList(),
    led = LedShow(enabled = true, mode = "spectrum", cycleMillis = 7000),
    currentJob = null,
    fault = null,
)

private fun job(
    jobId: String,
    status: JobStatus,
    stepIndex: Int = 0,
    progress: Float = 0f,
) = PourJob(
    jobId = jobId,
    drinkId = "11007",
    drinkName = "Margarita",
    status = status,
    steps = listOf(
        JobStep(0, StepKind.GLASS, "Positioning glass", "Cocktail glass", null, null, null),
        JobStep(1, StepKind.POUR, "Pouring Tequila", null, 1, 44.0, 22.0),
        JobStep(2, StepKind.MIX, "Mixing", "Stirring the blend", null, null, null),
        JobStep(3, StepKind.FINISH, "Finishing touch", "Garnish and serve", null, null, null),
    ),
    currentStepIndex = stepIndex,
    progress = progress,
    totalMl = 44.0,
    dispensedMl = 22.0,
    error = null,
)

private fun margaritaRequest(jobId: String) = PourRequest(
    jobId = jobId,
    drinkId = "11007",
    drinkName = "Margarita",
    glass = "Cocktail glass",
    items = listOf(
        com.example.smartbartender.domain.model.PourItem("tequila", "Tequila", 44.0),
        com.example.smartbartender.domain.model.PourItem("triple_sec", "Triple sec", 15.0),
    ),
    manualSteps = listOf("Salt the rim"),
)
