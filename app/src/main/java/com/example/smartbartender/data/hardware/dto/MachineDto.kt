package com.example.smartbartender.data.hardware.dto

import com.example.smartbartender.domain.model.CalibrationPhase
import com.example.smartbartender.domain.model.CalibrationResult
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CalibrationStatus
import com.example.smartbartender.domain.model.CleaningPhase
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CleaningStatus
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineFault
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSlot
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourItem
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import com.example.smartbartender.domain.model.StepKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The wire format from `pi/API.md`, one class per documented object.
 *
 * Enum-ish fields stay [String] here and are mapped in the `toDomain()` helpers, so a value
 * a future firmware adds degrades to `UNKNOWN` instead of throwing mid-pour.
 */

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val machineId: String = "",
    val firmware: String = "",
)

@Serializable
data class SlotDto(
    val pump: Int,
    val bottleId: String? = null,
    val mlPerSecond: Double = 12.5,
) {
    fun toDomain() = MachineSlot(pump = pump, bottleId = bottleId, mlPerSecond = mlPerSecond)
}

@Serializable
data class SlotAssignmentDto(
    val pump: Int,
    val bottleId: String?,
)

@Serializable
data class SlotsRequestDto(val slots: List<SlotAssignmentDto>)

@Serializable
data class SlotsResponseDto(val slots: List<SlotDto> = emptyList())

@Serializable
data class LedDto(
    val enabled: Boolean = false,
    val mode: String = "off",
    val colorHex: String? = null,
    val brightness: Double = 0.6,
    val cycleMillis: Int = 7000,
) {
    fun toDomain() = LedShow(enabled = enabled, mode = mode, cycleMillis = cycleMillis)
}

@Serializable
data class LedRequestDto(
    val enabled: Boolean,
    val mode: String? = null,
    val cycleMillis: Int? = null,
)

@Serializable
data class FaultDto(
    val code: String = "",
    val message: String = "",
    val recoverable: Boolean = false,
) {
    fun toDomain() = MachineFault(code = code, message = message, recoverable = recoverable)
}

@Serializable
data class PourStepDto(
    val index: Int = 0,
    val kind: String = "",
    val label: String = "",
    val detail: String? = null,
    val pump: Int? = null,
    val ml: Double? = null,
    val dispensedMl: Double? = null,
    val measured: Boolean = false,
) {
    fun toDomain() = JobStep(
        index = index,
        kind = when (kind) {
            "glass" -> StepKind.GLASS
            "pour" -> StepKind.POUR
            "mix" -> StepKind.MIX
            "manual" -> StepKind.MANUAL
            "finish" -> StepKind.FINISH
            else -> StepKind.UNKNOWN
        },
        label = label,
        detail = detail,
        pump = pump,
        ml = ml,
        dispensedMl = dispensedMl,
        measured = measured,
    )
}

@Serializable
data class PourJobDto(
    val jobId: String = "",
    val drinkId: String? = null,
    val drinkName: String = "",
    val status: String = "",
    val steps: List<PourStepDto> = emptyList(),
    val currentStepIndex: Int = 0,
    val progress: Double = 0.0,
    val totalMl: Double = 0.0,
    val dispensedMl: Double = 0.0,
    val error: FaultDto? = null,
    val finishedAtMs: Long? = null,
    val waitingForGlass: Boolean = false,
) {
    fun toDomain() = PourJob(
        jobId = jobId,
        drinkId = drinkId,
        drinkName = drinkName,
        status = when (status) {
            "queued" -> JobStatus.QUEUED
            "running" -> JobStatus.RUNNING
            "aborting" -> JobStatus.ABORTING
            "aborted" -> JobStatus.ABORTED
            "finished" -> JobStatus.FINISHED
            "failed" -> JobStatus.FAILED
            else -> JobStatus.UNKNOWN
        },
        steps = steps.map(PourStepDto::toDomain),
        currentStepIndex = currentStepIndex,
        progress = progress.toFloat(),
        totalMl = totalMl,
        dispensedMl = dispensedMl,
        error = error?.toDomain(),
        finishedAtMs = finishedAtMs,
        waitingForGlass = waitingForGlass,
    )
}

@Serializable
data class PourItemDto(
    val bottleId: String,
    val ingredientName: String,
    val ml: Double,
)

@Serializable
data class PourRequestDto(
    val jobId: String,
    val drinkId: String? = null,
    val drinkName: String,
    val glass: String? = null,
    val items: List<PourItemDto>,
    val manualSteps: List<String> = emptyList(),
)

@Serializable
data class MachineStatusDto(
    val machineId: String = "",
    val name: String = "",
    val firmware: String = "",
    val backend: String = "simulated",
    val state: String = "",
    val pumpCount: Int = 0,
    val maxPourMl: Double = 250.0,
    val slots: List<SlotDto> = emptyList(),
    val led: LedDto = LedDto(),
    val currentJob: PourJobDto? = null,
    val fault: FaultDto? = null,
    val sensor: SensorInfoDto = SensorInfoDto(),
    val calibration: CalibrationRunDto? = null,
    val cleaning: CleaningRunDto? = null,
) {
    fun toDomain() = MachineSnapshot(
        machineId = machineId,
        name = name,
        firmware = firmware,
        backend = backend,
        state = when (state) {
            "idle" -> MachineRunState.IDLE
            "busy" -> MachineRunState.BUSY
            "fault" -> MachineRunState.FAULT
            else -> MachineRunState.UNKNOWN
        },
        pumpCount = pumpCount,
        maxPourMl = maxPourMl,
        slots = slots.map(SlotDto::toDomain),
        led = led.toDomain(),
        currentJob = currentJob?.toDomain(),
        fault = fault?.toDomain(),
        sensorReferenceCm = sensor.referenceCm,
        glassDiameterMm = sensor.glassDiameterMm,
        calibratedAtMs = sensor.calibratedAtMs,
        calibration = calibration?.toDomain(),
        cleaning = cleaning?.toDomain(),
    )
}

