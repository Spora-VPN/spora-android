package to.spora.android.ui

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.spora.android.R
import to.spora.android.ConnectState
import to.spora.android.ConnectVpnService
import to.spora.android.SavedUseConnection
import to.spora.android.UseConnectionStore
import to.spora.android.UserError
import to.spora.android.ui.theme.CardBackground
import to.spora.android.ui.theme.TextLight
import to.spora.android.ui.theme.TextMain
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.Orange

@Composable
fun UseScreen(
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by ConnectState.uiState.collectAsState()
    var showModal by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pendingConnectionId by remember { mutableStateOf<String?>(null) }
    var deleteConnectionId by remember { mutableStateOf<String?>(null) }
    var prefillUrl by remember { mutableStateOf("") }

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
            if (existing != null) {
                if (uiState.isConnected || uiState.isConnecting) {
                    ConnectVpnService.disconnect(context)
                }
                startVpnConnection(existing.url, existing.id)
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            UseActionCard(onClick = {
                prefillUrl = ""
                showModal = true
            })

            // Errors that can't be attributed to a saved connection
            // (per-connection errors render inside the matching list item)
            val unattributedError = uiState.error?.takeIf {
                uiState.savedConnections.none { c -> c.id == uiState.errorConnectionId }
            }
            if (unattributedError != null) {
                Text(
                    text = stringResource(unattributedError.messageRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Orange,
                )
            }

            if (uiState.savedConnections.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.saved_connections_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )

                    uiState.savedConnections.forEach { connection ->
                        val isThisActive = uiState.activeConnectionId == connection.id
                        val isThisConnecting = isThisActive && uiState.isConnecting
                        val isThisConnected = isThisActive && uiState.isConnected

                        UseConnectionItem(
                            connection = connection,
                            isActive = isThisConnected || isThisConnecting,
                            isConnecting = isThisConnecting,
                            error = uiState.error?.takeIf { uiState.errorConnectionId == connection.id },
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

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Modal overlay
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
            UseDeleteConfirmContent(
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

@Composable
private fun UseActionCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Orange.copy(alpha = 0.4f),
                spotColor = Orange.copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Orange)
            .clickable(onClick = onClick)
            .padding(32.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.use_action_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextLight,
            )
            Text(
                text = stringResource(R.string.use_action_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = TextLight.copy(alpha = 0.7f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(40.dp)
                .background(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SporaIcons.ArrowRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UseConnectionItem(
    connection: SavedUseConnection,
    isActive: Boolean,
    isConnecting: Boolean,
    error: UserError?,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.label,
                    style = MaterialTheme.typography.displayMedium,
                    color = TextMain,
                )
                Text(
                    text = if (connection.url.length > 25) connection.url.take(25) + "\u2026" else connection.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted.copy(alpha = 0.5f),
                )
                if (isActive && !isConnecting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4CAF50), CircleShape),
                        )
                        Text(
                            text = stringResource(R.string.use_status_connected),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                } else if (isConnecting) {
                    Text(
                        text = stringResource(R.string.use_status_connecting),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                } else if (error != null) {
                    Text(
                        text = stringResource(error.messageRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = Orange,
                    )
                }
            }
            if (onDelete != null) {
                Icon(
                    imageVector = SporaIcons.Delete,
                    contentDescription = stringResource(R.string.delete_connection_content_desc),
                    tint = TextMuted,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(20.dp)
                        .clickable(onClick = onDelete),
                )
            }
            SporaToggle(
                checked = isActive,
                onCheckedChange = onToggle,
                enabled = !isConnecting,
            )
        }
    }
}

@Composable
private fun UseModalContent(
    initialUrl: String = "",
    savedConnections: List<SavedUseConnection> = emptyList(),
    onConfirm: (url: String, label: String, existingConnectionId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var label by remember { mutableStateOf("") }
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

    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        InputField(
            value = url,
            onValueChange = { url = it },
            label = stringResource(R.string.use_modal_url_label),
            placeholder = stringResource(R.string.use_modal_url_placeholder),
            focusRequester = focusRequester,
        )

        InputField(
            value = label,
            onValueChange = { label = it },
            label = stringResource(R.string.use_modal_label_label),
            placeholder = stringResource(R.string.use_modal_label_placeholder),
        )

        PrimaryButton(
            text = stringResource(R.string.use_modal_save),
            onClick = { if (url.isNotBlank()) onConfirm(url.trim(), label, matchedConnection?.id) },
            enabled = url.isNotBlank(),
            trailingIcon = {
                Icon(
                    imageVector = SporaIcons.Plus,
                    contentDescription = null,
                    tint = TextLight,
                    modifier = Modifier.size(20.dp),
                )
            },
        )

        CancelButton(onClick = onCancel)
    }
}

@Composable
private fun UseDeleteConfirmContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Column {
            Text(
                text = stringResource(R.string.delete_modal_header),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.delete_modal_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextMain,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.delete_modal_message),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        PrimaryButton(
            text = stringResource(R.string.action_delete),
            onClick = onConfirm,
        )

        CancelButton(onClick = onCancel)
    }
}
