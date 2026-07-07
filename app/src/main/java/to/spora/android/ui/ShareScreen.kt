package to.spora.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.spora.android.R
import to.spora.android.SharedConnection
import to.spora.android.SharedConnectionStore
import to.spora.android.ShareForegroundService
import to.spora.android.ShareState
import to.spora.android.ui.theme.ColorPendingText
import to.spora.android.ui.theme.ColorSuccessText
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary

@Composable
fun ShareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by ShareState.uiState.collectAsState()
    var creatingIdentity by remember { mutableStateOf(false) }
    var pendingShareConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConnectionId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingShareConnectionId, uiState.activeShares, uiState.errors) {
        val pendingId = pendingShareConnectionId ?: return@LaunchedEffect
        if (uiState.errors[pendingId] != null) {
            // The share failed; the list item shows the error — don't let the
            // share sheet and rename modal fire on a later successful retry.
            pendingShareConnectionId = null
            return@LaunchedEffect
        }
        val url = uiState.activeShares[pendingId]?.url ?: return@LaunchedEffect
        pendingShareConnectionId = null
        shareUrl(context, url)
        renameConnectionId = pendingId
    }

    fun createAndShare() {
        if (creatingIdentity || pendingShareConnectionId != null) return
        creatingIdentity = true
        coroutineScope.launch {
            // Key/cert generation is a blocking FFI call
            val identity = withContext(Dispatchers.Default) {
                java.util.Base64.getEncoder()
                    .encodeToString(uniffi.spora_ffi.makeIdentity())
            }
            val existingCount = ShareState.uiState.value.connections.size
            val label = context.getString(R.string.share_default_label, existingCount + 1)
            val connection = SharedConnection(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                identity = identity,
            )
            SharedConnectionStore.save(connection)
            ShareState.addConnection(connection)
            pendingShareConnectionId = connection.id
            ShareForegroundService.startConnection(context, connection.id, connection.identity)
            creatingIdentity = false
        }
    }

    val busy = creatingIdentity || pendingShareConnectionId != null

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SporaActionCard(
                eyebrow = stringResource(
                    if (busy) R.string.share_action_eyebrow_busy else R.string.share_action_eyebrow,
                ),
                title = stringResource(
                    if (busy) R.string.share_action_creating else R.string.share_action_title,
                ),
                subtitle = stringResource(
                    if (busy) R.string.share_action_subtitle_busy else R.string.share_action_subtitle,
                ),
                busy = busy,
                onClick = { createAndShare() },
            )

            Column {
                Text(
                    text = stringResource(R.string.saved_connections_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                if (uiState.connections.isEmpty()) {
                    EmptyStateCard(
                        title = stringResource(R.string.empty_list_title),
                        body = stringResource(R.string.share_empty_body),
                        logo = painterResource(R.drawable.ic_logo),
                    )
                } else {
                    ShareConnectionList(
                        uiState = uiState,
                        context = context,
                        onDelete = { deleteConnectionId = it },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Rename modal (shown after share intent)
        ModalOverlay(
            visible = renameConnectionId != null,
            onDismiss = { renameConnectionId = null },
        ) {
            val connectionId = renameConnectionId
            val currentLabel = connectionId?.let { id ->
                uiState.connections.find { it.id == id }?.label
            } ?: ""

            RenameModalContent(
                currentLabel = currentLabel,
                onConfirm = { newLabel ->
                    connectionId?.let { id ->
                        val connection = uiState.connections.find { it.id == id }
                        if (connection != null) {
                            val updated = connection.copy(label = newLabel)
                            SharedConnectionStore.update(updated)
                            ShareState.renameConnection(id, newLabel)
                        }
                    }
                    renameConnectionId = null
                },
                onDismiss = { renameConnectionId = null },
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
                        SharedConnectionStore.delete(id)
                        ShareState.removeConnection(id)
                    }
                    deleteConnectionId = null
                },
                onCancel = { deleteConnectionId = null },
            )
        }
    }
}

@Composable
private fun ShareConnectionList(
    uiState: to.spora.android.ShareUiState,
    context: Context,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        uiState.connections.forEach { connection ->
            val isActive = connection.id in uiState.activeShares
            val isStarting = connection.id in uiState.startingIds
            val error = uiState.errors[connection.id]
            val url = uiState.activeShares[connection.id]?.url

            val dotState = when {
                isActive -> DotState.Active
                isStarting -> DotState.Pending
                else -> DotState.None
            }
            val statusText = when {
                isActive -> stringResource(R.string.share_status_sharing)
                isStarting -> stringResource(R.string.share_status_starting)
                else -> stringResource(R.string.share_status_inactive)
            }
            val statusColor = when {
                isActive -> ColorSuccessText
                isStarting -> ColorPendingText
                else -> TextMuted
            }

            ConnectionCard(
                name = connection.label,
                dotState = dotState,
                statusText = statusText,
                statusColor = statusColor,
                errorText = error?.let { stringResource(it.messageRes) },
                checked = isActive || isStarting,
                // Stays enabled while starting so a stuck share can be cancelled
                onToggle = { enabled ->
                    if (enabled) {
                        ShareForegroundService.startConnection(
                            context,
                            connection.id,
                            connection.identity,
                        )
                    } else {
                        ShareForegroundService.stopConnection(context, connection.id)
                    }
                },
                showBarcode = isActive,
                barcodeSeed = connection.id.hashCode(),
                onShareAgain = if (isActive && url != null) {
                    { shareUrl(context, url) }
                } else null,
                onDelete = if (!isActive && !isStarting) {
                    { onDelete(connection.id) }
                } else null,
            )
        }
    }
}

@Composable
private fun RenameModalContent(
    currentLabel: String,
    onConfirm: (label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(currentLabel) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column {
        InputField(
            value = label,
            onValueChange = { label = it },
            label = stringResource(R.string.share_rename_label),
            placeholder = stringResource(R.string.share_rename_placeholder),
            helper = stringResource(R.string.share_rename_hint),
            focusRequester = focusRequester,
        )

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = { if (label.isNotBlank()) onConfirm(label) },
            enabled = label.isNotBlank(),
        )

        // Skipping keeps the default name — naming is a favor, not a chore
        TextActionButton(
            text = stringResource(R.string.share_rename_skip),
            onClick = onDismiss,
        )
    }
}

@Composable
internal fun DeleteConfirmContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
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
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.delete_modal_message),
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

private fun shareUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        // text/plain: most share targets don't register for text/html, which
        // hid them from the chooser
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
