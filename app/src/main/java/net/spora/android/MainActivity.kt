package net.spora.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.spora.android.ui.ShareScreen
import net.spora.android.ui.SporaIcons
import net.spora.android.ui.UseScreen
import net.spora.android.ui.theme.Slate
import net.spora.android.ui.theme.SporaTheme
import net.spora.android.ui.theme.TextLight
import net.spora.android.ui.theme.TextLightMuted
import net.spora.android.ui.theme.TextMain
import net.spora.android.ui.theme.TextMuted
import uniffi.spora_ffi.initAndroidLogging

class MainActivity : ComponentActivity() {
    private var deepLinkUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedConnectionStore.init(this)
        UseConnectionStore.init(this)
        ShareState.loadConnections(SharedConnectionStore.getAll())
        ConnectState.loadConnections(UseConnectionStore.getAll())
        initAndroidLogging()
        handleDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            SporaTheme {
                MainScreen(
                    deepLinkUrl = deepLinkUrl.value,
                    onDeepLinkConsumed = { deepLinkUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { url ->
                deepLinkUrl.value = url
            }
        }
    }
}

@Composable
fun MainScreen(
    deepLinkUrl: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(deepLinkUrl) {
        if (deepLinkUrl != null) {
            selectedTab = 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppHeader()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (selectedTab) {
                0 -> ShareScreen()
                1 -> UseScreen(
                    initialUrl = deepLinkUrl,
                    onInitialUrlConsumed = onDeepLinkConsumed,
                )
            }
        }

        BottomNav(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        )
    }
}

@Composable
private fun AppHeader() {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val shareState by ShareState.uiState.collectAsState()
    val connectState by ConnectState.uiState.collectAsState()

    val activeShareNames = shareState.activeShares.keys.mapNotNull { id ->
        shareState.connections.find { it.id == id }?.label
    }
    val activeUseConnection = connectState.activeConnectionId?.let { id ->
        connectState.savedConnections.find { it.id == id }
    }

    val statusDotColor: Color
    val statusText: String

    when {
        connectState.isConnected && activeUseConnection != null -> {
            statusDotColor = Color(0xFF4CAF50)
            statusText = "Connected via ${activeUseConnection.label}"
        }
        connectState.isConnecting && activeUseConnection != null -> {
            statusDotColor = Color(0xFFFFC107)
            statusText = "Connecting to ${activeUseConnection.label}\u2026"
        }
        activeShareNames.isNotEmpty() -> {
            statusDotColor = Color(0xFF4CAF50)
            statusText = "Sharing with ${activeShareNames.joinToString(", ")}"
        }
        shareState.startingIds.isNotEmpty() -> {
            statusDotColor = Color(0xFFFFC107)
            statusText = "Starting\u2026"
        }
        else -> {
            statusDotColor = TextMuted
            statusText = "Offline"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = statusBarTop + 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = "Spora logo",
                modifier = Modifier.height(36.dp),
            )

            Text(
                text = "SPORA",
                style = MaterialTheme.typography.titleMedium,
                color = TextMain,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusDotColor, CircleShape),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BottomNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            )
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Slate)
            .padding(top = 20.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            icon = SporaIcons.Share,
            label = "SHARE",
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
        )
        NavItem(
            icon = SporaIcons.CloudDownload,
            label = "USE",
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) TextLight else TextLightMuted
    val iconAlpha = if (selected) 1f else 0.5f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = iconAlpha),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}
