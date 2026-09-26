package com.example.smartbartender.ui.screens.detail

import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.StepKind

/**
 * Turns a [PourJob] pushed by the machine into the screen's [PourPhase].
 *
 * A pure function on purpose: the pour is the one piece of this app where being wrong has
 * physical consequences, and this way the whole thing can be driven through a scripted
 * sequence of jobs in a JVM test. See `PourReducerTest`.
 */
fun reducePour(previous: PourPhase, job: PourJob): PourPhase = when (job.status) {
    JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.ABORTING -> {
        val steps = job.steps.map(::toPourStep).ifEmpty { (previous as? PourPhase.Pouring)?.steps.orEmpty() }
        PourPhase.Pouring(
            jobId = job.jobId,
            steps = steps,
            currentStepIndex = job.currentStepIndex.coerceIn(0, steps.lastIndex.coerceAtLeast(0)),
            machineProgress = job.progress,
            waitingForGlass = job.waitingForGlass,
            aborting = job.status == JobStatus.ABORTING,
        )
    }

    JobStatus.FINISHED -> PourPhase.Finished(job.jobId)

    JobStatus.FAILED -> PourPhase.Failed(PourFailure.Faulted(job.error?.message))

    // Stopping was the user's own doing — not something to apologise for.
    JobStatus.ABORTED -> PourPhase.Idle

    // A status newer firmware added: keep showing what we had rather than guess.
    JobStatus.UNKNOWN -> previous
}

private fun toPourStep(step: JobStep) = PourStep(
    label = step.label,
    detail = step.detail,
    isManual = step.kind.isManual,
    volume = step.ml
        ?.takeIf { step.kind == StepKind.POUR }
        ?.let { planned -> StepVolume(step.dispensedMl ?: 0.0, planned, step.measured) },
)
