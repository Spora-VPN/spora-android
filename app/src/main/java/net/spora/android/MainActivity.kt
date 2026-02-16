package net.spora.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.spora.android.ui.theme.SporaTheme
import uniffi.spora_ffi.initAndroidLogging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedConnectionStore.init(this)
        ShareState.loadConnections(SharedConnectionStore.getAll())
        initAndroidLogging();
        enableEdgeToEdge()
        setContent {
            SporaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Share", "Connect")

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> ShareScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
            1 -> ConnectScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun ShareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ShareState.uiState.collectAsState()
    var showLabelDialog by remember { mutableStateOf(false) }
    var pendingShareConnectionId by remember { mutableStateOf<String?>(null) }

    // Auto-trigger share intent when a newly created connection gets its URL
    LaunchedEffect(pendingShareConnectionId, uiState.activeShares) {
        val pendingId = pendingShareConnectionId ?: return@LaunchedEffect
        val url = uiState.activeShares[pendingId]?.url ?: return@LaunchedEffect
        pendingShareConnectionId = null
        shareUrl(context, url)
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Share your connection")

        uiState.connections.forEach { connection ->
            val isActive = connection.id in uiState.activeShares
            val isStarting = connection.id in uiState.startingIds
            val error = uiState.errors[connection.id]
            val url = uiState.activeShares[connection.id]?.url

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = connection.label,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    when {
                        isStarting -> Text(
                            text = "Starting\u2026",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        isActive && url != null -> Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        error != null -> Text(
                            text = "Error: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (isActive && url != null) {
                    IconButton(onClick = { shareUrl(context, url) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share link")
                    }
                }
                Switch(
                    checked = isActive || isStarting,
                    onCheckedChange = { enabled ->
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
                    enabled = !isStarting,
                )
            }
        }

        if (uiState.connections.isNotEmpty()) {
            HorizontalDivider()
        }

        Button(onClick = { showLabelDialog = true }) {
            Text("Share")
        }
    }

    if (showLabelDialog) {
        NewShareDialog(
            onDismiss = { showLabelDialog = false },
            onConfirm = { label ->
                showLabelDialog = false
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
        )
    }
}

@Composable
fun NewShareDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New shared connection") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                placeholder = { Text("e.g., name of who you're sharing with") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.isNotBlank(),
            ) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun shareUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

@Composable
fun ConnectScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ConnectState.uiState.collectAsState()
    var url by rememberSaveable { mutableStateOf("spora://188.166.74.116:2334/abcdef") }
    // Pending URL to connect after VPN permission is granted
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingUrl?.let { ConnectVpnService.connect(context, it) }
        }
        pendingUrl = null
    }

    fun startVpnConnection(connectUrl: String) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingUrl = connectUrl
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            ConnectVpnService.connect(context, connectUrl)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Connect to a peer")

        when {
            uiState.isConnecting -> Text(text = "Status: connecting\u2026")
            uiState.isConnected -> Text(text = "Status: connected")
            uiState.errorMessage != null -> Text(text = "Status: error: ${uiState.errorMessage}")
            else -> Text(text = "Status: disconnected")
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Share URL") },
            placeholder = { Text("Enter the share URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isConnecting && !uiState.isConnected,
            singleLine = true,
        )

        Button(
            onClick = { startVpnConnection(url) },
            enabled = url.isNotBlank() && !uiState.isConnecting && !uiState.isConnected,
        ) {
            Text("Connect")
        }

        if (uiState.isConnected || uiState.isConnecting) {
            Button(onClick = { ConnectVpnService.disconnect(context) }) {
                Text("Disconnect")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SporaTheme {
        MainScreen()
    }
}
