package com.example.smartbartender

import com.example.smartbartender.data.local.PourHistoryCodec
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.PourHistory
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourOutcome
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PourHistoryTest {

    private val slots = listOf("tequila", "triple_sec", "lime_juice", null)

    @Test
    fun `a finished job records what each bottle dispensed`() {
        val record = PourRecord.from(margarita(JobStatus.FINISHED, tequila = 44.0, tripleSec = 15.0), slots, 1_000)!!

        assertEquals(PourOutcome.FINISHED, record.outcome)
        assertEquals(mapOf("tequila" to 44.0, "triple_sec" to 15.0), record.mlByBottle)
        assertEquals(59.0, record.totalMl, 0.001)
        assertEquals("11007", record.drinkId)
    }

    @Test
    fun `a stopped pour counts only what reached the glass`() {
        val record = PourRecord.from(margarita(JobStatus.ABORTED, tequila = 20.5, tripleSec = 0.0), slots, 1_000)!!

        assertEquals(PourOutcome.ABORTED, record.outcome)
        // The planned 44 + 15 ml never happened; the untouched pump is left out entirely.
        assertEquals(mapOf("tequila" to 20.5), record.mlByBottle)
    }

    @Test
    fun `pumps resolve to bottles positionally`() {
        val swapped = listOf("triple_sec", "tequila", null, null)
        val record = PourRecord.from(margarita(JobStatus.FINISHED, tequila = 44.0, tripleSec = 15.0), swapped, 0)!!

        // Pump 1 poured 44 ml, and pump 1 holds triple sec in this rack.
        assertEquals(44.0, record.mlByBottle.getValue("triple_sec"), 0.001)
    }

    @Test
    fun `a live job is not a record`() {
        assertNull(PourRecord.from(margarita(JobStatus.RUNNING, 10.0, 0.0), slots, 0))
        assertNull(PourRecord.from(margarita(JobStatus.ABORTING, 10.0, 0.0), slots, 0))
    }

    @Test
    fun `the machine's finish time wins over the local clock`() {
        val job = margarita(JobStatus.FINISHED, 44.0, 15.0).copy(finishedAtMs = 42)
        assertEquals(42L, PourRecord.from(job, slots, 1_000)!!.finishedAtMs)
    }

    @Test
    fun `history round-trips through its stored form`() {
        val records = listOf(record("a"), record("b", PourOutcome.ABORTED))
        assertEquals(records, PourHistoryCodec.decode(PourHistoryCodec.encode(records)))
    }

    @Test
    fun `blank or corrupt storage degrades to no history`() {
        assertTrue(PourHistoryCodec.decode("").isEmpty())
        assertTrue(PourHistoryCodec.decode("{not json").isEmpty())
        assertTrue(PourHistoryCodec.decode("""[{"jobId":"x","outcome":"EXPLODED"}]""").isEmpty())
    }

    @Test
    fun `recording the same job twice is a no-op`() {
        val once = PourHistory.append(emptyList(), record("a"))
        assertSame(once, PourHistory.append(once, record("a")))
    }

    @Test
    fun `the oldest pours roll off past the cap`() {
        var history = emptyList<PourRecord>()
        repeat(PourHistory.MAX_RECORDS + 3) { history = PourHistory.append(history, record("job-$it")) }

        assertEquals(PourHistory.MAX_RECORDS, history.size)
        assertEquals("job-3", history.first().jobId)
        assertEquals("job-${PourHistory.MAX_RECORDS + 2}", history.last().jobId)
    }

    private fun record(id: String, outcome: PourOutcome = PourOutcome.FINISHED) =
        PourRecord(id, "11007", "Margarita", outcome, 1_000, mapOf("tequila" to 44.0))

    private fun margarita(status: JobStatus, tequila: Double, tripleSec: Double) = PourJob(
        jobId = "job-1",
        drinkId = "11007",
        drinkName = "Margarita",
        status = status,
        steps = listOf(
            JobStep(0, StepKind.GLASS, "Positioning glass", null, null, null, null),
            JobStep(1, StepKind.POUR, "Pouring Tequila", "44 ml", 1, 44.0, tequila),
            JobStep(2, StepKind.POUR, "Pouring Triple sec", "15 ml", 2, 15.0, tripleSec),
            JobStep(3, StepKind.MANUAL, "Salt the rim", null, null, null, null),
            JobStep(4, StepKind.FINISH, "Finishing touch", null, null, null, null),
        ),
        currentStepIndex = 4,
        progress = 1f,
        totalMl = 59.0,
        dispensedMl = tequila + tripleSec,
        error = null,
    )
}
