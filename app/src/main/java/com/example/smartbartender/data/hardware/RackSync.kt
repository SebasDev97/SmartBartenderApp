package com.example.smartbartender.data.hardware

import android.util.Log
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.inPumpOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val TAG = "RackSync"

/**
 * Keeps the machine's pump map equal to the phone's rack, whichever screen changed it.
 *
 * Application-scoped like [PourRecorder], so the rule "the machine's pump map follows the
 * phone's rack" holds without any screen having to remember it. It compares the rack with
 * the map the machine reports and pushes only when they differ and the machine can take it:
 * connected, and idle, since a busy machine refuses a new map (`409 MACHINE_BUSY`). A rack
 * changed mid-pour, or while offline, therefore arrives as soon as the machine is idle again,
 * and a reconnect to a rebooted machine is covered the same way.
 */
class RackSync(
    private val rack: RackStore,
    private val machine: BartenderMachine,
    private val scope: CoroutineScope,
) {
    fun start() {
        combine(rack.slots, machine.connection) { slots, connection ->
            val snapshot = connection.snapshotOrNull
            slots.takeIf {
                snapshot != null &&
                    snapshot.state == MachineRunState.IDLE &&
                    snapshot.slots.inPumpOrder() != slots
            }
        }
            // One push per mismatch: a failed push is retried when something changes (the
            // machine goes busy and idle again, or reconnects), never in a tight loop.
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { slots ->
                machine.pushSlots(slots).onFailure { Log.i(TAG, "rack not pushed: ${it.message}") }
            }
            .launchIn(scope)
    }
}
