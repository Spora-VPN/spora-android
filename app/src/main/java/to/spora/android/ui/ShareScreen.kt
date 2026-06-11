package to.spora.android.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.spora.android.R
import to.spora.android.SharedConnection
import to.spora.android.SharedConnectionStore
import to.spora.android.ShareForegroundService
import to.spora.android.ShareState
import to.spora.android.UserError
import to.spora.android.ui.theme.CardBackground
import to.spora.android.ui.theme.Orange
import to.spora.android.ui.theme.TextLight
import to.spora.android.ui.theme.TextMain
import to.spora.android.ui.theme.TextMuted

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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            ActionCard(busy = busy, onClick = { createAndShare() })

            if (uiState.connections.isNotEmpty()) {
                ConnectionList(
                    uiState = uiState,
                    context = context,
                    onDelete = { deleteConnectionId = it },
                )
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
private fun ActionCard(busy: Boolean, onClick: () -> Unit) {
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
            .background(Orange.copy(alpha = if (busy) 0.6f else 1f))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(32.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.share_action_title),
                style = MaterialTheme.typography.headlineLarge,
                color = TextLight,
            )
            Text(
                text = stringResource(
                    if (busy) R.string.share_action_creating else R.string.share_action_subtitle,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = TextLight.copy(alpha = 0.7f),
            )
        }

        // Circle arrow button
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
private fun ConnectionList(
    uiState: to.spora.android.ShareUiState,
    context: Context,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.saved_connections_header),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        uiState.connections.forEach { connection ->
            val isActive = connection.id in uiState.activeShares
            val isStarting = connection.id in uiState.startingIds
            val error = uiState.errors[connection.id]
            val url = uiState.activeShares[connection.id]?.url

            ConnectionItem(
                label = connection.label,
                isActive = isActive,
                isStarting = isStarting,
                error = error,
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
                onShareClick = if (isActive && url != null) {
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
private fun ConnectionItem(
    label: String,
    isActive: Boolean,
    isStarting: Boolean,
    error: UserError?,
    onToggle: (Boolean) -> Unit,
    onShareClick: (() -> Unit)?,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.displayMedium,
                    color = TextMain,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4CAF50), CircleShape),
                        )
                        Text(
                            text = stringResource(R.string.share_status_sharing),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    } else if (isStarting) {
                        Text(
                            text = stringResource(R.string.share_status_starting),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    } else if (error != null) {
                        Text(
                            text = stringResource(error.messageRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = Orange,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.share_status_inactive),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
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
            if (onShareClick != null) {
                Icon(
                    imageVector = SporaIcons.Share,
                    contentDescription = stringResource(R.string.share_link_content_desc),
                    tint = TextMain,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(20.dp)
                        .clickable(onClick = onShareClick),
                )
            }
            // Stays enabled while starting so a stuck share can be cancelled
            SporaToggle(
                checked = isActive || isStarting,
                onCheckedChange = onToggle,
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

    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Column {
            InputField(
                value = label,
                onValueChange = { label = it },
                label = stringResource(R.string.share_rename_label),
                placeholder = stringResource(R.string.share_rename_placeholder),
                focusRequester = focusRequester,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.share_rename_hint),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        PrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = { if (label.isNotBlank()) onConfirm(label) },
            enabled = label.isNotBlank(),
        )

        CancelButton(onClick = onDismiss)
    }
}

@Composable
private fun DeleteConfirmContent(
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

private fun shareUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        // text/plain: most share targets don't register for text/html, which
        // hid them from the chooser
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
