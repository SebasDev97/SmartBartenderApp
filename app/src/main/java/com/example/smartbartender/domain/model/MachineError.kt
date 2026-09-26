package com.example.smartbartender.domain.model

import java.io.IOException

/**
 * Why a command to the machine did not go through.
 *
 * Typed rather than a sentence so callers can branch on it (a [Refused] with
 * `NOT_CALIBRATED` is a different problem from an unplugged Pi) and so the UI does the
 * wording. Only [Refused] carries text of its own: the Pi's `{"error": {"code", "message"}}`
 * is already written for a person — "Tequila (tequila) is not loaded" beats "HTTP 422".
 */
sealed interface MachineError {
    /** No address set, or the machine link is switched off. */
    data object NotConfigured : MachineError

    /** The Test button was pressed with an empty address field. */
    data object MissingAddress : MachineError

    data object Timeout : MachineError

    data object Unreachable : MachineError

    /** The event socket dropped. [detail] is the transport's own reason, if it gave one. */
    data class ConnectionLost(val detail: String?) : MachineError

    /** The machine answered and said no. See `pi/API.md` for the codes. */
    data class Refused(val code: String?, val message: String?, val httpStatus: Int) : MachineError

    data class Unexpected(val detail: String?) : MachineError
}

/** How a [MachineError] travels inside a [Result]. */
class MachineException(val error: MachineError, cause: Throwable? = null) : IOException(error.toString(), cause)

/** The [MachineError] behind a failed machine [Result]; anything else counts as unexpected. */
fun Throwable.toMachineError(): MachineError = (this as? MachineException)?.error ?: MachineError.Unexpected(message)
