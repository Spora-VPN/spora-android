package net.spora.android

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.spora.android.ui.theme.SporaTheme
import uniffi.spora_ffi.initAndroidLogging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Share your connection")

        when {
            uiState.isStarting -> Text(text = "Status: starting…")
            uiState.isRunning -> Text(text = "Status: running")
            uiState.errorMessage != null -> Text(text = "Status: error: ${uiState.errorMessage}")
            else -> Text(text = "Status: idle")
        }

        if (uiState.url != null) {
            Text(text = "URL:\n${uiState.url}")
        }

        Button(
            onClick = { ShareForegroundService.start(context) },
            enabled = !uiState.isStarting && !uiState.isRunning,
        ) {
            Text("Start")
        }

        if (uiState.isRunning || uiState.isStarting) {
            Button(onClick = { ShareForegroundService.stop(context) }) {
                Text("Stop")
            }
        }
    }
}

@Composable
fun ConnectScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ConnectState.uiState.collectAsState()
    var url by rememberSaveable { mutableStateOf("spora://188.166.74.116:2335/abcdef") }

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
            uiState.isConnecting -> Text(text = "Status: connecting…")
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
