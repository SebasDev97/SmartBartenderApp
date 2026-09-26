package com.example.smartbartender.data.hardware

import android.util.Log
import com.example.smartbartender.data.local.RackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val TAG = "RackSync"

/**
 * Tells the machine about every rack change, whichever screen made it.
 *
 * Application-scoped like [PourRecorder], so the rule "the machine's pump map follows the
 * phone's rack" holds without any screen having to remember it. A push that fails while the
 * machine is offline is not retried here: [HttpBartenderMachine] pushes the rack again on
 * every reconnect.
 */
class RackSync(
    private val rack: RackStore,
    private val machine: BartenderMachine,
    private val scope: CoroutineScope,
) {
    fun start() {
        rack.slots
            .onEach { slots ->
                machine.pushSlots(slots).onFailure { Log.i(TAG, "rack not pushed: ${it.message}") }
            }
            .launchIn(scope)
    }
}
