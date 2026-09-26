package com.example.smartbartender.domain.model

/**
 * The machine as the app understands it. Mirrors the objects in `pi/API.md`, minus the
 * fields the app has no use for.
 *
 * The Pi is the source of truth while a pour runs: the app posts a plan once and then only
 * renders what arrives over the WebSocket. Nothing here is ever invented locally.
 */
data class MachineSnapshot(
    val machineId: String,
    val name: String,
    val firmware: String,
    val backend: MachineBackend,
    val state: MachineRunState,
    val pumpCount: Int,
    val maxPourMl: Double,
    val slots: List<MachineSlot>,
    val led: LedShow,
    val currentJob: PourJob?,
    val fault: MachineFault?,
    /** Sensor to the empty tray. Null until measured — the machine refuses to pour until then. */
    val sensorReferenceCm: Double? = null,
    val glassDiameterMm: Double = DEFAULT_GLASS_DIAMETER_MM,
    val calibratedAtMs: Long? = null,
    /** The calibration run in progress, if any. The machine is BUSY meanwhile. */
    val calibration: CalibrationRun? = null,
    /** The cleaning run in progress, if any. The machine is BUSY meanwhile. */
    val cleaning: CleaningRun? = null,
) {
    val isSimulated: Boolean get() = backend == MachineBackend.SIMULATED

    /**
     * Busy with something other than the caller's own run: a pour, or a calibration while
     * the cleaning screen asks, say. [ownRunActive] is whether the caller's run is the reason.
     */
    fun busyWithOtherWork(ownRunActive: Boolean): Boolean = state == MachineRunState.BUSY && !ownRunActive

    companion object {
        /** The glass the Pi assumes until it reports its own. */
        const val DEFAULT_GLASS_DIAMETER_MM = 58.0
    }
}

/** What drives the pumps: `pi/API.md` lists `simulated` and `arduino`. */
enum class MachineBackend { SIMULATED, ARDUINO, UNKNOWN }

enum class MachineRunState { IDLE, BUSY, FAULT, UNKNOWN }

/** One physical pump. [pump] is 1-based, matching the "SLOT 1" tiles on the bottles screen. */
data class MachineSlot(
    val pump: Int,
    val bottleId: String?,
    val mlPerSecond: Double,
)

data class LedShow(
    val enabled: Boolean,
    val mode: LedMode,
    val cycleMillis: Int,
) {
    companion object {
        /**
         * One lap of the spectrum. The phone's strip preview and the machine's strip both use
         * it, so they stay in phase.
         */
        const val CYCLE_MILLIS = 7000
    }
}

/** `pi/API.md`: the app only ever sends [SPECTRUM] or [OFF]; the Pi sets [POUR] itself. */
enum class LedMode { OFF, SOLID, SPECTRUM, POUR, UNKNOWN }

data class MachineFault(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

enum class JobStatus {
    QUEUED, RUNNING, ABORTING, ABORTED, FINISHED, FAILED, UNKNOWN;

    val isTerminal: Boolean get() = this == ABORTED || this == FINISHED || this == FAILED
    val isLive: Boolean get() = this == QUEUED || this == RUNNING || this == ABORTING
}

enum class StepKind {
    GLASS, POUR, MIX, MANUAL, FINISH, UNKNOWN;

    /** Steps the human has to do — ice, mint, a salted rim. No pump can serve them. */
    val isManual: Boolean get() = this == MANUAL
}

data class JobStep(
    val index: Int,
    val kind: StepKind,
    val label: String,
    val detail: String?,
    val pump: Int?,
    val ml: Double?,
    val dispensedMl: Double?,
    /** True once [dispensedMl] is what the glass sensor measured, not a pump-time estimate. */
    val measured: Boolean = false,
)

data class PourJob(
    val jobId: String,
    val drinkId: String?,
    val drinkName: String,
    val status: JobStatus,
    val steps: List<JobStep>,
    val currentStepIndex: Int,
    /** Volume-weighted, computed by the machine. Truer than counting steps. */
    val progress: Float,
    val totalMl: Double,
    val dispensedMl: Double,
    val error: MachineFault?,
    /** Machine clock, set once the job ends. Null while it is still live. */
    val finishedAtMs: Long? = null,
    /** The glass step is waiting for a glass under the nozzle; nothing pours until then. */
    val waitingForGlass: Boolean = false,
)

/** How a calibration or cleaning run stands. Both kinds of run share one lifecycle. */
enum class RunStatus {
    RUNNING, FINISHED, FAILED, ABORTED, UNKNOWN;

    val isTerminal: Boolean get() = this != RUNNING
}

enum class CalibrationPhase { WAITING_GLASS, MEASURING, PUMPING, SETTLING, DONE, UNKNOWN }

/** One calibration run on the machine. Like a [PourJob], only ever replaced whole. */
data class CalibrationRun(
    val runId: String,
    val status: RunStatus,
    val phase: CalibrationPhase,
    val pumps: List<Int>,
    val currentPump: Int?,
    /** Already written for a person: "Pump 2 runs for 3 s", "The glass is nearly full…". */
    val message: String,
    val results: List<CalibrationResult>,
    val error: MachineFault?,
)

data class CalibrationResult(
    val pump: Int,
    val mlPerSecond: Double,
    val volumeMl: Double,
    val seconds: Double,
)

enum class CleaningPhase { PUMPING, PAUSING, DONE, UNKNOWN }

/**
 * One rinse of the pump lines: each pump in turn runs warm water into a container, [rounds]
 * times over. Like a [CalibrationRun], only ever replaced whole.
 */
data class CleaningRun(
    val runId: String,
    val status: RunStatus,
    val phase: CleaningPhase,
    val pumps: List<Int>,
    val rounds: Int,
    /** Per pump, per round. */
    val seconds: Double,
    /** 1-based. */
    val currentRound: Int?,
    val currentPump: Int?,
    /** Pump time done over pump time planned, computed by the machine. */
    val progress: Float,
    /** Already written for a person: "Rinsing pump 3 (round 1 of 2)". */
    val message: String,
    val error: MachineFault?,
)

/** One live reading of the ultrasonic sensor above the glass. */
data class SensorReading(
    /** Null when the sensor got no valid echo. */
    val distanceCm: Double?,
    val glassPresent: Boolean,
    val referenceCm: Double?,
)

/**
 * What the app asks the machine to pour. Items name bottles, not pumps: the Pi maps bottle
 * ids to pumps itself, so a stale rack fails cleanly instead of wrongly.
 */
data class PourRequest(
    val jobId: String,
    val drinkId: String?,
    val drinkName: String,
    val glass: String?,
    val items: List<PourItem>,
    val manualSteps: List<String>,
)

data class PourItem(
    val bottleId: String,
    val ingredientName: String,
    val ml: Double,
)

/** Where the machine lives. Empty [host] means "not set up yet". */
data class MachineAddress(
    val host: String,
    val port: Int,
    val enabled: Boolean,
) {
    val isConfigured: Boolean get() = enabled && host.isNotBlank()

    val httpBase: String get() = "http://$host:$port"
    val wsUrl: String get() = "ws://$host:$port/api/v1/events"

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

sealed interface ConnectionState {
    /** No address configured, or the switch is off. */
    data object Disabled : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val snapshot: MachineSnapshot) : ConnectionState

    data class Failed(val error: MachineError) : ConnectionState

    val isConnected: Boolean get() = this is Connected

    val snapshotOrNull: MachineSnapshot? get() = (this as? Connected)?.snapshot
}
