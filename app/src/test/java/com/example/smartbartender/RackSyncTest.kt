package com.example.smartbartender

import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.hardware.RackSync
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.LedMode
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineBackend
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineException
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSlot
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The machine's pump map must follow the phone's rack. What matters: a map the machine
 * refused because it was busy still arrives once it is idle, and nothing is pushed twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RackSyncTest {

    private class FakeRack(slots: List<String?>) : RackStore {
        override val slots = MutableStateFlow(slots)
        override val loadedBottleIds = MutableStateFlow(slots.filterNotNull().toSet())
        override suspend fun setBottleLoaded(bottleId: String, loaded: Boolean) = false
        override suspend fun setLoadedBottles(bottleIds: Set<String>) = Unit
    }

    /** Answers `PUT /slots` like the Pi: refused while busy, otherwise applied and broadcast. */
    private class FakeMachine(state: MachineRunState, slots: List<String?>) : BartenderMachine {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected(snapshot(state, slots)))
        override val currentJob = MutableStateFlow<PourJob?>(null)
        val pushed = mutableListOf<List<String?>>()

        fun setState(state: MachineRunState) {
            val current = connection.value.snapshotOrNull ?: return
            connection.value = ConnectionState.Connected(current.copy(state = state))
        }

        override suspend fun pushSlots(slots: List<String?>): Result<Unit> {
            pushed += slots
            val current = connection.value.snapshotOrNull
                ?: return Result.failure(MachineException(MachineError.NotConfigured))
            if (current.state != MachineRunState.IDLE) {
                return Result.failure(MachineException(MachineError.Refused("MACHINE_BUSY", null, 409)))
            }
            connection.value = ConnectionState.Connected(current.copy(slots = machineSlots(slots)))
            return Result.success(Unit)
        }

        override suspend fun testConnection(host: String, port: Int): Result<MachineSnapshot> = error("not used")
        override suspend fun startPour(request: PourRequest): Result<PourJob> = error("not used")
        override suspend fun abort(jobId: String) = Result.success(Unit)
        override suspend fun fetchJob(jobId: String): Result<PourJob> = error("not used")
        override suspend fun setLed(enabled: Boolean) = Result.success(Unit)
        override suspend fun jog(pump: Int, seconds: Double) = Result.success(Unit)
        override suspend fun readSensor(): Result<SensorReading> = error("not used")
        override suspend fun measureReference(): Result<SensorReading> = error("not used")
        override suspend fun startCalibration(pumps: List<Int>?, seconds: Double?): Result<CalibrationRun> =
            error("not used")
        override suspend fun abortCalibration() = Result.success(Unit)
        override suspend fun startCleaning(pumps: List<Int>?, seconds: Double?, rounds: Int?): Result<CleaningRun> =
            error("not used")
        override suspend fun abortCleaning() = Result.success(Unit)
    }

    private fun TestScope.sync(rack: FakeRack, machine: FakeMachine) {
        RackSync(rack, machine, backgroundScope).start()
        runCurrent()
    }

    @Test
    fun `a machine with a different map is sent the rack`() = runTest {
        val rack = FakeRack(listOf("tequila", "triple_sec", null, null))
        val machine = FakeMachine(MachineRunState.IDLE, listOf("vodka", null, null, null))

        sync(rack, machine)

        assertEquals(listOf(listOf("tequila", "triple_sec", null, null)), machine.pushed)
    }

    @Test
    fun `a machine that already has the rack is left alone`() = runTest {
        val rack = FakeRack(listOf("tequila", "triple_sec", null, null))
        val machine = FakeMachine(MachineRunState.IDLE, listOf("tequila", "triple_sec", null, null))

        sync(rack, machine)

        assertTrue(machine.pushed.isEmpty())
    }

    @Test
    fun `a rack changed mid-pour reaches the machine once it is idle`() = runTest {
        val rack = FakeRack(listOf("tequila", null, null, null))
        val machine = FakeMachine(MachineRunState.BUSY, listOf("tequila", null, null, null))
        sync(rack, machine)

        rack.slots.value = listOf("tequila", "lime_juice", null, null)
        runCurrent()
        assertTrue("a busy machine refuses a new map, so none is sent", machine.pushed.isEmpty())

        machine.setState(MachineRunState.IDLE)
        runCurrent()
        assertEquals(listOf(listOf("tequila", "lime_juice", null, null)), machine.pushed)
        assertEquals(listOf("tequila", "lime_juice", null, null), machine.mapOnMachine())
    }

    @Test
    fun `a rack change is sent once, not again when the machine confirms it`() = runTest {
        val rack = FakeRack(listOf("tequila", null, null, null))
        val machine = FakeMachine(MachineRunState.IDLE, listOf("tequila", null, null, null))
        sync(rack, machine)

        rack.slots.value = listOf("tequila", "lime_juice", null, null)
        runCurrent()
        machine.setState(MachineRunState.BUSY)
        machine.setState(MachineRunState.IDLE)
        runCurrent()

        assertEquals(1, machine.pushed.size)
    }

    @Test
    fun `nothing is sent while the machine is offline`() = runTest {
        val rack = FakeRack(listOf("tequila", null, null, null))
        val machine = FakeMachine(MachineRunState.IDLE, listOf(null, null, null, null))
        machine.connection.value = ConnectionState.Connecting
        sync(rack, machine)

        rack.slots.value = listOf("tequila", "lime_juice", null, null)
        runCurrent()
        assertTrue(machine.pushed.isEmpty())

        machine.connection.value = ConnectionState.Connected(snapshot(MachineRunState.IDLE, listOf(null, null, null, null)))
        runCurrent()
        assertEquals(listOf(listOf("tequila", "lime_juice", null, null)), machine.pushed)
    }

    private fun FakeMachine.mapOnMachine() = connection.value.snapshotOrNull!!.slots.map { it.bottleId }
}

// ---------------------------------------------------------------------- fixtures

private fun machineSlots(slots: List<String?>) = slots.mapIndexed { index, id -> MachineSlot(index + 1, id, 12.5) }

private fun snapshot(state: MachineRunState, slots: List<String?>) = MachineSnapshot(
    machineId = "bartender-01",
    name = "Smart Bartender",
    firmware = "0.1.0",
    backend = MachineBackend.SIMULATED,
    state = state,
    pumpCount = 4,
    maxPourMl = 250.0,
    slots = machineSlots(slots),
    led = LedShow(enabled = true, mode = LedMode.SPECTRUM, cycleMillis = LedShow.CYCLE_MILLIS),
    currentJob = null,
    fault = null,
)
