package com.example.smartbartender.ui.screens.machine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineFault
import com.example.smartbartender.domain.model.RunStatus
import com.example.smartbartender.ui.common.text
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.NeonLime
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.TextSecondary

/**
 * The scrolling page of a screen that runs on the machine itself. Without a connection it
 * says so and shows nothing else; while the machine is busy with other work it says that
 * above the [content], which stays visible but should disable its own controls.
 */
@Composable
fun MachineRunPage(
    connected: Boolean,
    busy: Boolean,
    disconnectedMessage: String,
    busyMessage: String,
    error: MachineError?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        if (!connected) {
            Text(text = disconnectedMessage, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            return@Column
        }
        if (busy) {
            Text(text = busyMessage, style = MaterialTheme.typography.bodyMedium, color = NeonCyan)
            Spacer(Modifier.height(16.dp))
        }

        content()

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(text = it.text(), style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
        }
    }
}

/** How the previous calibration or cleaning run ended, in the machine's words where it gave some. */
@Composable
fun LastRunSummary(status: RunStatus, message: String, fault: MachineFault?, stoppedText: String) {
    val (text, color) = when (status) {
        RunStatus.FINISHED -> message to NeonLime
        RunStatus.ABORTED -> stoppedText to TextSecondary
        else -> (fault?.message ?: message) to ErrorRed
    }
    Text(
        text = stringResource(R.string.run_last, text),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
fun AccentCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, accent: Color) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Obsidian),
    )
}
