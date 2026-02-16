package net.spora.android.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.spora.android.SharedConnection
import net.spora.android.SharedConnectionStore
import net.spora.android.ShareForegroundService
import net.spora.android.ShareState
import net.spora.android.ui.theme.Border
import net.spora.android.ui.theme.CardBackground
import net.spora.android.ui.theme.Orange
import net.spora.android.ui.theme.TextLight
import net.spora.android.ui.theme.TextMain
import net.spora.android.ui.theme.TextMuted

@Composable
fun ShareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ShareState.uiState.collectAsState()
    var showModal by remember { mutableStateOf(false) }
    var pendingShareConnectionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingShareConnectionId, uiState.activeShares) {
        val pendingId = pendingShareConnectionId ?: return@LaunchedEffect
        val url = uiState.activeShares[pendingId]?.url ?: return@LaunchedEffect
        pendingShareConnectionId = null
        shareUrl(context, url)
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
                label = "Total Traffic Shared",
                value = "142.5",
                unit = "GB",
                periodLabel = "June",
            )

            // Action card or connection list
            if (uiState.connections.isEmpty()) {
                ActionCard(onClick = { showModal = true })
            } else {
                ActionCard(onClick = { showModal = true })

                ConnectionList(
                    uiState = uiState,
                    context = context,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Modal overlay
        ModalOverlay(
            visible = showModal,
            onDismiss = { showModal = false },
        ) {
            ShareModalContent(
                onConfirm = { label ->
                    showModal = false
                    val key = uniffi.spora_ffi.makeSecretKey()
                    val connection = SharedConnection(
                        id = java.util.UUID.randomUUID().toString(),
                        label = label,
                        secretKey = key,
                    )
                    SharedConnectionStore.save(connection)
                    ShareState.addConnection(connection)
                    pendingShareConnectionId = connection.id
                    ShareForegroundService.startConnection(context, connection.id, connection.secretKey)
                },
                onCancel = { showModal = false },
            )
        }
    }
}

@Composable
private fun ActionCard(onClick: () -> Unit) {
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
                text = "! INACTIVE",
                style = MaterialTheme.typography.labelSmall,
                color = TextLight.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "START SHARING",
                style = MaterialTheme.typography.labelSmall,
                color = TextLight.copy(alpha = 0.9f),
            )
            Text(
                text = "New Connection",
                style = MaterialTheme.typography.headlineLarge,
                color = TextLight,
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
    uiState: net.spora.android.ShareUiState,
    context: Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "ACTIVE CONNECTIONS",
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
                            connection.secretKey,
                        )
                    } else {
                        ShareForegroundService.stopConnection(context, connection.id)
                    }
                },
                onShareClick = if (isActive && url != null) {
                    { shareUrl(context, url) }
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
    error: String?,
    onToggle: (Boolean) -> Unit,
    onShareClick: (() -> Unit)?,
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
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    } else if (isStarting) {
                        Text(
                            text = "Starting\u2026",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    } else if (error != null) {
                        Text(
                            text = "Error: $error",
                            style = MaterialTheme.typography.labelSmall,
                            color = Orange,
                        )
                    } else {
                        Text(
                            text = "Inactive",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
            if (onShareClick != null) {
                Icon(
                    imageVector = SporaIcons.Share,
                    contentDescription = "Share link",
                    tint = TextMain,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(20.dp)
                        .clickable(onClick = onShareClick),
                )
            }
            SporaToggle(
                checked = isActive || isStarting,
                onCheckedChange = onToggle,
                enabled = !isStarting,
            )
        }

        if (isActive) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniBarcodeStrip()
                Text(
                    text = "12.4 MB/s",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = TextMain,
                )
            }
        }
    }
}

@Composable
private fun ShareModalContent(
    onConfirm: (label: String) -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Column {
            InputField(
                value = label,
                onValueChange = { label = it },
                label = "Label Connection",
                placeholder = "e.g. Mom's Phone",
                focusRequester = focusRequester,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Naming helps identify who is using your net.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        PrimaryButton(
            text = "GENERATE LINK",
            onClick = { if (label.isNotBlank()) onConfirm(label) },
            enabled = label.isNotBlank(),
            trailingIcon = {
                Icon(
                    imageVector = SporaIcons.ArrowRight,
                    contentDescription = null,
                    tint = TextLight,
                    modifier = Modifier.size(20.dp),
                )
            },
        )

        CancelButton(onClick = onCancel)
    }
}

private fun shareUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
