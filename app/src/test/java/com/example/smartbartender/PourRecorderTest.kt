package com.example.smartbartender

import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.hardware.PourRecorder
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSlot
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourHistory
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourOutcome
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.StepKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder decides what ends up on the Stats tab. What matters: only this phone's pours,
 * each exactly once, and one that ended while the app was dead still gets counted.
 */
class PourRecorderTest {

    private class FakeMachine(connected: Boolean = true) : BartenderMachine {
        override val connection = MutableStateFlow(
            if (connected) ConnectionState.Connected(snapshot()) else ConnectionState.Disabled,
        )
        override val currentJob = MutableStateFlow<PourJob?>(null)

        /** What `GET /api/v1/pours/{jobId}` would answer — the machine keeps its last jobs. */
        val remembered = mutableMapOf<String, PourJob>()
        val fetched = mutableListOf<String>()

        override suspend fun testConnection(host: String, port: Int) = Result.success(snapshot())
        override suspend fun pushSlots(slots: List<String?>) = Result.success(Unit)
        override suspend fun startPour(request: PourRequest): Result<PourJob> = error("not used")
        override suspend fun abort(jobId: String) = Result.success(Unit)
        override suspend fun setLed(enabled: Boolean, cycleMillis: Int) = Result.success(Unit)

        override suspend fun fetchJob(jobId: String): Result<PourJob> {
            fetched += jobId
            return remembered[jobId]?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("No job $jobId"))
        }
    }

    private class FakePreferences : BartenderPreferences {
        override val loadedBottleIds = MutableStateFlow(setOf("tequila", "triple_sec"))
        override val slots = MutableStateFlow<List<String?>>(listOf("tequila", "triple_sec", null, null))
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

        // The real append, so de-duplication is tested rather than faked.
        override suspend fun recordPour(record: PourRecord) {
            pourHistory.value = PourHistory.append(pourHistory.value, record)
        }

        override suspend fun clearPourHistory() { pourHistory.value = emptyList() }
        override suspend fun saveCustomDrink(drink: CustomDrink) = Unit
        override suspend fun deleteCustomDrink(id: String) = Unit
    }

    private fun TestScope.recorder(machine: FakeMachine, preferences: FakePreferences) {
        PourRecorder(machine, preferences, backgroundScope, clock = { 1_000L })
        runCurrent()
    }

    @Test
    fun `this phone's finished pour is recorded with its bottles`() = runTest {
        val machine = FakeMachine()
        val preferences = FakePreferences()
        recorder(machine, preferences)

        preferences.activeJobId.value = "mine"
        machine.currentJob.value = job("mine", JobStatus.RUNNING)
        runCurrent()
        assertTrue(preferences.pourHistory.value.isEmpty())

        machine.currentJob.value = job("mine", JobStatus.FINISHED)
        runCurrent()

        val record = preferences.pourHistory.value.single()
        assertEquals(PourOutcome.FINISHED, record.outcome)
        assertEquals(mapOf("tequila" to 44.0, "triple_sec" to 15.0), record.mlByBottle)
    }

    @Test
    fun `another phone's pour is not this phone's statistic`() = runTest {
        val machine = FakeMachine()
        val preferences = FakePreferences()
        recorder(machine, preferences)

        preferences.activeJobId.value = "mine"
        machine.currentJob.value = job("someone-elses", JobStatus.FINISHED)
        runCurrent()

        assertTrue(preferences.pourHistory.value.isEmpty())
    }

    @Test
    fun `a repeated final frame is counted once`() = runTest {
        val machine = FakeMachine()
        val preferences = FakePreferences()
        recorder(machine, preferences)

        preferences.activeJobId.value = "mine"
        machine.currentJob.value = job("mine", JobStatus.FINISHED)
        runCurrent()
        machine.currentJob.value = job("mine", JobStatus.FINISHED).copy(progress = 0.999f)
        runCurrent()

        assertEquals(1, preferences.pourHistory.value.size)
    }

    @Test
    fun `a stopped pour is recorded as stopped`() = runTest {
        val machine = FakeMachine()
        val preferences = FakePreferences()
        recorder(machine, preferences)

        preferences.activeJobId.value = "mine"
        machine.currentJob.value = job("mine", JobStatus.ABORTED, tripleSec = 0.0)
        runCurrent()

        val record = preferences.pourHistory.value.single()
        assertEquals(PourOutcome.ABORTED, record.outcome)
        assertEquals(mapOf("tequila" to 44.0), record.mlByBottle)
    }

    @Test
    fun `a pour that ended while the app was dead is caught up on reconnect`() = runTest {
        val machine = FakeMachine(connected = false)
        val preferences = FakePreferences()
        preferences.activeJobId.value = "left-running"
        machine.remembered["left-running"] = job("left-running", JobStatus.FINISHED).copy(finishedAtMs = 777)
        recorder(machine, preferences)

        machine.connection.value = ConnectionState.Connected(snapshot())
        runCurrent()

        val record = preferences.pourHistory.value.single()
        assertEquals("left-running", record.jobId)
        assertEquals(777L, record.finishedAtMs)
    }

    @Test
    fun `an already recorded pour is not fetched again`() = runTest {
        val machine = FakeMachine(connected = false)
        val preferences = FakePreferences()
        preferences.activeJobId.value = "done"
        preferences.pourHistory.value = listOf(
            PourRecord("done", "11007", "Margarita", PourOutcome.FINISHED, 1, emptyMap()),
        )
        recorder(machine, preferences)

        machine.connection.value = ConnectionState.Connected(snapshot())
        runCurrent()

        assertTrue(machine.fetched.isEmpty())
    }
}

// ---------------------------------------------------------------------- fixtures

private fun snapshot() = MachineSnapshot(
    machineId = "bartender-01",
    name = "Smart Bartender De-Luxe",
    firmware = "0.1.0",
    backend = "simulated",
    state = MachineRunState.IDLE,
    pumpCount = 4,
    maxPourMl = 250.0,
    slots = listOf(
        MachineSlot(1, "tequila", 12.5),
        MachineSlot(2, "triple_sec", 12.5),
        MachineSlot(3, null, 12.5),
        MachineSlot(4, null, 12.5),
    ),
    led = LedShow(enabled = true, mode = "spectrum", cycleMillis = 7000),
    currentJob = null,
    fault = null,
)

private fun job(jobId: String, status: JobStatus, tripleSec: Double = 15.0) = PourJob(
    jobId = jobId,
    drinkId = "11007",
    drinkName = "Margarita",
    status = status,
    steps = listOf(
        JobStep(0, StepKind.GLASS, "Positioning glass", null, null, null, null),
        JobStep(1, StepKind.POUR, "Pouring Tequila", "44 ml", 1, 44.0, 44.0),
        JobStep(2, StepKind.POUR, "Pouring Triple sec", "15 ml", 2, 15.0, tripleSec),
        JobStep(3, StepKind.FINISH, "Finishing touch", null, null, null, null),
    ),
    currentStepIndex = 3,
    progress = 1f,
    totalMl = 59.0,
    dispensedMl = 44.0 + tripleSec,
    error = null,
)
