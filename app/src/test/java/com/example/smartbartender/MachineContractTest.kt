package com.example.smartbartender

import com.example.smartbartender.data.hardware.dto.CalibrationRunDto
import com.example.smartbartender.data.hardware.dto.CleaningRunDto
import com.example.smartbartender.data.hardware.dto.EventEnvelopeDto
import com.example.smartbartender.data.hardware.dto.HealthDto
import com.example.smartbartender.data.hardware.dto.LedDto
import com.example.smartbartender.data.hardware.dto.MachineStatusDto
import com.example.smartbartender.data.hardware.dto.PourJobDto
import com.example.smartbartender.data.hardware.dto.SensorReadingDto
import com.example.smartbartender.domain.model.CalibrationPhase
import com.example.smartbartender.domain.model.CalibrationStatus
import com.example.smartbartender.domain.model.CleaningPhase
import com.example.smartbartender.domain.model.CleaningStatus
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.StepKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of this project are written from `pi/API.md` independently, so the one thing
 * that can silently break is the wire format itself.
 *
 * Every string below was **captured from the running machine service** (`python -m app.main
 * --simulate`), not written by hand. If the Pi's models change and these stop decoding, that
 * is the contract drifting — fix one side or the other, don't loosen the test.
 */
class MachineContractTest {

