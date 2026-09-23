package com.example.smartbartender.data.hardware

import android.util.Log
import com.example.smartbartender.data.hardware.dto.EventEnvelopeDto
import com.example.smartbartender.data.hardware.dto.FaultDto
import com.example.smartbartender.data.hardware.dto.LedDto
import com.example.smartbartender.data.hardware.dto.MachineStatusDto
import com.example.smartbartender.data.hardware.dto.PourJobDto
import com.example.smartbartender.data.hardware.dto.SlotsResponseDto
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineFault
import com.example.smartbartender.domain.model.MachineSlot
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "BartenderEvents"

/** One frame from the machine's event socket. Each one carries a whole object, never a delta. */
sealed interface MachineEvent {
    data class Snapshot(val snapshot: MachineSnapshot) : MachineEvent
    data class Pour(val job: PourJob) : MachineEvent
    data class Led(val led: LedShow) : MachineEvent
    data class Slots(val slots: List<MachineSlot>) : MachineEvent
    data class Fault(val fault: MachineFault) : MachineEvent
    data object Heartbeat : MachineEvent
}

/** Thrown when the socket closes or fails, so the caller's retry operator can back off. */
class MachineSocketException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The machine's live state as a flow.
 *
 * The socket is one-way by design — commands go over REST — so this listener only ever reads.
 * The flow fails (rather than completing) on a dropped connection, which is what lets the
 * caller distinguish "gone" from "finished" and reconnect.
 */
fun OkHttpClient.machineEvents(wsUrl: String, json: Json): Flow<MachineEvent> = callbackFlow {
    val socket = newWebSocket(
        Request.Builder().url(wsUrl).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text, json)?.let { trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(MachineSocketException(t.message ?: "connection lost", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(MachineSocketException("socket closed ($code ${reason.ifBlank { "no reason" }})"))
            }
        },
    )
    awaitClose { socket.cancel() }
}

private fun parseEvent(text: String, json: Json): MachineEvent? = runCatching {
    val envelope = json.decodeFromString<EventEnvelopeDto>(text)
    when (envelope.type) {
        "snapshot" ->
            MachineEvent.Snapshot(json.decodeFromJsonElement(MachineStatusDto.serializer(), envelope.payload).toDomain())

        "pour" ->
            MachineEvent.Pour(json.decodeFromJsonElement(PourJobDto.serializer(), envelope.payload).toDomain())

        "led" ->
            MachineEvent.Led(json.decodeFromJsonElement(LedDto.serializer(), envelope.payload).toDomain())

        "slots" ->
            MachineEvent.Slots(
                json.decodeFromJsonElement(SlotsResponseDto.serializer(), envelope.payload)
                    .slots.map { it.toDomain() },
            )

        "fault" ->
            MachineEvent.Fault(json.decodeFromJsonElement(FaultDto.serializer(), envelope.payload).toDomain())

        "heartbeat" -> MachineEvent.Heartbeat

        // A type this build doesn't know about is not an error — newer firmware may add some.
        else -> null
    }
}.onFailure { Log.w(TAG, "could not read a machine event: ${it.message}") }.getOrNull()
