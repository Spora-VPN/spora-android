package to.spora.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.spora.android.Diagnostics
import to.spora.android.R
import to.spora.android.ui.theme.BorderHairline
import to.spora.android.ui.theme.ColorError
import to.spora.android.ui.theme.ColorPendingText
import to.spora.android.ui.theme.SurfaceCard
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary

// The uploader works on its own thread; while the modal is open its status
// (last upload, device id once registered) is re-read at this pace.
private const val STATUS_POLL_MS = 2_000L

/**
 * The upload switch. [onRequired]: the account requires uploads, so switching
 * off was refused and the user is owed the force notice, which MainScreen
 * shows (one owner, so it can never stack on a second copy).
 */
@Composable
fun DiagnosticsModalContent(
    onDismiss: () -> Unit,
    onRequired: () -> Unit,
) {
    val status by Diagnostics.status.collectAsState()
    val unavailable by Diagnostics.unavailable.collectAsState()
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    var switchFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            Diagnostics.refresh()
            delay(STATUS_POLL_MS)
        }
    }

    val current = status
    Column {
        Column {
            Text(
                text = stringResource(R.string.diagnostics_modal_header),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diagnostics_modal_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diagnostics_modal_what),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diagnostics_modal_why),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diagnostics_modal_off),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        val toggleLabel = stringResource(R.string.diagnostics_toggle_label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = toggleLabel,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                // The switch below carries the label for TalkBack
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
            )
            SporaToggle(
                checked = current?.uploadEnabled == true,
                onCheckedChange = { wanted ->
                    // The guard absorbs a double-tap landing before the
                    // switch's new state has been read back
                    if (!switching) {
                        switching = true
                        scope.launch {
                            val result = Diagnostics.setUploadEnabled(wanted)
                            switchFailed = result == Diagnostics.SwitchResult.FAILED
                            if (result == Diagnostics.SwitchResult.REQUIRED) onRequired()
                            switching = false
                        }
                    }
                },
                enabled = current != null,
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            )
        }

        when {
            // Null status: the service is still starting, or could not start
            current == null && unavailable ->
                StatusLine(DotState.Error, stringResource(R.string.diagnostics_unavailable))
            switchFailed ->
                StatusLine(DotState.Error, stringResource(R.string.diagnostics_switch_failed))
            current != null && current.uploadEnabled && current.lastError != null ->
                StatusLine(DotState.Pending, stringResource(R.string.diagnostics_upload_failed))
        }

        current?.deviceId?.let { id ->
            Spacer(modifier = Modifier.height(22.dp))
            DataField(
                label = stringResource(R.string.diagnostics_device_id_label),
                value = id,
                helper = stringResource(R.string.diagnostics_device_id_hint),
            )
        }

        if (current != null && (current.uploadEnabled || current.lastUploadUnixMs != null)) {
            val context = LocalContext.current
            Spacer(modifier = Modifier.height(18.dp))
            DataField(
                label = stringResource(R.string.diagnostics_last_upload_label),
                value = current.lastUploadUnixMs?.let { ms ->
                    DateUtils.formatDateTime(
                        context,
                        ms.toLong(),
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                            DateUtils.FORMAT_ABBREV_MONTH,
                    )
                } ?: stringResource(R.string.diagnostics_last_upload_never),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.action_done),
            onClick = onDismiss,
        )
    }
}

// A status dot and its message under the switch; announced when it appears,
// since it answers a tap on the switch or arrives with a poll
@Composable
private fun StatusLine(state: DotState, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .padding(top = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        StatusDot(state)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == DotState.Error) ColorError else ColorPendingText,
        )
    }
}

/**
 * The force notice: the account requires uploads, so the switch stays on.
 * MainScreen shows it when the user tries to switch off, and once when the
 * flag first arrives.
 */
@Composable
fun TelemetryRequiredContent(
    onKeep: () -> Unit,
    onContactSupport: () -> Unit,
) {
    Column {
        Column {
            Text(
                text = stringResource(R.string.telemetry_required_header),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.telemetry_required_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.telemetry_required_message),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.telemetry_required_keep),
            onClick = onKeep,
        )

        TextActionButton(
            text = stringResource(R.string.telemetry_required_contact),
            onClick = onContactSupport,
        )
    }
}

// A mono data value (selectable, e.g. to paste into a support mail) under a
// micro-label.
@Composable
internal fun DataField(
    label: String,
    value: String,
    helper: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (helper != null) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
