package to.spora.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.spora.android.BugReport
import to.spora.android.R
import to.spora.android.ui.theme.ColorError
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary

@Composable
fun FeedbackModalContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    // null while writing; once handed to the share sheet, whether the
    // diagnostic report went with it
    var reportAttached by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // The report written for this message, reused when the chooser is opened
    // again (cacheDir/reports keeps it a day)
    var reportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Also from the hand-off screen: a user who backed out of the chooser
    // can pick an app again without retyping anything
    val send: () -> Unit = {
        // The guard absorbs a double-tap landing before recomposition
        // disables the button
        if (!preparing) {
            preparing = true
            val s = subject.trim()
            val m = message.trim()
            scope.launch {
                val report = reportPath?.let { File(it) }?.takeIf { it.isFile }
                    ?: withContext(Dispatchers.IO) { BugReport.export(context, "$s\n\n$m") }
                reportPath = report?.path
                reportAttached = BugReport.send(context, s, m, report)
                preparing = false
            }
        }
    }

    val handedOff = reportAttached
    if (handedOff != null) {
        HandoffContent(reportAttached = handedOff, onSendAgain = send, onDismiss = onDismiss)
        return
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column {
        Column {
            Text(
                text = stringResource(R.string.feedback_modal_header),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_modal_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_modal_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_modal_report_note),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        InputField(
            value = subject,
            onValueChange = { subject = it },
            label = stringResource(R.string.feedback_subject_label),
            placeholder = stringResource(R.string.feedback_subject_placeholder),
            focusRequester = focusRequester,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(modifier = Modifier.height(22.dp))

        InputField(
            value = message,
            onValueChange = { message = it },
            label = stringResource(R.string.feedback_message_label),
            placeholder = stringResource(R.string.feedback_message_placeholder),
            singleLine = false,
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
        )

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(
                if (preparing) R.string.feedback_preparing else R.string.feedback_send,
            ),
            onClick = send,
            enabled = !preparing && subject.isNotBlank() && message.isNotBlank(),
        )

        CancelButton(onClick = onDismiss)
    }
}

// After the share sheet: nothing has reached us yet, and the copy says so.
@Composable
private fun HandoffContent(
    reportAttached: Boolean,
    onSendAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        Column(
            // The form this replaces held accessibility focus; without a
            // live region TalkBack would never announce the next step
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(
                text = stringResource(R.string.feedback_handoff_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_handoff_message),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            if (!reportAttached) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    StatusDot(DotState.Error)
                    Text(
                        text = stringResource(R.string.feedback_no_report),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorError,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        DataField(
            label = stringResource(R.string.feedback_support_address_label),
            value = stringResource(R.string.support_email),
        )

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.action_done),
            onClick = onDismiss,
        )

        TextActionButton(
            text = stringResource(R.string.feedback_send_again),
            onClick = onSendAgain,
        )
    }
}
