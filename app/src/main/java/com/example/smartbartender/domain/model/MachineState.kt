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
    val backend: String,
    val state: MachineRunState,
    val pumpCount: Int,
    val maxPourMl: Double,
    val slots: List<MachineSlot>,
    val led: LedShow,
    val currentJob: PourJob?,
    val fault: MachineFault?,
) {
    val isSimulated: Boolean get() = backend != "gpio"
}

enum class MachineRunState { IDLE, BUSY, FAULT, UNKNOWN }

/** One physical pump. [pump] is 1-based, matching the "SLOT 1" tiles on the bottles screen. */
data class MachineSlot(
    val pump: Int,
    val bottleId: String?,
    val mlPerSecond: Double,
)

data class LedShow(
    val enabled: Boolean,
    val mode: String,
    val cycleMillis: Int,
)

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
)

/** What the app asks the machine to pour. Volumes are already resolved; pumps are not — */
/** the Pi maps bottle ids to pumps itself, so a stale rack fails cleanly instead of wrongly. */
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

        /** The Android emulator's alias for the machine running Android Studio. */
        const val EMULATOR_HOST = "10.0.2.2"
    }
}

sealed interface ConnectionState {
    /** No address configured, or the switch is off. */
    data object Disabled : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val snapshot: MachineSnapshot) : ConnectionState

    data class Failed(val message: String) : ConnectionState

    val isConnected: Boolean get() = this is Connected

    val snapshotOrNull: MachineSnapshot? get() = (this as? Connected)?.snapshot
}
