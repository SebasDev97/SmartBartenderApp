package com.example.smartbartender.domain.model

/** How a pour this phone started ended up. Only terminal states are ever recorded. */
enum class PourOutcome { FINISHED, ABORTED, FAILED }

/**
 * One pour, as remembered for the Stats tab.
 *
 * [mlByBottle] is what the pumps actually dispensed, not what the plan asked for, so a pour
 * stopped halfway counts the half that reached the glass. It says nothing about what is left
 * in a bottle — the machine has no way to know that.
 */
data class PourRecord(
    val jobId: String,
    val drinkId: String?,
    val drinkName: String,
    val outcome: PourOutcome,
    val finishedAtMs: Long,
    val mlByBottle: Map<String, Double>,
) {
    val totalMl: Double get() = mlByBottle.values.sum()

    val isFinished: Boolean get() = outcome == PourOutcome.FINISHED

    companion object {
        /**
         * Turns a terminal [job] into a record, or null if it has not ended.
         *
         * The job names pumps, not bottles; [slots] (index 0 is pump 1) resolves them. That is
         * safe because the machine refuses a slot change while a pour runs.
         */
        fun from(job: PourJob, slots: List<String?>, finishedAtMs: Long): PourRecord? {
            val outcome = when (job.status) {
                JobStatus.FINISHED -> PourOutcome.FINISHED
                JobStatus.ABORTED -> PourOutcome.ABORTED
                JobStatus.FAILED -> PourOutcome.FAILED
                else -> return null
            }
            val mlByBottle = buildMap<String, Double> {
                job.steps
                    .filter { it.kind == StepKind.POUR }
                    .forEach { step ->
                        val bottleId = step.pump?.let { slots.getOrNull(it - 1) } ?: return@forEach
                        val ml = step.dispensedMl ?: 0.0
                        if (ml > 0) put(bottleId, (get(bottleId) ?: 0.0) + ml)
                    }
            }
            return PourRecord(
                jobId = job.jobId,
                drinkId = job.drinkId,
                drinkName = job.drinkName,
                outcome = outcome,
                finishedAtMs = job.finishedAtMs ?: finishedAtMs,
                mlByBottle = mlByBottle,
            )
        }
    }
}

/**
 * The pours this phone has started, oldest first.
 *
 * Capped at [MAX_RECORDS] so the file stays small; past that the oldest pours roll off and
 * the lifetime totals become "the last thousand drinks", which is plenty for a home bar.
 */
object PourHistory {

    const val MAX_RECORDS = 1000

    /** Adds [record] at the end. A job already held is a no-op, so replayed frames never double-count. */
    fun append(records: List<PourRecord>, record: PourRecord): List<PourRecord> {
        if (records.any { it.jobId == record.jobId }) return records
        return (records + record).takeLast(MAX_RECORDS)
    }
}
