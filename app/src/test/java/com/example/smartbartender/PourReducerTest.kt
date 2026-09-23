package com.example.smartbartender

import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.MachineFault
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.StepKind
import com.example.smartbartender.ui.screens.detail.PreparationState
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

    @Test
    fun `a running job drives the overlay`() {
        val state = reducePour(PreparationState(), job(JobStatus.RUNNING, stepIndex = 1, progress = 0.4f))

        assertTrue(state.isRunning)
        assertFalse(state.isFinished)
        assertEquals(1, state.currentStepIndex)
        assertEquals("Pouring Tequila", state.currentStep?.label)
    }

    @Test
    fun `the machine's progress wins over the step-count fallback`() {
        val state = reducePour(PreparationState(), job(JobStatus.RUNNING, stepIndex = 0, progress = 0.62f))
        // Step 0 of 4 would be 0.25 by counting; the machine measured 0.62.
        assertEquals(0.62f, state.progress, 0.001f)
    }

    @Test
    fun `a pour step shows how much has actually gone in`() {
        val state = reducePour(PreparationState(), job(JobStatus.RUNNING, stepIndex = 1))
        assertEquals("22 / 44 ml", state.steps[1].detail)
    }

    @Test
    fun `manual steps are marked so the overlay can say who does them`() {
        val state = reducePour(PreparationState(), job(JobStatus.RUNNING))
        assertTrue(state.steps[2].isManual)
        assertFalse(state.steps[1].isManual)
    }

    @Test
    fun `finishing fills the bar and stops the run`() {
        val state = reducePour(PreparationState(), job(JobStatus.FINISHED, stepIndex = 3, progress = 1f))

        assertFalse(state.isRunning)
        assertTrue(state.isFinished)
        assertEquals(1f, state.progress, 0.001f)
        assertNull(state.errorMessage)
    }

    @Test
    fun `aborting is shown while it happens, and is not reported as an error`() {
        val aborting = reducePour(PreparationState(), job(JobStatus.ABORTING, stepIndex = 1))
        assertTrue(aborting.isAborting)
        assertTrue(aborting.isRunning)

        val aborted = reducePour(aborting, job(JobStatus.ABORTED, stepIndex = 1))
        assertFalse(aborted.isRunning)
        assertFalse(aborted.isFinished)
        // The user asked for this. Nothing to apologise for.
        assertNull(aborted.errorMessage)
    }

    @Test
    fun `a failure surfaces the machine's own words`() {
        val fault = MachineFault("PUMP_FAULT", "Pump 2 did not reach target", recoverable = false)
        val state = reducePour(PreparationState(), job(JobStatus.FAILED, error = fault))

        assertFalse(state.isRunning)
        assertEquals("Pump 2 did not reach target", state.errorMessage)
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
        val end = sequence.fold(PreparationState()) { state, next -> reducePour(state, next) }

        assertTrue(end.isFinished)
        assertEquals("job-1", end.jobId)
        assertEquals(1f, end.progress, 0.001f)
    }

    @Test
    fun `an out-of-range step index from the machine cannot crash the screen`() {
        val state = reducePour(PreparationState(), job(JobStatus.RUNNING, stepIndex = 99))
        assertEquals(3, state.currentStepIndex)
    }
}
