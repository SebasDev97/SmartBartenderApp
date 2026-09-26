package com.example.smartbartender.ui.screens.detail

import com.example.smartbartender.domain.model.MachineError

/**
 * Where the pour on the detail screen stands. One sealed type rather than a handful of
 * flags, so "running and finished at once" cannot be expressed, and every screen that
 * renders a pour has to say what it shows for each phase.
 */
sealed interface PourPhase {

    /** Nothing poured, or the last pour was dismissed or stopped by the user. */
    data object Idle : PourPhase

    /** Queued, pouring or stopping on the machine. */
    data class Pouring(
        val jobId: String,
        val steps: List<PourStep>,
        val currentStepIndex: Int,
        /** Volume-weighted progress from the machine. Null until it has answered. */
        val machineProgress: Float?,
        /** The machine is waiting for a glass under the nozzle before anything pours. */
        val waitingForGlass: Boolean,
        /** The user asked to stop, and the pumps have not confirmed it yet. */
        val aborting: Boolean = false,
        /** Why the last Stop did not reach the machine. The pumps may still be running. */
        val stopFailed: MachineError? = null,
    ) : PourPhase {
        val currentStep: PourStep? get() = steps.getOrNull(currentStepIndex)

        val progress: Float
            get() = machineProgress ?: if (steps.isEmpty()) 0f else (currentStepIndex + 1).toFloat() / steps.size

        companion object {
            /**
             * The state to show the instant the button is tapped, before the machine has
             * answered. Every pour starts by looking for a glass; the machine's first event
             * confirms it and brings the real steps.
             */
            fun starting(jobId: String) = Pouring(
                jobId = jobId,
                steps = emptyList(),
                currentStepIndex = 0,
                machineProgress = null,
                waitingForGlass = true,
            )
        }
    }

    data class Finished(val jobId: String) : PourPhase

    data class Failed(val failure: PourFailure) : PourPhase
}

/** Why a pour did not happen, or did not finish. */
sealed interface PourFailure {
    /** Nothing in the recipe matches a loaded bottle. */
    data object NothingPourable : PourFailure

    /** The machine turned the request down, or could not be reached. */
    data class Refused(val error: MachineError) : PourFailure

    /** The machine started and then reported the job failed. [message] is its own words, if any. */
    data class Faulted(val message: String?) : PourFailure
}

/** One stage of the pour, as reported by the machine. */
data class PourStep(
    val label: String,
    val detail: String? = null,
    /** Something the human does — ice, a garnish, a salted rim. No pump can serve it. */
    val isManual: Boolean = false,
    /** Set on pump steps: how much has gone in so far. */
    val volume: StepVolume? = null,
)

/** A pump step's progress. [measured] once the glass sensor, not the pump clock, says so. */
data class StepVolume(val dispensedMl: Double, val plannedMl: Double, val measured: Boolean)
