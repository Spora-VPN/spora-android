package to.spora.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.spora.android.Diagnostics
import to.spora.android.OwnRelay
import to.spora.android.R
import to.spora.android.RelayAccess
import to.spora.android.RelayEndpointError
import to.spora.android.RelaySettings
import to.spora.android.normalizeRelayEndpoint
import to.spora.android.relayEndpointError
import to.spora.android.ui.theme.BorderHairline
import to.spora.android.ui.theme.BorderStrong
import to.spora.android.ui.theme.SurfaceCard
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary
import uniffi.spora_ffi.RelayAccessStatus

// The service renews relay access on its own thread; while the page is open
// its status is re-read at this pace.
private const val STATUS_POLL_MS = 2_000L

// No relay index: the editor adds a new relay
private const val NEW_RELAY = -1

/**
 * Settings › Relays: which relays new links are made with. Spora's relays (on
 * by default) and the user's own are independent; a link keeps the relays it
 * was made with, so nothing here changes an existing link.
 */
@Composable
fun RelaySettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by RelaySettings.state.collectAsState()
    val access by RelayAccess.status.collectAsState()
    val fetchFailed by RelayAccess.fetchFailed.collectAsState()
    val serviceUnavailable by Diagnostics.unavailable.collectAsState()
    // A fetch started here (switching Spora's relays on with none fetched)
    var fetching by remember { mutableStateOf(false) }
    // Index into settings.own, NEW_RELAY to add one; null: no editor
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var removing by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        RelaySettings.init(context)
        while (true) {
            RelayAccess.refresh()
            delay(STATUS_POLL_MS)
        }
    }

    // Spora's relays on and never fetched (a fresh install, or turned on
    // after a start without them): fetch now, so the status says whether
    // they can be reached before the user makes a link. Once per page and
    // switch-on: a failure leaves this true, so it does not loop.
    val neverFetched = settings.useSpora && access?.let {
        it.advisedRelays.isEmpty() && it.lastSuccessUnixMs == null
    } == true
    LaunchedEffect(neverFetched) {
        if (neverFetched) {
            fetching = true
            try {
                RelayAccess.fetch(context)
            } finally {
                fetching = false
            }
        }
    }

    Column {
        Text(
            text = stringResource(R.string.settings_relays_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(14.dp))
        SporaRelaysCard(
            useSpora = settings.useSpora,
            onUseSporaChange = { use ->
                RelaySettings.setUseSpora(use)
                scope.launch { RelayAccess.refresh() }
            },
            status = sporaRelayStatus(
                access = access,
                fetching = fetching,
                fetchFailed = fetchFailed,
                serviceUnavailable = serviceUnavailable,
            ).takeIf { settings.useSpora },
        )

        Spacer(modifier = Modifier.height(26.dp))
        SectionLabel(stringResource(R.string.settings_own_relays_header))
        Text(
            text = stringResource(R.string.settings_own_relays_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            settings.own.forEachIndexed { index, relay ->
                OwnRelayCard(
                    relay = relay,
                    onEdit = { editing = index },
                    onRemove = { removing = index },
                )
            }
            AddRelayButton(onClick = { editing = NEW_RELAY })
        }

        if (!settings.hasRelays) {
            StatusLine(
                DotState.Error,
                stringResource(R.string.settings_no_relays),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(
            text = stringResource(R.string.settings_relays_note),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.padding(top = 22.dp),
        )
    }

    ModalOverlay(
        visible = editing != null,
        onDismiss = { editing = null },
    ) {
        val index = editing ?: NEW_RELAY
        val current = settings.own.getOrNull(index)
        RelayEditContent(
            initial = current,
            // Every relay but the one being edited
            others = settings.own.filterIndexed { i, _ -> i != index }.map { it.endpoint },
            onSave = { relay ->
                RelaySettings.put(relay, index.takeIf { current != null })
                editing = null
            },
            onCancel = { editing = null },
        )
    }

    ModalOverlay(
        visible = removing != null,
        onDismiss = { removing = null },
    ) {
        RemoveRelayContent(
            onConfirm = {
                removing?.let { RelaySettings.remove(it) }
                removing = null
            },
            onCancel = { removing = null },
        )
    }
}

private data class RelayStatusLine(val dot: DotState, val text: String)

/**
 * What the status of Spora's relays tells the user: whether new links (and
 * running shares) can use them.
 */
@Composable
private fun sporaRelayStatus(
    access: RelayAccessStatus?,
    fetching: Boolean,
    fetchFailed: Boolean,
    serviceUnavailable: Boolean,
): RelayStatusLine {
    val now = System.currentTimeMillis()
    val tokenValid = access?.tokenExpiresUnixMs?.let { it.toLong() > now } == true
    return when {
        access == null && serviceUnavailable ->
            RelayStatusLine(DotState.Error, stringResource(R.string.settings_spora_status_unavailable))
        access == null || fetching ->
            RelayStatusLine(DotState.Pending, stringResource(R.string.settings_spora_status_checking))
        tokenValid && access.advisedRelays.isNotEmpty() ->
            RelayStatusLine(DotState.Active, stringResource(R.string.settings_spora_status_ready))
        access.lastError != null || fetchFailed ->
            RelayStatusLine(DotState.Error, stringResource(R.string.settings_spora_status_unreachable))
        // The API answered, and gave no token (or no relays)
        access.lastSuccessUnixMs != null ->
            RelayStatusLine(DotState.Error, stringResource(R.string.settings_spora_status_not_available))
        else ->
            RelayStatusLine(DotState.Pending, stringResource(R.string.settings_spora_status_checking))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SporaRelaysCard(
    useSpora: Boolean,
    onUseSporaChange: (Boolean) -> Unit,
    status: RelayStatusLine?,
) {
    val label = stringResource(R.string.settings_spora_toggle_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                // The switch carries the label for TalkBack
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
            )
            SporaToggle(
                checked = useSpora,
                onCheckedChange = onUseSporaChange,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_spora_toggle_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            if (status != null) StatusLine(status.dot, status.text)
        }
    }
}

@Composable
private fun OwnRelayCard(
    relay: OwnRelay,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.settings_relay_edit_action),
                onClick = onEdit,
            )
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = relay.endpoint,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (relay.token != null) R.string.settings_relay_token_set
                    else R.string.settings_relay_no_token,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        SmallIconButton(
            icon = SporaIcons.Trash,
            contentDescription = stringResource(R.string.settings_relay_remove_content_desc),
            onClick = onRemove,
        )
    }
}

// Dashed like an empty list: a place for something to go
@Composable
private fun AddRelayButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                drawRoundRect(
                    color = BorderStrong,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                        ),
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = SporaIcons.Plus,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.settings_relay_add),
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
        )
    }
}

