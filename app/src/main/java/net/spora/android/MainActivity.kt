package net.spora.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.spora.android.ui.theme.SporaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SporaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ShareScreen(
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
fun ShareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiState by ShareState.uiState.collectAsState()

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Spora network sharing")

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

@Preview(showBackground = true)
@Composable
fun ShareScreenPreview() {
    SporaTheme {
        ShareScreen()
    }
}
