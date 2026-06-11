package to.spora.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.launch
import to.spora.android.ui.ShareScreen
import to.spora.android.ui.SporaIcons
import to.spora.android.ui.UseScreen
import to.spora.android.ui.theme.Slate
import to.spora.android.ui.theme.SporaTheme
import to.spora.android.ui.theme.TextLight
import to.spora.android.ui.theme.TextLightMuted
import to.spora.android.ui.theme.TextMain
import to.spora.android.ui.theme.TextMuted
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
        requestNotificationPermission()
        handleDeepLink(intent)
        // The UI is always light (sage) at the top and always dark (slate)
        // behind the gesture area; without explicit styles enableEdgeToEdge
        // follows the *system* theme, making status icons unreadable in
        // system dark mode and the nav pill low-contrast in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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

    // Both services communicate status (and their only error/stop affordances)
    // through notifications, which Android 13+ suppresses until this is granted.
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
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
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(deepLinkUrl) {
        if (deepLinkUrl != null) {
            pagerState.animateScrollToPage(1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppHeader()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> ShareScreen()
                1 -> UseScreen(
                    initialUrl = deepLinkUrl,
                    onInitialUrlConsumed = onDeepLinkConsumed,
                )
            }
        }

        BottomNav(
            selectedTab = pagerState.currentPage,
            onTabSelected = { coroutineScope.launch { pagerState.animateScrollToPage(it) } },
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
            statusText = stringResource(R.string.header_status_connected_via, activeUseConnection.label)
        }
        connectState.isConnecting && activeUseConnection != null -> {
            statusDotColor = Color(0xFFFFC107)
            statusText = stringResource(R.string.header_status_connecting_to, activeUseConnection.label)
        }
        activeShareNames.isNotEmpty() -> {
            statusDotColor = Color(0xFF4CAF50)
            statusText = stringResource(R.string.header_status_sharing_with, activeShareNames.joinToString(", "))
        }
        shareState.startingIds.isNotEmpty() -> {
            statusDotColor = Color(0xFFFFC107)
            statusText = stringResource(R.string.header_status_starting)
        }
        else -> {
            statusDotColor = TextMuted
            statusText = stringResource(R.string.header_status_offline)
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
                contentDescription = stringResource(R.string.header_logo_content_desc),
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
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = max(navBarBottom.value + 8, 32f).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            )
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Slate)
            .padding(top = 20.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            icon = SporaIcons.Share,
            label = stringResource(R.string.tab_share),
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
        )
        NavItem(
            icon = SporaIcons.CloudDownload,
            label = stringResource(R.string.tab_use),
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
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            // The label Text below already names the tab for TalkBack
            contentDescription = null,
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
