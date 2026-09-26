package com.example.smartbartender.data.hardware

import android.util.Log
import com.example.smartbartender.data.hardware.dto.CalibrationRequestDto
import com.example.smartbartender.data.hardware.dto.CleaningRequestDto
import com.example.smartbartender.data.hardware.dto.ErrorResponseDto
import com.example.smartbartender.data.hardware.dto.JogRequestDto
import com.example.smartbartender.data.hardware.dto.LedRequestDto
import com.example.smartbartender.data.hardware.dto.SlotAssignmentDto
import com.example.smartbartender.data.hardware.dto.SlotsRequestDto
import com.example.smartbartender.data.hardware.dto.toDto
import com.example.smartbartender.data.hardware.dto.wireName
import com.example.smartbartender.data.local.MachineSettingsStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.LedMode
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineAddress
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineException
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
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
    private val rack: RackStore,
    private val settings: MachineSettingsStore,
    private val scope: CoroutineScope,
) : BartenderMachine {

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disabled)
    override val connection = _connection.asStateFlow()

    private val _currentJob = MutableStateFlow<PourJob?>(null)
    override val currentJob = _currentJob.asStateFlow()

    @Volatile
    private var address: MachineAddress = MachineAddress("", MachineAddress.DEFAULT_PORT, enabled = false)

    /** Follows the configured address: connects, reconnects with back-off, and folds every event in. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        settings.machineAddress
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
                            _connection.value = ConnectionState.Failed(cause.asMachineError())
                            val wait = RECONNECT_BACKOFF[minOf(attempt.toInt(), RECONNECT_BACKOFF.lastIndex)]
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
                val previous = _connection.value.snapshotOrNull
                val firstConnect = previous == null
                _connection.value = ConnectionState.Connected(event.snapshot.keepingEndedRuns(previous))
                _currentJob.value = event.snapshot.currentJob ?: _currentJob.value
                // A reconnect means the machine may have rebooted, or been changed by another
                // phone. Push what this app believes so the two converge.
                if (firstConnect) scope.launch { resync() }
            }

            is MachineEvent.Pour -> _currentJob.value = event.job

            is MachineEvent.Led -> updateSnapshot { it.copy(led = event.led) }

            is MachineEvent.Slots -> updateSnapshot { it.copy(slots = event.slots) }

            is MachineEvent.Fault -> updateSnapshot { it.copy(fault = event.fault) }

            is MachineEvent.Calibration -> updateSnapshot { it.copy(calibration = event.run) }

            is MachineEvent.Cleaning -> updateSnapshot { it.copy(cleaning = event.run) }

            MachineEvent.Heartbeat -> Unit
        }
    }

    /**
     * The machine's snapshot carries a calibration or cleaning run only while it runs, and the
     * last `calibration`/`cleaning` event (with the results, or why it failed) is followed by a
     * snapshot without one. Keep those ended runs, so their screens can still show them.
     */
    private fun MachineSnapshot.keepingEndedRuns(previous: MachineSnapshot?): MachineSnapshot = copy(
        calibration = calibration
            ?: previous?.calibration?.takeIf { it.status.isTerminal },
        cleaning = cleaning
            ?: previous?.cleaning?.takeIf { it.status.isTerminal },
    )

    private fun updateSnapshot(transform: (MachineSnapshot) -> MachineSnapshot) {
        _connection.update { state ->
            (state as? ConnectionState.Connected)?.let { ConnectionState.Connected(transform(it.snapshot)) }
                ?: state
        }
    }

    private suspend fun resync() {
        runCatching {
            pushSlots(rack.slots.first())
            setLed(settings.ledShowEnabled.first())
        }.onFailure { Log.w(TAG, "could not resync the machine: ${it.message}") }
    }

    // ------------------------------------------------------------------ commands

    override suspend fun testConnection(host: String, port: Int): Result<MachineSnapshot> {
        if (host.isBlank()) return Result.failure(MachineException(MachineError.MissingAddress))
        val base = MachineAddress(host.trim(), port, enabled = true).httpBase
        return runMachineCall {
            api.health("$base/healthz")
            api.status("$base/api/v1/status").toDomain()
        }
    }

    override suspend fun pushSlots(slots: List<String?>): Result<Unit> = call { base ->
        api.putSlots(
            url = "$base/api/v1/slots",
            body = SlotsRequestDto(
                slots = slots.mapIndexed { index, bottleId ->
                    SlotAssignmentDto(pump = index + 1, bottleId = bottleId)
                },
            ),
        )
    }

    override suspend fun startPour(request: PourRequest): Result<PourJob> = call { base ->
        api.startPour(
            url = "$base/api/v1/pours",
            idempotencyKey = request.jobId,
            body = request.toDto(),
        ).toDomain().also { _currentJob.value = it }
    }

    override suspend fun abort(jobId: String): Result<Unit> = call { base ->
        api.abort("$base/api/v1/pours/$jobId/abort")
    }

    override suspend fun fetchJob(jobId: String): Result<PourJob> = call { base ->
        api.pour("$base/api/v1/pours/$jobId").toDomain()
    }

    override suspend fun setLed(enabled: Boolean): Result<Unit> = call { base ->
        api.putLed(
            url = "$base/api/v1/led",
            body = LedRequestDto(
                enabled = enabled,
                mode = (if (enabled) LedMode.SPECTRUM else LedMode.OFF).wireName(),
                cycleMillis = LedShow.CYCLE_MILLIS,
            ),
        )
    }

    override suspend fun jog(pump: Int, seconds: Double): Result<Unit> = call { base ->
        api.jog("$base/api/v1/pumps/$pump/jog", JogRequestDto(seconds))
    }

    override suspend fun readSensor(): Result<SensorReading> = call { base ->
        api.sensor("$base/api/v1/sensor").toDomain()
    }

    override suspend fun measureReference(): Result<SensorReading> = call { base ->
        api.measureReference("$base/api/v1/sensor/reference").toDomain()
    }

    override suspend fun startCalibration(pumps: List<Int>?, seconds: Double?): Result<CalibrationRun> =
        call { base ->
            api.startCalibration("$base/api/v1/calibration", CalibrationRequestDto(pumps, seconds))
                .toDomain()
                .also { run -> updateSnapshot { it.copy(calibration = run) } }
        }

    override suspend fun abortCalibration(): Result<Unit> = call { base ->
        api.abortCalibration("$base/api/v1/calibration/abort")
    }

    override suspend fun startCleaning(pumps: List<Int>?, seconds: Double?, rounds: Int?): Result<CleaningRun> =
        call { base ->
            api.startCleaning("$base/api/v1/cleaning", CleaningRequestDto(pumps, seconds, rounds))
                .toDomain()
                .also { run -> updateSnapshot { it.copy(cleaning = run) } }
        }

    override suspend fun abortCleaning(): Result<Unit> = call { base ->
        api.abortCleaning("$base/api/v1/cleaning/abort")
    }

    /** Runs [block] against the configured machine's base URL, or fails with [MachineError.NotConfigured]. */
    private inline fun <T> call(block: (base: String) -> T): Result<T> {
        val base = address.takeIf { it.isConfigured }?.httpBase
            ?: return Result.failure(MachineException(MachineError.NotConfigured))
        return runMachineCall { block(base) }
    }
}

/** Runs [block], turning whatever it throws into a [MachineException] with a typed reason. */
private inline fun <T> runMachineCall(block: () -> T): Result<T> =
    runCatching(block).recoverCatching { throw MachineException(it.asMachineError(), it) }

private fun Throwable.asMachineError(): MachineError = when (this) {
    is MachineException -> error
    is MachineSocketException -> MachineError.ConnectionLost(message)
    is HttpException -> refusal()
    is SocketTimeoutException -> MachineError.Timeout
    is IOException -> MachineError.Unreachable
    else -> MachineError.Unexpected(message)
}

/**
 * The machine answers a refusal with `{"error": {"code", "message"}}` (see `pi/API.md`); keep
 * both so the UI can show the Pi's own sentence and callers can branch on the code.
 */
private fun HttpException.refusal(): MachineError.Refused {
    val body = runCatching {
        MachineNetworkModule.json.decodeFromString<ErrorResponseDto>(response()?.errorBody()?.string().orEmpty()).error
    }.getOrNull()
    return MachineError.Refused(
        code = body?.code?.takeIf { it.isNotBlank() },
        message = body?.message?.takeIf { it.isNotBlank() },
        httpStatus = code(),
    )
}
