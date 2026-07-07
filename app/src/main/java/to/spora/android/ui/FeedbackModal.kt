package to.spora.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bugfender.sdk.Bugfender
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import to.spora.android.R
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary

@Composable
fun FeedbackModalContent(onDismiss: () -> Unit) {
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    if (sent) {
        Column {
            Column(
                // The form this replaces held accessibility focus; without a
                // live region TalkBack would never announce the confirmation
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Text(
                    text = stringResource(R.string.feedback_thanks_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.feedback_thanks_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                text = stringResource(R.string.feedback_done),
                onClick = onDismiss,
            )
        }
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
            text = stringResource(R.string.feedback_send),
            onClick = {
                // The guard absorbs a double-tap landing before recomposition
                // swaps in the thank-you state
                if (!sent) {
                    sendFeedback(subject.trim(), message.trim())
                    sent = true
                }
            },
            enabled = subject.isNotBlank() && message.isNotBlank(),
        )

        CancelButton(onClick = onDismiss)
    }
}

// Fire-and-forget on purpose: the modal dismisses right after sending, and a
// lifecycle-bound scope would cancel the enqueue mid-flight
@OptIn(DelicateCoroutinesApi::class)
private fun sendFeedback(subject: String, message: String) {
    GlobalScope.launch(Dispatchers.IO) {
        runCatching { Bugfender.sendUserFeedback(subject, message) }
    }
}
