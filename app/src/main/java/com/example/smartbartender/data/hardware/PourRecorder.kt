package com.example.smartbartender.data.hardware

import com.example.smartbartender.data.local.ActiveJobStore
import com.example.smartbartender.data.local.PourHistoryStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.domain.model.MachineSlot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Writes every pour this phone started into the history behind the Stats tab.
 *
 * Application-scoped for the same reason as [HttpBartenderMachine]: a drink finishes whether
 * or not the detail screen is still open, and it should still be counted.
 *
 * "This phone started it" means its id is the persisted `activeJobId` — the machine pushes
 * every phone's pours to every phone, and someone else's drink is not your statistic.
 */
class PourRecorder(
    private val machine: BartenderMachine,
    private val activeJob: ActiveJobStore,
    private val history: PourHistoryStore,
    private val rack: RackStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun start() {
        machine.currentJob
            .filterNotNull()
            .filter { it.status.isTerminal }
            .onEach { job -> if (job.jobId == activeJob.activeJobId.first()) record(job) }
            .launchIn(scope)

        // The app may have been killed mid-pour and missed the final frame. The machine
        // remembers its last jobs, so ask it how that one ended once the link is back.
        machine.connection
            .map { it.isConnected }
            .distinctUntilChanged()
            .filter { connected -> connected }
            .onEach { catchUp() }
            .launchIn(scope)
    }

    private suspend fun catchUp() {
        val jobId = activeJob.activeJobId.first() ?: return
        if (history.pourHistory.first().any { it.jobId == jobId }) return
        machine.fetchJob(jobId).onSuccess { job -> if (job.status.isTerminal) record(job) }
    }

    private suspend fun record(job: PourJob) {
        val slots = machine.connection.value.snapshotOrNull?.slots
            ?.takeIf { it.isNotEmpty() }
            ?.inPumpOrder()
            ?: rack.slots.first()
        PourRecord.from(job, slots, clock())?.let { history.recordPour(it) }
    }
}

/** The machine's slot table as a positional list: index 0 is pump 1. */
private fun List<MachineSlot>.inPumpOrder(): List<String?> =
    (1..maxOf { it.pump }).map { pump -> firstOrNull { it.pump == pump }?.bottleId }