@Serializable
data class SensorInfoDto(
    val referenceCm: Double? = null,
    val glassDiameterMm: Double = 58.0,
    val calibratedAtMs: Long? = null,
)

@Serializable
data class SensorReadingDto(
    val distanceCm: Double? = null,
    val glassPresent: Boolean = false,
    val referenceCm: Double? = null,
) {
    fun toDomain() = SensorReading(distanceCm = distanceCm, glassPresent = glassPresent, referenceCm = referenceCm)
}

@Serializable
data class CalibrationResultDto(
    val pump: Int = 0,
    val mlPerSecond: Double = 0.0,
    val volumeMl: Double = 0.0,
    val seconds: Double = 0.0,
) {
    fun toDomain() = CalibrationResult(pump = pump, mlPerSecond = mlPerSecond, volumeMl = volumeMl, seconds = seconds)
}

@Serializable
data class CalibrationRunDto(
    val runId: String = "",
    val status: String = "",
    val phase: String = "",
    val pumps: List<Int> = emptyList(),
    val currentPump: Int? = null,
    val message: String = "",
    val results: List<CalibrationResultDto> = emptyList(),
    val error: FaultDto? = null,
) {
    fun toDomain() = CalibrationRun(
        runId = runId,
        status = when (status) {
            "running" -> CalibrationStatus.RUNNING
            "finished" -> CalibrationStatus.FINISHED
            "failed" -> CalibrationStatus.FAILED
            "aborted" -> CalibrationStatus.ABORTED
            else -> CalibrationStatus.UNKNOWN
        },
        phase = when (phase) {
            "waiting_glass" -> CalibrationPhase.WAITING_GLASS
            "measuring" -> CalibrationPhase.MEASURING
            "pumping" -> CalibrationPhase.PUMPING
            "settling" -> CalibrationPhase.SETTLING
            "done" -> CalibrationPhase.DONE
            else -> CalibrationPhase.UNKNOWN
        },
        pumps = pumps,
        currentPump = currentPump,
        message = message,
        results = results.map(CalibrationResultDto::toDomain),
        error = error?.toDomain(),
    )
}

@Serializable
data class CalibrationRequestDto(
    val pumps: List<Int>? = null,
    val seconds: Double? = null,
)

@Serializable
data class CleaningRunDto(
    val runId: String = "",
    val status: String = "",
    val phase: String = "",
    val pumps: List<Int> = emptyList(),
    val rounds: Int = 1,
    val seconds: Double = 0.0,
    val currentRound: Int? = null,
    val currentPump: Int? = null,
    val progress: Float = 0f,
    val message: String = "",
    val error: FaultDto? = null,
) {
    fun toDomain() = CleaningRun(
        runId = runId,
        status = when (status) {
            "running" -> CleaningStatus.RUNNING
            "finished" -> CleaningStatus.FINISHED
            "failed" -> CleaningStatus.FAILED
            "aborted" -> CleaningStatus.ABORTED
            else -> CleaningStatus.UNKNOWN
        },
        phase = when (phase) {
            "pumping" -> CleaningPhase.PUMPING
            "pausing" -> CleaningPhase.PAUSING
            "done" -> CleaningPhase.DONE
            else -> CleaningPhase.UNKNOWN
        },
        pumps = pumps,
        rounds = rounds,
        seconds = seconds,
        currentRound = currentRound,
        currentPump = currentPump,
        progress = progress,
        message = message,
        error = error?.toDomain(),
    )
}

@Serializable
data class CleaningRequestDto(
    val pumps: List<Int>? = null,
    val seconds: Double? = null,
    val rounds: Int? = null,
)

@Serializable
data class JogRequestDto(val seconds: Double)

@Serializable
data class JogResponseDto(val pump: Int = 0, val seconds: Double = 0.0)

@Serializable
data class ApiErrorDto(val code: String = "", val message: String = "")

@Serializable
data class ErrorResponseDto(
    val error: ApiErrorDto = ApiErrorDto(),
    val currentJob: PourJobDto? = null,
)

/**
 * One WebSocket frame. `data` stays a [JsonObject] so an unrecognised `type` costs nothing —
 * the body is only decoded once we know which shape it is.
 */
@Serializable
data class EventEnvelopeDto(
    val type: String,
    val seq: Long = 0,
    val ts: Long = 0,
    @SerialName("data") val payload: JsonObject = JsonObject(emptyMap()),
)

fun PourRequest.toDto() = PourRequestDto(
    jobId = jobId,
    drinkId = drinkId,
    drinkName = drinkName,
    glass = glass,
    items = items.map { it.toDto() },
    manualSteps = manualSteps,
)

fun PourItem.toDto() = PourItemDto(bottleId, ingredientName, ml)
