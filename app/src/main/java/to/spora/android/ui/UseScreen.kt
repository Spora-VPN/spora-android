package to.spora.android.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import to.spora.android.R
import to.spora.android.ConnectState
import to.spora.android.ConnectVpnService
import to.spora.android.SavedUseConnection
import to.spora.android.UseConnectionStore
import to.spora.android.UserError
import to.spora.android.ui.theme.ColorError
import to.spora.android.ui.theme.ColorPendingText
import to.spora.android.ui.theme.ColorSuccessText
import to.spora.android.ui.theme.TextMuted

@Composable
fun UseScreen(
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by ConnectState.uiState.collectAsState()
    var showModal by rememberSaveable { mutableStateOf(false) }
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var prefillUrl by rememberSaveable { mutableStateOf("") }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingUrl?.let { url ->
                ConnectVpnService.connect(context, url, pendingConnectionId)
            }
        } else {
            ConnectState.failed(UserError.VPN_PERMISSION_DENIED, pendingConnectionId)
        }
        pendingUrl = null
        pendingConnectionId = null
    }

    fun startVpnConnection(url: String, connectionId: String?) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingUrl = url
            pendingConnectionId = connectionId
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            ConnectVpnService.connect(context, url, connectionId)
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl != null) {
            val existing = uiState.savedConnections.find { it.url == initialUrl }
            val alreadyActive = existing != null &&
                uiState.activeConnectionId == existing.id &&
                (uiState.isConnected || uiState.isConnecting)
            if (existing != null) {
                // Don't tear down and re-establish a tunnel that is already
                // serving this exact connection
                if (!alreadyActive) {
                    if (uiState.isConnected || uiState.isConnecting) {
                        ConnectVpnService.disconnect(context)
                    }
                    startVpnConnection(existing.url, existing.id)
                }
            } else {
                prefillUrl = initialUrl
                showModal = true
            }
            onInitialUrlConsumed()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SporaActionCard(
                eyebrow = stringResource(R.string.use_action_eyebrow),
                title = stringResource(R.string.use_action_title),
                subtitle = stringResource(R.string.use_action_subtitle),
                onClick = {
                    prefillUrl = ""
                    showModal = true
                },
            )

            // Errors that can't be attributed to a saved connection
            // (per-connection errors render inside the matching list item)
            val unattributedError = uiState.error?.takeIf {
                uiState.savedConnections.none { c -> c.id == uiState.errorConnectionId }
            }
            if (unattributedError != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    StatusDot(DotState.Error)
                    Text(
                        text = stringResource(unattributedError.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorError,
                    )
                }
            }

            Column {
                Text(
                    text = stringResource(R.string.saved_connections_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                if (uiState.savedConnections.isEmpty()) {
                    EmptyStateCard(
                        title = stringResource(R.string.empty_list_title),
                        body = stringResource(R.string.use_empty_body),
                        logo = painterResource(R.drawable.ic_logo),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        uiState.savedConnections.forEach { connection ->
                            val isThisActive = uiState.activeConnectionId == connection.id
                            val isThisConnecting = isThisActive && uiState.isConnecting
                            val isThisConnected = isThisActive && uiState.isConnected
                            val error = uiState.error?.takeIf { uiState.errorConnectionId == connection.id }

                            val dotState = when {
                                isThisConnected -> DotState.Active
                                isThisConnecting -> DotState.Pending
                                else -> DotState.None
                            }
                            val statusText = when {
                                isThisConnected -> stringResource(R.string.use_status_connected)
                                isThisConnecting -> stringResource(R.string.use_status_connecting)
                                else -> null
                            }
                            val statusColor = when {
                                isThisConnected -> ColorSuccessText
                                else -> ColorPendingText
                            }

                            ConnectionCard(
                                name = connection.label,
                                url = connection.url,
                                dotState = dotState,
                                statusText = statusText,
                                statusColor = statusColor,
                                errorText = error?.let { stringResource(it.messageRes) },
                                checked = isThisConnected || isThisConnecting,
                                // Stays enabled while connecting so a stuck
                                // attempt can be cancelled
                                onToggle = { enabled ->
                                    if (enabled) {
                                        if (uiState.isConnected || uiState.isConnecting) {
                                            ConnectVpnService.disconnect(context)
                                        }
                                        startVpnConnection(connection.url, connection.id)
                                    } else {
                                        ConnectVpnService.disconnect(context)
                                    }
                                },
                                onDelete = if (!isThisActive) {
                                    { deleteConnectionId = connection.id }
                                } else null,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Save/edit modal
        ModalOverlay(
            visible = showModal,
            onDismiss = { showModal = false },
        ) {
            UseModalContent(
                initialUrl = prefillUrl,
                savedConnections = uiState.savedConnections,
                onConfirm = { url, label, existingId ->
                    showModal = false
                    if (existingId != null) {
                        val updated = SavedUseConnection(
                            id = existingId,
                            label = label.ifBlank { context.getString(R.string.use_default_label) },
                            url = url,
                        )
                        UseConnectionStore.update(updated)
                        ConnectState.updateConnection(updated)
                        startVpnConnection(updated.url, updated.id)
                    } else {
                        val connection = SavedUseConnection(
                            id = java.util.UUID.randomUUID().toString(),
                            label = label.ifBlank { context.getString(R.string.use_default_label) },
                            url = url,
                        )
                        UseConnectionStore.save(connection)
                        ConnectState.addConnection(connection)
                        startVpnConnection(connection.url, connection.id)
                    }
                },
                onCancel = { showModal = false },
            )
        }

        // Delete confirmation
        ModalOverlay(
            visible = deleteConnectionId != null,
            onDismiss = { deleteConnectionId = null },
        ) {
            DeleteConfirmContent(
                onConfirm = {
                    deleteConnectionId?.let { id ->
                        UseConnectionStore.delete(id)
                        ConnectState.removeConnection(id)
                    }
                    deleteConnectionId = null
                },
                onCancel = { deleteConnectionId = null },
            )
        }
    }
}

/**
 * Accepts the share-link shape the deep link filter handles:
 * https://spora.to/s/<token>[?r=host:port]
 */
internal fun isValidShareUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" &&
        uri.host == "spora.to" &&
        uri.path.orEmpty().length > "/s/".length &&
        uri.path.orEmpty().startsWith("/s/")
}

@Composable
private fun UseModalContent(
    initialUrl: String = "",
    savedConnections: List<SavedUseConnection> = emptyList(),
    onConfirm: (url: String, label: String, existingConnectionId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var label by rememberSaveable { mutableStateOf("") }
    val urlValid = remember(url) { isValidShareUrl(url.trim()) }
    val focusRequester = remember { FocusRequester() }
    val matchedConnection = remember(url, savedConnections) {
        savedConnections.find { it.url == url.trim() }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotEmpty()) {
            url = initialUrl
        }
    }

    LaunchedEffect(matchedConnection) {
        if (matchedConnection != null) {
            label = matchedConnection.label
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column {
        InputField(
            value = url,
            onValueChange = { url = it },
            label = stringResource(R.string.use_modal_url_label),
            placeholder = stringResource(R.string.use_modal_url_placeholder),
            error = if (url.isNotBlank() && !urlValid) {
                stringResource(R.string.error_invalid_url)
            } else null,
            focusRequester = focusRequester,
            // A URL keyboard without autocorrect, so pasted/typed links
            // don't get silently mangled
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(modifier = Modifier.height(22.dp))

        InputField(
            value = label,
            onValueChange = { label = it },
            label = stringResource(R.string.use_modal_label_label),
            placeholder = stringResource(R.string.use_modal_label_placeholder),
            helper = stringResource(R.string.use_modal_label_helper),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.use_modal_save),
            onClick = { if (urlValid) onConfirm(url.trim(), label, matchedConnection?.id) },
            enabled = urlValid,
        )

        CancelButton(onClick = onCancel)
    }
}
