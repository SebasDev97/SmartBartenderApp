package com.example.smartbartender.ui.screens.detail

import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourPlan
import com.example.smartbartender.domain.model.StepKind

/**
 * Turns a [PourJob] pushed by the machine into the screen state.
 *
 * A pure function on purpose: the pour is the one piece of this app where being wrong has
 * physical consequences, and this way the whole thing can be driven through a scripted
 * sequence of jobs in a JVM test. See `PourReducerTest`.
 */
fun reducePour(previous: PreparationState, job: PourJob): PreparationState {
    val steps = job.steps.map { step ->
        PourStep(
            label = step.label,
            detail = when {
                step.kind == StepKind.POUR && step.ml != null ->
                    "${step.dispensedMl?.toInt() ?: 0} / ${step.ml.toInt()} ml"

                else -> step.detail
            },
            isManual = step.kind.isManual,
        )
    }

    return previous.copy(
        isRunning = job.status.isLive,
        isFinished = job.status == JobStatus.FINISHED,
        isAborting = job.status == JobStatus.ABORTING,
        steps = steps.ifEmpty { previous.steps },
        currentStepIndex = job.currentStepIndex.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
        jobId = job.jobId,
        remoteProgress = job.progress,
        errorMessage = when (job.status) {
            JobStatus.FAILED -> job.error?.message ?: "The machine stopped unexpectedly"
            // Aborting was the user's own doing — not something to apologise for.
            else -> null
        },
    )
}

/**
 * The state to show the instant the button is tapped, before the machine has answered.
 *
 * Without this the overlay would sit blank for one round trip. The steps here are a
 * prediction; the machine's first `pour` event replaces them wholesale.
 */
fun optimisticPreparation(plan: PourPlan, jobId: String, glass: String?): PreparationState {
    val steps = buildList {
        add(PourStep("Positioning glass", glass ?: "Cocktail glass"))
        plan.request.items.forEach { item ->
            add(PourStep("Pouring ${item.ingredientName}", "${item.ml.toInt()} ml"))
        }
        if (plan.request.items.isNotEmpty()) add(PourStep("Mixing", "Stirring the blend"))
        plan.manualSteps.forEach { add(PourStep(it, "Add this yourself", isManual = true)) }
        add(PourStep("Finishing touch", "Garnish and serve"))
    }
    return PreparationState(
        isRunning = true,
        steps = steps,
        currentStepIndex = 0,
        jobId = jobId,
        remoteProgress = null,
    )
}
