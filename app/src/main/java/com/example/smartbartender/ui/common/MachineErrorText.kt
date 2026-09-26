package com.example.smartbartender.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.MachineError

/** A [MachineError] worded for a person. A refusal is shown in the machine's own words when it gave any. */
@Composable
fun MachineError.text(): String = when (this) {
    MachineError.NotConfigured -> stringResource(R.string.machine_error_not_connected)
    MachineError.MissingAddress -> stringResource(R.string.machine_error_missing_address)
    MachineError.Timeout -> stringResource(R.string.machine_error_timeout)
    MachineError.Unreachable -> stringResource(R.string.machine_error_unreachable)
    is MachineError.ConnectionLost -> detail ?: stringResource(R.string.machine_error_connection_lost)
    is MachineError.Refused -> message ?: stringResource(R.string.machine_error_refused, httpStatus)
    is MachineError.Unexpected -> detail ?: stringResource(R.string.machine_error_unexpected)
}
