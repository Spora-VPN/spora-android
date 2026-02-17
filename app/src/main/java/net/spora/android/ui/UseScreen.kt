package net.spora.android.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.spora.android.ConnectState
import net.spora.android.ConnectVpnService
import net.spora.android.SavedUseConnection
import net.spora.android.UseConnectionStore
import net.spora.android.ui.theme.CardBackground
import net.spora.android.ui.theme.TextLight
import net.spora.android.ui.theme.TextMain
import net.spora.android.ui.theme.TextMuted
import net.spora.android.ui.theme.Yellow

@Composable
fun UseScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ConnectState.uiState.collectAsState()
    var showModal by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pendingConnectionId by remember { mutableStateOf<String?>(null) }
    var deleteConnectionId by remember { mutableStateOf<String?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingUrl?.let { url ->
                ConnectVpnService.connect(context, url, pendingConnectionId)
            }
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            StatusHeader(
                label = "Data Consumed",
                value = "8.2",
                unit = "GB",
                periodLabel = "Today",
                showBarcode = false,
            )

            if (uiState.savedConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "NO SAVED CONNECTIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted.copy(alpha = 0.5f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.savedConnections.forEach { connection ->
                        val isThisActive = uiState.activeConnectionId == connection.id
                        val isThisConnecting = isThisActive && uiState.isConnecting
                        val isThisConnected = isThisActive && uiState.isConnected

                        UseConnectionItem(
                            connection = connection,
                            isActive = isThisConnected || isThisConnecting,
                            isConnecting = isThisConnecting,
                            onToggle = { enabled ->
                                if (enabled) {
                                    // Disconnect current first if different
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

            Spacer(modifier = Modifier.height(72.dp))
        }

        // Floating add button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                )
                .size(56.dp)
                .background(
                    color = Yellow,
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable { showModal = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SporaIcons.Plus,
                contentDescription = "Add connection",
                tint = TextMain,
                modifier = Modifier.size(24.dp),
            )
        }

        // Modal overlay
        ModalOverlay(
            visible = showModal,
            onDismiss = { showModal = false },
        ) {
            UseModalContent(
                onConfirm = { url, label ->
                    showModal = false
                    val connection = SavedUseConnection(
                        id = java.util.UUID.randomUUID().toString(),
                        label = label.ifBlank { "Unknown Connection" },
                        url = url,
                    )
                    UseConnectionStore.save(connection)
                    ConnectState.addConnection(connection)
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
private fun UseConnectionItem(
    connection: SavedUseConnection,
    isActive: Boolean,
    isConnecting: Boolean,
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
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                } else if (isConnecting) {
                    Text(
                        text = "Connecting\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
            if (onDelete != null) {
                Icon(
                    imageVector = SporaIcons.Delete,
                    contentDescription = "Delete connection",
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
    onConfirm: (url: String, label: String) -> Unit,
    onCancel: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        InputField(
            value = url,
            onValueChange = { url = it },
            label = "Connection URL",
            placeholder = "spora://...",
            focusRequester = focusRequester,
        )

        InputField(
            value = label,
            onValueChange = { label = it },
            label = "Label (Optional)",
            placeholder = "e.g. Home Base",
        )

        PrimaryButton(
            text = "SAVE CONNECTION",
            onClick = { if (url.isNotBlank()) onConfirm(url, label) },
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
                text = "DELETE CONNECTION",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Are you sure?",
                style = MaterialTheme.typography.headlineLarge,
                color = TextMain,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This connection will be permanently removed.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        PrimaryButton(
            text = "DELETE",
            onClick = onConfirm,
        )

        CancelButton(onClick = onCancel)
    }
}
