package com.example.smartbartender.data.hardware

import android.util.Log
import com.example.smartbartender.data.hardware.dto.ErrorResponseDto
import com.example.smartbartender.data.hardware.dto.JogRequestDto
import com.example.smartbartender.data.hardware.dto.LedRequestDto
import com.example.smartbartender.data.hardware.dto.SlotAssignmentDto
import com.example.smartbartender.data.hardware.dto.SlotsRequestDto
import com.example.smartbartender.data.hardware.dto.toDto
import com.example.smartbartender.data.local.BartenderPreferences
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

private const val TAG = "BartenderMachine"

/** 1s, 2s, 4s, 8s, then every 15s. Long enough to stop hammering, short enough to feel live. */
private val RECONNECT_BACKOFF = listOf(1, 2, 4, 8, 15)

/**
 * Talks to the Pi over REST, and listens to it over one WebSocket.
 *
 * Lives for the life of the application, not a screen: leaving the detail screen, rotating,
 * or backgrounding the app must not drop the pour feed. Combined with the machine owning the
 * pour itself, that is what makes a foreground service unnecessary — the drink finishes
 * whatever the app is doing, and the app re-attaches when it comes back.
 */
class HttpBartenderMachine(
    private val api: BartenderApi,
    private val client: OkHttpClient,
    private val preferences: BartenderPreferences,
    private val scope: CoroutineScope,
) : BartenderMachine {

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disabled)
    override val connection = _connection.asStateFlow()

    private val _currentJob = MutableStateFlow<PourJob?>(null)
    override val currentJob = _currentJob.asStateFlow()

    @Volatile
    private var address: MachineAddress = MachineAddress("", MachineAddress.DEFAULT_PORT, enabled = false)

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        preferences.machineAddress
            .onEach { address = it }
            .flatMapLatest { target ->
                if (!target.isConfigured) {
                    _connection.value = ConnectionState.Disabled
                    _currentJob.value = null
                    emptyFlow()
                } else {
                    client.machineEvents(target.wsUrl, MachineNetworkModule.json)
                        .onStart {
                            _connection.value = ConnectionState.Connecting
                            Log.i(TAG, "connecting to ${target.httpBase}")
                        }
                        .retryWhen { cause, attempt ->
                            _connection.value = ConnectionState.Failed(cause.friendlyMessage())
                            val wait = RECONNECT_BACKOFF[
                                minOf(attempt.toInt(), RECONNECT_BACKOFF.lastIndex),
                            ]
                            delay(wait.seconds)
                            true
                        }
                }
            }
            .onEach(::reduce)
            .catch { Log.e(TAG, "event stream gave up", it) }
            .launchIn(scope)
    }

    /**
     * Folds a pushed event into local state.
     *
     * Every event carries a whole object rather than a delta, so this is always a replace —
     * a frame dropped by a slow client is corrected by the next one.
     */
    private fun reduce(event: MachineEvent) {
        when (event) {
            is MachineEvent.Snapshot -> {
                val firstConnect = _connection.value !is ConnectionState.Connected
                _connection.value = ConnectionState.Connected(event.snapshot)
                _currentJob.value = event.snapshot.currentJob ?: _currentJob.value
                // A reconnect means the machine may have rebooted, or been changed by another
                // phone. Push what this app believes so the two converge.
                if (firstConnect) scope.launch { resync() }
            }

            is MachineEvent.Pour -> _currentJob.value = event.job

            is MachineEvent.Led -> updateSnapshot { it.copy(led = event.led) }

            is MachineEvent.Slots -> updateSnapshot { it.copy(slots = event.slots) }

            is MachineEvent.Fault -> updateSnapshot { it.copy(fault = event.fault) }

            MachineEvent.Heartbeat -> Unit
        }
    }

    private fun updateSnapshot(transform: (MachineSnapshot) -> MachineSnapshot) {
        _connection.update { state ->
            (state as? ConnectionState.Connected)?.let { ConnectionState.Connected(transform(it.snapshot)) }
                ?: state
        }
    }

    private suspend fun resync() {
        runCatching {
            pushSlots(preferences.slots.first())
            setLed(preferences.ledShowEnabled.first(), cycleMillis = LED_CYCLE_MILLIS)
        }.onFailure { Log.w(TAG, "could not resync the machine: ${it.message}") }
    }

    // ------------------------------------------------------------------ commands

    override suspend fun testConnection(host: String, port: Int): Result<MachineSnapshot> {
        val base = MachineAddress(host.trim(), port, enabled = true)
        if (host.isBlank()) return Result.failure(IOException("Enter the machine's address first"))
        return runCatching {
            api.health("${base.httpBase}/healthz")
            api.status("${base.httpBase}/api/v1/status").toDomain()
        }.mapError()
    }

    override suspend fun pushSlots(slots: List<String?>): Result<Unit> = command { base ->
        api.putSlots(
            url = "$base/api/v1/slots",
            body = SlotsRequestDto(
                slots = slots.mapIndexed { index, bottleId ->
                    SlotAssignmentDto(pump = index + 1, bottleId = bottleId)
                },
            ),
        )
    }

    override suspend fun startPour(request: PourRequest): Result<PourJob> {
        val base = address.takeIf { it.isConfigured }?.httpBase
            ?: return Result.failure(IOException(OFFLINE_MESSAGE))
        return runCatching {
            api.startPour(
                url = "$base/api/v1/pours",
                idempotencyKey = request.jobId,
                body = request.toDto(),
            ).toDomain().also { _currentJob.value = it }
        }.mapError()
    }

    override suspend fun abort(jobId: String): Result<Unit> = command { base ->
        api.abort("$base/api/v1/pours/$jobId/abort")
    }

    override suspend fun setLed(enabled: Boolean, cycleMillis: Int): Result<Unit> = command { base ->
        api.putLed(
            url = "$base/api/v1/led",
            body = LedRequestDto(
                enabled = enabled,
                mode = if (enabled) "spectrum" else "off",
                cycleMillis = cycleMillis,
            ),
        )
    }

    /** Runs one pump for [seconds] so its output can be measured. See `pi/README.md`. */
    suspend fun jog(pump: Int, seconds: Double): Result<Unit> = command { base ->
        api.jog("$base/api/v1/pumps/$pump/jog", JogRequestDto(seconds))
    }

    private suspend inline fun command(block: (base: String) -> Unit): Result<Unit> {
        val base = address.takeIf { it.isConfigured }?.httpBase
            ?: return Result.failure(IOException(OFFLINE_MESSAGE))
        return runCatching { block(base) }.mapError()
    }

    companion object {
        /** Matches `rememberLedState`'s default, so the phone and the strip stay in phase. */
        const val LED_CYCLE_MILLIS = 7000

        private const val OFFLINE_MESSAGE = "The machine is not connected"
    }
}

/**
 * Turns whatever went wrong into something worth showing a person.
 *
 * The machine answers a refusal with `{"error": {"code", "message"}}` (see `pi/API.md`), and
 * those messages are already written for a human — "Tequila (tequila) is not loaded" beats
 * "HTTP 422".
 */
private fun <T> Result<T>.mapError(): Result<T> =
    recoverCatching { throwable -> throw IOException(throwable.friendlyMessage(), throwable) }

private fun Throwable.friendlyMessage(): String = when (this) {
    is MachineSocketException -> message ?: "Connection lost"
    is HttpException -> machineMessage() ?: "Machine refused that (HTTP ${code()})"
    is SocketTimeoutException -> "The machine did not answer"
    is IOException -> "Machine unreachable"
    else -> message ?: "Machine error"
}

private fun HttpException.machineMessage(): String? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    MachineNetworkModule.json.decodeFromString<ErrorResponseDto>(body).error.message.takeIf { it.isNotBlank() }
}.getOrNull()
