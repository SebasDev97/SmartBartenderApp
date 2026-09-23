package com.example.smartbartender

import com.example.smartbartender.data.hardware.dto.EventEnvelopeDto
import com.example.smartbartender.data.hardware.dto.HealthDto
import com.example.smartbartender.data.hardware.dto.LedDto
import com.example.smartbartender.data.hardware.dto.MachineStatusDto
import com.example.smartbartender.data.hardware.dto.PourJobDto
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.StepKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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

    private val status = """{"machineId":"bartender-01","name":"Smart Bartender De-Luxe","firmware":"0.1.0","backend":"simulated","state":"idle","pumpCount":4,"maxPourMl":250.0,"uptimeS":905,"slots":[{"pump":1,"bottleId":"tequila","mlPerSecond":12.5},{"pump":2,"bottleId":"triple_sec","mlPerSecond":12.5},{"pump":3,"bottleId":"lime_juice","mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null}"""

    private val pourJob = """{"jobId":"2a65e4d9-c882-4a5c-ab06-0360709eb065","drinkId":"11007","drinkName":"Margarita","status":"queued","steps":[{"index":0,"kind":"glass","label":"Positioning glass","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":0.0},{"index":2,"kind":"pour","label":"Pouring Triple sec","detail":"15 ml","pump":2,"ml":15.0,"dispensedMl":0.0},{"index":3,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null},{"index":4,"kind":"manual","label":"Salt the rim","detail":"Add this yourself","pump":null,"ml":null,"dispensedMl":null},{"index":5,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null}],"currentStepIndex":0,"progress":0.0,"totalMl":59.0,"dispensedMl":0.0,"startedAtMs":null,"finishedAtMs":null,"error":null}"""

    private val snapshotEvent = """{"type":"snapshot","seq":1,"ts":1790097817547,"data":{"machineId":"bartender-01","name":"Smart Bartender De-Luxe","firmware":"0.1.0","backend":"simulated","state":"idle","pumpCount":4,"maxPourMl":250.0,"uptimeS":905,"slots":[{"pump":1,"bottleId":"tequila","mlPerSecond":12.5},{"pump":2,"bottleId":"triple_sec","mlPerSecond":12.5},{"pump":3,"bottleId":"lime_juice","mlPerSecond":12.5},{"pump":4,"bottleId":null,"mlPerSecond":12.5}],"led":{"enabled":true,"mode":"spectrum","colorHex":"#2AF5E4","brightness":0.6,"cycleMillis":7000},"currentJob":null,"fault":null}}"""

    private val pourEvent = """{"type":"pour","seq":2,"ts":1790097817548,"data":{"jobId":"2a65e4d9-c882-4a5c-ab06-0360709eb065","drinkId":"11007","drinkName":"Margarita","status":"running","steps":[{"index":0,"kind":"glass","label":"Positioning glass","detail":"Cocktail glass","pump":null,"ml":null,"dispensedMl":null},{"index":1,"kind":"pour","label":"Pouring Tequila","detail":"44 ml","pump":1,"ml":44.0,"dispensedMl":0.0},{"index":2,"kind":"pour","label":"Pouring Triple sec","detail":"15 ml","pump":2,"ml":15.0,"dispensedMl":0.0},{"index":3,"kind":"mix","label":"Mixing","detail":"Stirring the blend","pump":null,"ml":null,"dispensedMl":null},{"index":4,"kind":"manual","label":"Salt the rim","detail":"Add this yourself","pump":null,"ml":null,"dispensedMl":null},{"index":5,"kind":"finish","label":"Finishing touch","detail":"Garnish and serve","pump":null,"ml":null,"dispensedMl":null}],"currentStepIndex":0,"progress":0.0,"totalMl":59.0,"dispensedMl":0.0,"startedAtMs":1790097817548,"finishedAtMs":null,"error":null}}"""

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
}