@Composable
private fun RelayEditContent(
    initial: OwnRelay?,
    others: List<String>,
    onSave: (OwnRelay) -> Unit,
    onCancel: () -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf(initial?.endpoint ?: "") }
    var token by rememberSaveable { mutableStateOf(initial?.token ?: "") }
    // Errors show once the user tries to save, not while they type
    var tried by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val normalized = normalizeRelayEndpoint(endpoint)
    val error = relayEndpointError(normalized)
        ?: RelayEndpointError.DUPLICATE.takeIf { normalized in others }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column {
        Text(
            text = stringResource(
                if (initial == null) R.string.settings_relay_add_header
                else R.string.settings_relay_edit_header,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_relay_modal_title),
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
        )

        Spacer(modifier = Modifier.height(24.dp))
        InputField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = stringResource(R.string.settings_relay_address_label),
            placeholder = stringResource(R.string.settings_relay_address_placeholder),
            helper = stringResource(R.string.settings_relay_address_helper),
            error = if (tried && error != null) stringResource(error.messageRes()) else null,
            focusRequester = focusRequester,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        )

        Spacer(modifier = Modifier.height(22.dp))
        InputField(
            value = token,
            onValueChange = { token = it },
            label = stringResource(R.string.settings_relay_token_label),
            placeholder = stringResource(R.string.settings_relay_token_placeholder),
            helper = stringResource(R.string.settings_relay_token_helper),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        )

        Spacer(modifier = Modifier.height(24.dp))
        PrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = {
                tried = true
                if (error == null) {
                    onSave(OwnRelay(normalized, token.trim().ifEmpty { null }))
                }
            },
            enabled = endpoint.isNotBlank(),
        )
        CancelButton(onClick = onCancel)
    }
}

private fun RelayEndpointError.messageRes(): Int = when (this) {
    RelayEndpointError.FORMAT -> R.string.settings_relay_error_format
    RelayEndpointError.PROTOCOL -> R.string.settings_relay_error_protocol
    RelayEndpointError.IPV6_BRACKETS -> R.string.settings_relay_error_ipv6
    RelayEndpointError.PORT -> R.string.settings_relay_error_port
    RelayEndpointError.DUPLICATE -> R.string.settings_relay_error_duplicate
}

@Composable
private fun RemoveRelayContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_relay_remove_header),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.delete_modal_title),
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_relay_remove_message),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(24.dp))
        PrimaryButton(
            text = stringResource(R.string.action_delete),
            onClick = onConfirm,
        )
        CancelButton(onClick = onCancel)
    }
}