    /** The same configuration the app uses; see MachineNetworkModule. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val health = """{"ok":true,"machineId":"bartender-01","firmware":"0.1.0"}"""

    private val status = """{"machineId":"bartender-01","name":"Smart Bartender","firmware":"0.1.0","backend":"simulated","state":"idle","pumpCount":4,"maxPourMl":250.0,"uptimeS":905,"slots":[{"pump":1,"bottleId":"tequila","mlPerSecond":12.5},{"pump":2,"bottleId":"triple_sec","mlPerSecond":12.5},{"pump":3,"bottleId":"lime_juice","mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null}"""

    private val pourJob = """{"jobId":"2a65e4d9-c882-4a5c-ab06-0360709eb065","drinkId":"11007","drinkName":"Margarita","status":"queued","steps":[{"index":0,"kind":"glass","label":"Positioning glass","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":0.0},{"index":2,"kind":"pour","label":"Pouring Triple sec","detail":"15 ml","pump":2,"ml":15.0,"dispensedMl":0.0},{"index":3,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null},{"index":4,"kind":"manual","label":"Salt the rim","detail":"Add this yourself","pump":null,"ml":null,"dispensedMl":null},{"index":5,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null}],"currentStepIndex":0,"progress":0.0,"totalMl":59.0,"dispensedMl":0.0,"startedAtMs":null,"finishedAtMs":null,"error":null}"""

    private val snapshotEvent = """{"type":"snapshot","seq":1,"ts":1790097817547,"data":{"machineId":"bartender-01","name":"Smart Bartender","firmware":"0.1.0","backend":"simulated","state":"idle","pumpCount":4,"maxPourMl":250.0,"uptimeS":905,"slots":[{"pump":1,"bottleId":"tequila","mlPerSecond":12.5},{"pump":2,"bottleId":"triple_sec","mlPerSecond":12.5},{"pump":3,"bottleId":"lime_juice","mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null}}"""

    private val pourEvent = """{"type":"pour","seq":2,"ts":1790097817548,"data":{"jobId":"2a65e4d9-c882-4a5c-ab06-0360709eb065","drinkId":"11007","drinkName":"Margarita","status":"running","steps":[{"index":0,"kind":"glass","label":"Positioning glass","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":0.0},{"index":2,"kind":"pour","label":"Pouring Triple sec","detail":"15 ml","pump":2,"ml":15.0,"dispensedMl":0.0},{"index":3,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null},{"index":4,"kind":"manual","label":"Salt the rim","detail":"Add this yourself","pump":null,"ml":null,"dispensedMl":null},{"index":5,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null}],"currentStepIndex":0,"progress":0.0,"totalMl":59.0,"dispensedMl":0.0,"startedAtMs":1790097817548,"finishedAtMs":null,"error":null}}"""

    // Captured after the glass sensor, measured volumes and calibration were added. The strings
    // above predate them, so they double as "an older Pi still decodes" tests.

    private val waitingForGlassEvent = """{"type":"pour","seq":6,"ts":1790331391239,"data":{"jobId":"5aa275fa-d5e0-42f0-aa74-aa71298405b9","drinkId":null,"drinkName":"Margarita","status":"running","steps":[{"index":0,"kind":"glass","label":"Place a glass","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null,"measured":false},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":0.0,"measured":false},{"index":2,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null,"measured":false},{"index":3,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null,"measured":false}],"currentStepIndex":0,"progress":0.0,"totalMl":44.0,"dispensedMl":0.0,"startedAtMs":1790331391239,"finishedAtMs":null,"error":null,"waitingForGlass":true}}"""

    private val measuredPourEvent = """{"type":"pour","seq":15,"ts":1790331392436,"data":{"jobId":"5aa275fa-d5e0-42f0-aa74-aa71298405b9","drinkId":null,"drinkName":"Margarita","status":"finished","steps":[{"index":0,"kind":"glass","label":"Glass detected","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null,"measured":false},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":44.12,"measured":true},{"index":2,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null,"measured":false},{"index":3,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null,"measured":false}],"currentStepIndex":3,"progress":1.0,"totalMl":44.0,"dispensedMl":44.12,"startedAtMs":1790331391239,"finishedAtMs":1790331392435,"error":null,"waitingForGlass":false}}"""

    private val calibratingSnapshotEvent = """{"type":"snapshot","seq":17,"ts":1790331392945,"data":{"machineId":"bartender-01","name":"Smart Bartender","firmware":"0.1.0","backend":"simulated","state":"busy","pumpCount":4,"maxPourMl":250.0,"uptimeS":4,"slots":[{"pump":1,"bottleId":"tequila","mlPerSecond":12.5},{"pump":2,"bottleId":"triple_sec","mlPerSecond":12.5},{"pump":3,"bottleId":null,"mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null,"sensor":{"referenceCm":16.3,"glassDiameterMm":58.0,"calibratedAtMs":null},"calibration":{"runId":"dbf52d12-625f-48e0-baa2-39ac407f32c4","status":"running","phase":"waiting_glass","pumps":[1],"currentPump":null,"message":"","results":[],"startedAtMs":1790331392945,"finishedAtMs":null,"error":null}}}"""

    // Captured after cleaning was added.

    private val cleaningSnapshotEvent = """{"type":"snapshot","seq":2,"ts":1790332551057,"data":{"machineId":"bartender-01","name":"Smart Bartender","firmware":"0.1.0","backend":"simulated","state":"busy","pumpCount":4,"maxPourMl":250.0,"uptimeS":1,"slots":[{"pump":1,"bottleId":null,"mlPerSecond":12.5},{"pump":2,"bottleId":null,"mlPerSecond":12.5},{"pump":3,"bottleId":null,"mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null,"sensor":{"referenceCm":16.3,"glassDiameterMm":58.0,"calibratedAtMs":null},"calibration":null,"cleaning":{"runId":"3553974e-59a8-4af8-b4af-276274eb74bf","status":"running","phase":"pumping","pumps":[1,2],"rounds":1,"seconds":2.0,"currentRound":null,"currentPump":null,"progress":0.0,"message":"","startedAtMs":1790332551057,"finishedAtMs":null,"error":null}}}"""

    private val cleaningEvent = """{"type":"cleaning","seq":4,"ts":1790332551462,"data":{"runId":"3553974e-59a8-4af8-b4af-276274eb74bf","status":"running","phase":"pumping","pumps":[1,2],"rounds":1,"seconds":2.0,"currentRound":1,"currentPump":1,"progress":0.405,"message":"Rinsing pump 1 (round 1 of 1)","startedAtMs":1790332551057,"finishedAtMs":null,"error":null}}"""

    private val cleaningFinishedEvent = """{"type":"cleaning","seq":8,"ts":1790332552312,"data":{"runId":"3553974e-59a8-4af8-b4af-276274eb74bf","status":"finished","phase":"done","pumps":[1,2],"rounds":1,"seconds":2.0,"currentRound":null,"currentPump":null,"progress":1.0,"message":"Rinsed — put your bottles back","startedAtMs":1790332551057,"finishedAtMs":1790332552312,"error":null}}"""

    private val calibrationEvent = """{"type":"calibration","seq":25,"ts":1790331393672,"data":{"runId":"dbf52d12-625f-48e0-baa2-39ac407f32c4","status":"finished","phase":"done","pumps":[1],"currentPump":null,"message":"Calibrated 1 pump","results":[{"pump":1,"mlPerSecond":12.581,"volumeMl":12.68,"seconds":1.008,"startDistanceCm":13.93,"endDistanceCm":13.45}],"startedAtMs":1790331392945,"finishedAtMs":1790331393672,"error":null}}"""

    private val sensorReading = """{"distanceCm":13.45,"glassPresent":true,"referenceCm":16.3}"""

    private val ledResponse = """{"enabled":false,"mode":"off","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000}"""

    @Test
    fun `health decodes`() {
        val decoded = json.decodeFromString<HealthDto>(health)
        assertTrue(decoded.ok)
        assertEquals("bartender-01", decoded.machineId)
    }

    @Test
    fun `a real status decodes into the domain`() {
        val snapshot = json.decodeFromString<MachineStatusDto>(status).toDomain()

        assertEquals("bartender-01", snapshot.machineId)
        assertEquals(MachineRunState.IDLE, snapshot.state)
        assertEquals(4, snapshot.pumpCount)
        assertTrue(snapshot.isSimulated)
        assertEquals(250.0, snapshot.maxPourMl, 0.01)
    }

    @Test
    fun `slots keep their pump numbers and their empties`() {
        val slots = json.decodeFromString<MachineStatusDto>(status).toDomain().slots

        assertEquals(listOf(1, 2, 3, 4), slots.map { it.pump })
        assertEquals("tequila", slots.first { it.pump == 1 }.bottleId)
        // An empty slot must survive as null, not as an empty string.
        assertNull(slots.first { it.pump == 4 }.bottleId)
    }

    @Test
    fun `a real pour job decodes, including the manual step`() {
        val job = json.decodeFromString<PourJobDto>(pourJob).toDomain()

        assertEquals("Margarita", job.drinkName)
        assertEquals(
            listOf(StepKind.GLASS, StepKind.POUR, StepKind.POUR, StepKind.MIX, StepKind.MANUAL, StepKind.FINISH),
            job.steps.map { it.kind },
        )
        assertEquals(59.0, job.totalMl, 0.01)
        assertEquals(1, job.steps.first { it.kind == StepKind.POUR }.pump)
        assertTrue(job.steps.any { it.kind == StepKind.MANUAL && it.label == "Salt the rim" })
    }

    @Test
    fun `a snapshot event unwraps to a machine status`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(snapshotEvent)
        assertEquals("snapshot", envelope.type)

        val snapshot = json.decodeFromJsonElement(MachineStatusDto.serializer(), envelope.payload).toDomain()
        assertEquals("bartender-01", snapshot.machineId)
    }

    @Test
    fun `a pour event unwraps to a live job`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(pourEvent)
        assertEquals("pour", envelope.type)
        assertTrue(envelope.seq > 0)

        val job = json.decodeFromJsonElement(PourJobDto.serializer(), envelope.payload).toDomain()
        assertTrue(job.status.isLive)
        assertTrue(job.progress in 0f..1f)
    }

    @Test
    fun `an led response decodes`() {
        val led = json.decodeFromString<LedDto>(ledResponse).toDomain()
        assertEquals(false, led.enabled)
        assertEquals("off", led.mode)
    }

    @Test
    fun `an unknown status or step kind degrades instead of throwing`() {
        // Newer firmware may add values this build has never heard of. That must not be
        // fatal halfway through a pour.
        val future = pourJob.replace("\"status\":\"queued\"", "\"status\":\"priming\"")
            .replace("\"kind\":\"mix\"", "\"kind\":\"shake\"")
        val job = json.decodeFromString<PourJobDto>(future).toDomain()

        assertEquals(JobStatus.UNKNOWN, job.status)
        assertTrue(job.steps.any { it.kind == StepKind.UNKNOWN })
    }

    @Test
    fun `a pour waiting for its glass says so`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(waitingForGlassEvent)
        val job = json.decodeFromJsonElement(PourJobDto.serializer(), envelope.payload).toDomain()

        assertTrue(job.waitingForGlass)
        assertEquals(StepKind.GLASS, job.steps[job.currentStepIndex].kind)
        assertEquals("Place a glass", job.steps[0].label)
    }

    @Test
    fun `a finished pour carries the measured volume`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(measuredPourEvent)
        val job = json.decodeFromJsonElement(PourJobDto.serializer(), envelope.payload).toDomain()

        assertFalse(job.waitingForGlass)
        val pour = job.steps.first { it.kind == StepKind.POUR }
        assertTrue(pour.measured)
        assertNotNull(pour.dispensedMl)
    }

    @Test
    fun `fields an older Pi does not send default to not-measured and no sensor`() {
        val job = json.decodeFromString<PourJobDto>(pourJob).toDomain()
        assertFalse(job.waitingForGlass)
        assertTrue(job.steps.none { it.measured })

        val snapshot = json.decodeFromString<MachineStatusDto>(status).toDomain()
        assertNull(snapshot.sensorReferenceCm)
        assertNull(snapshot.calibration)
        assertNull(snapshot.cleaning)
    }

    @Test
    fun `a snapshot during calibration carries the sensor and the running run`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(calibratingSnapshotEvent)
        val snapshot = json.decodeFromJsonElement(MachineStatusDto.serializer(), envelope.payload).toDomain()

        assertEquals(MachineRunState.BUSY, snapshot.state)
        assertEquals(16.3, snapshot.sensorReferenceCm!!, 0.001)
        assertEquals(58.0, snapshot.glassDiameterMm, 0.001)
        assertEquals(CalibrationStatus.RUNNING, snapshot.calibration?.status)
    }

    @Test
    fun `a calibration event carries each pump's result`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(calibrationEvent)
        assertEquals("calibration", envelope.type)

        val run = json.decodeFromJsonElement(CalibrationRunDto.serializer(), envelope.payload).toDomain()
        assertEquals(CalibrationStatus.FINISHED, run.status)
        assertEquals(CalibrationPhase.DONE, run.phase)
        assertEquals(listOf(1), run.results.map { it.pump })
        assertTrue(run.results.single().mlPerSecond > 0)
    }

    @Test
    fun `a snapshot during cleaning carries the running run`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(cleaningSnapshotEvent)
        val snapshot = json.decodeFromJsonElement(MachineStatusDto.serializer(), envelope.payload).toDomain()

        assertEquals(MachineRunState.BUSY, snapshot.state)
        assertNull(snapshot.calibration)
        assertEquals(CleaningStatus.RUNNING, snapshot.cleaning?.status)
        assertEquals(listOf(1, 2), snapshot.cleaning?.pumps)
    }

    @Test
    fun `a cleaning event carries the pump, the round and the progress`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(cleaningEvent)
        assertEquals("cleaning", envelope.type)

        val run = json.decodeFromJsonElement(CleaningRunDto.serializer(), envelope.payload).toDomain()
        assertEquals(CleaningStatus.RUNNING, run.status)
        assertEquals(CleaningPhase.PUMPING, run.phase)
        assertEquals(1, run.currentRound)
        assertEquals(1, run.currentPump)
        assertEquals(1, run.rounds)
        assertEquals(2.0, run.seconds, 0.001)
        assertEquals(0.405f, run.progress, 0.001f)
    }

    @Test
    fun `the last cleaning event ends the run`() {
        val envelope = json.decodeFromString<EventEnvelopeDto>(cleaningFinishedEvent)
        val run = json.decodeFromJsonElement(CleaningRunDto.serializer(), envelope.payload).toDomain()

        assertEquals(CleaningStatus.FINISHED, run.status)
        assertEquals(CleaningPhase.DONE, run.phase)
        assertTrue(run.status.isTerminal)
        assertNull(run.currentPump)
        assertEquals(1f, run.progress, 0.0001f)
        assertNull(run.error)
    }

    @Test
    fun `a sensor reading decodes`() {
        val reading = json.decodeFromString<SensorReadingDto>(sensorReading).toDomain()
        assertNotNull(reading.distanceCm)
        assertEquals(16.3, reading.referenceCm!!, 0.001)
    }
}
