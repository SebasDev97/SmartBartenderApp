package com.example.smartbartender

import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineFault
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.StepKind
import com.example.smartbartender.ui.screens.detail.PourFailure
import com.example.smartbartender.ui.screens.detail.PourPhase
import com.example.smartbartender.ui.screens.detail.StepVolume
import com.example.smartbartender.ui.screens.detail.reducePour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is driven entirely by what the machine pushes, so this is where "did the UI
 * follow the machine correctly" is answered — no coroutines, no socket, just the transitions.
 */
class PourReducerTest {

    private fun job(
        status: JobStatus,
        stepIndex: Int = 0,
        progress: Float = 0f,
        error: MachineFault? = null,
    ) = PourJob(
        jobId = "job-1",
        drinkId = "11007",
        drinkName = "Margarita",
        status = status,
        steps = listOf(
            JobStep(0, StepKind.GLASS, "Positioning glass", "Cocktail glass", null, null, null),
            JobStep(1, StepKind.POUR, "Pouring Tequila", null, 1, 44.0, 22.0),
            JobStep(2, StepKind.MANUAL, "Salt the rim", "Add this yourself", null, null, null),
            JobStep(3, StepKind.FINISH, "Finishing touch", "Garnish and serve", null, null, null),
        ),
        currentStepIndex = stepIndex,
        progress = progress,
        totalMl = 44.0,
        dispensedMl = 22.0,
        error = error,
    )

    private fun pouring(phase: PourPhase): PourPhase.Pouring {
        assertTrue("expected a live pour, got $phase", phase is PourPhase.Pouring)
        return phase as PourPhase.Pouring
    }

    @Test
    fun `a running job drives the overlay`() {
        val state = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1, progress = 0.4f)))

        assertEquals(1, state.currentStepIndex)
        assertEquals("Pouring Tequila", state.currentStep?.label)
    }

    @Test
    fun `the machine's progress wins over the step-count fallback`() {
        val state = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 0, progress = 0.62f)))
        // Step 0 of 4 would be 0.25 by counting; the machine measured 0.62.
        assertEquals(0.62f, state.progress, 0.001f)
    }

    @Test
    fun `before the machine answers, nothing is claimed to have poured`() {
        val starting = PourPhase.Pouring.starting("job-1")

        assertEquals(0f, starting.progress, 0.001f)
        assertTrue(starting.waitingForGlass)
    }

    @Test
    fun `a pour step shows how much has actually gone in`() {
        val state = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1)))
        assertEquals(StepVolume(dispensedMl = 22.0, plannedMl = 44.0, measured = false), state.steps[1].volume)
        assertNull(state.steps[0].volume)
    }

    @Test
    fun `manual steps are marked so the overlay can say who does them`() {
        val state = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING)))
        assertTrue(state.steps[2].isManual)
        assertFalse(state.steps[1].isManual)
    }

    @Test
    fun `finishing ends the pour`() {
        val state = reducePour(PourPhase.Idle, job(JobStatus.FINISHED, stepIndex = 3, progress = 1f))
        assertEquals(PourPhase.Finished("job-1"), state)
    }

    @Test
    fun `aborting is shown while it happens, and is not reported as an error`() {
        val aborting = pouring(reducePour(PourPhase.Idle, job(JobStatus.ABORTING, stepIndex = 1)))
        assertTrue(aborting.aborting)

        // The user asked for this. Nothing to apologise for.
        assertEquals(PourPhase.Idle, reducePour(aborting, job(JobStatus.ABORTED, stepIndex = 1)))
    }

    @Test
    fun `a failure surfaces the machine's own words`() {
        val fault = MachineFault("PUMP_FAULT", "Pump 2 did not reach target", recoverable = false)
        val state = reducePour(PourPhase.Idle, job(JobStatus.FAILED, error = fault))

        assertEquals(PourPhase.Failed(PourFailure.Faulted("Pump 2 did not reach target")), state)
    }

    @Test
    fun `a whole pour replayed in sequence ends up finished`() {
        val sequence = listOf(
            job(JobStatus.QUEUED),
            job(JobStatus.RUNNING, stepIndex = 0, progress = 0.05f),
            job(JobStatus.RUNNING, stepIndex = 1, progress = 0.5f),
            job(JobStatus.RUNNING, stepIndex = 3, progress = 0.95f),
            job(JobStatus.FINISHED, stepIndex = 3, progress = 1f),
        )
        val end = sequence.fold<PourJob, PourPhase>(PourPhase.Idle) { state, next -> reducePour(state, next) }

        assertEquals(PourPhase.Finished("job-1"), end)
    }

    @Test
    fun `an out-of-range step index from the machine cannot crash the screen`() {
        val state = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 99)))
        assertEquals(3, state.currentStepIndex)
    }

    @Test
    fun `a status this build does not know keeps the pour on screen`() {
        val running = reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1))
        assertEquals(running, reducePour(running, job(JobStatus.UNKNOWN, stepIndex = 2)))
    }

    @Test
    fun `waiting for a glass is shown until the machine sees one`() {
        val waiting = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING).copy(waitingForGlass = true)))
        assertTrue(waiting.waitingForGlass)

        val detected = pouring(reducePour(waiting, job(JobStatus.RUNNING, stepIndex = 1, progress = 0.1f)))
        assertFalse(detected.waitingForGlass)
    }

    @Test
    fun `an aborted wait for a glass is not still waiting`() {
        val state = reducePour(PourPhase.Idle, job(JobStatus.ABORTED).copy(waitingForGlass = true))
        assertEquals(PourPhase.Idle, state)
    }

    @Test
    fun `a measured pour step says it was measured`() {
        val measured = job(JobStatus.RUNNING, stepIndex = 1).let { j ->
            j.copy(steps = j.steps.map { if (it.kind == StepKind.POUR) it.copy(dispensedMl = 41.6, measured = true) else it })
        }
        val state = pouring(reducePour(PourPhase.Idle, measured))
        assertEquals(StepVolume(dispensedMl = 41.6, plannedMl = 44.0, measured = true), state.steps[1].volume)
    }

    @Test
    fun `a job without steps keeps the steps already shown`() {
        val running = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1)))
        val stepless = pouring(reducePour(running, job(JobStatus.RUNNING, stepIndex = 1).copy(steps = emptyList())))
        assertEquals(running.steps, stepless.steps)
    }

    @Test
    fun `a refused stop stays on screen while the job keeps running`() {
        val refused = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1)))
            .copy(stopFailed = MachineError.Unreachable)

        val next = pouring(reducePour(refused, job(JobStatus.RUNNING, stepIndex = 1, progress = 0.6f)))

        assertEquals(MachineError.Unreachable, next.stopFailed)
    }

    @Test
    fun `a refused stop is dropped once the machine confirms a stop`() {
        val refused = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING, stepIndex = 1)))
            .copy(stopFailed = MachineError.Unreachable)

        val stopping = pouring(reducePour(refused, job(JobStatus.ABORTING, stepIndex = 1)))

        assertNull(stopping.stopFailed)
        assertTrue(stopping.aborting)
    }

    @Test
    fun `a refused stop belongs to its own job`() {
        val refused = pouring(reducePour(PourPhase.Idle, job(JobStatus.RUNNING)))
            .copy(stopFailed = MachineError.Unreachable)

        val other = pouring(reducePour(refused, job(JobStatus.RUNNING).copy(jobId = "job-2")))

        assertNull(other.stopFailed)
    }
}
