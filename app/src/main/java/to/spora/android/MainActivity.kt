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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlin.math.max
import kotlinx.coroutines.launch
import to.spora.android.ui.DotState
import to.spora.android.ui.FeedbackModalContent
import to.spora.android.ui.ModalOverlay
import to.spora.android.ui.ShareScreen
import to.spora.android.ui.SmallIconButton
import to.spora.android.ui.SporaIcons
import to.spora.android.ui.StatusDot
import to.spora.android.ui.UseScreen
import to.spora.android.ui.theme.SporaTheme
import to.spora.android.ui.theme.SurfaceNav
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextOnDark
import to.spora.android.ui.theme.TextOnDarkMuted
import to.spora.android.ui.theme.TextPrimary
import uniffi.spora_ffi.initAndroidLogging

class MainActivity : ComponentActivity() {
    private var deepLinkUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Brand surface instead of a white flash before Compose draws
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SharedConnectionStore.init(this)
        UseConnectionStore.init(this)
        ShareState.loadConnections(SharedConnectionStore.getAll())
        ConnectState.loadConnections(UseConnectionStore.getAll())
        initAndroidLogging()
        requestNotificationPermission()
        // Only on a fresh launch: re-running the deep link on every recreation
        // (rotation, theme change) would re-trigger a disconnect/reconnect or
        // reopen the save modal
        if (savedInstanceState == null) {
            handleDeepLink(intent)
        }
        // The UI is always light (sage) at the top and always dark (pine)
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
        // Keep the activity's intent current so a recreation doesn't replay
        // the original launch link instead of the latest one
        setIntent(intent)
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
    var showFeedbackModal by rememberSaveable { mutableStateOf(false) }

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
        AppHeader(onFeedbackClick = { showFeedbackModal = true })

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

    ModalOverlay(
        visible = showFeedbackModal,
        onDismiss = { showFeedbackModal = false },
    ) {
        FeedbackModalContent(onDismiss = { showFeedbackModal = false })
    }
}

private data class HeaderStatusRow(
    val dot: DotState,
    val direction: String?, // "↑" share / "↓" use — semantic, echoed in mono
    val text: String,
)

@Composable
private fun AppHeader(onFeedbackClick: () -> Unit) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val shareState by ShareState.uiState.collectAsState()
    val connectState by ConnectState.uiState.collectAsState()

    val activeShareNames = shareState.activeShares.keys.mapNotNull { id ->
        shareState.connections.find { it.id == id }?.label
    }
    val activeUseConnection = connectState.activeConnectionId?.let { id ->
        connectState.savedConnections.find { it.id == id }
    }

    // Dual-role shows BOTH rows (combined, §11.5) — connection first.
    val rows = mutableListOf<HeaderStatusRow>()
    if (activeUseConnection != null && connectState.isConnected) {
        rows += HeaderStatusRow(
            DotState.Active,
            "↓",
            stringResource(R.string.header_status_connected, activeUseConnection.label.uppercase()),
        )
    } else if (activeUseConnection != null && connectState.isConnecting) {
        rows += HeaderStatusRow(
            DotState.Pending,
            "↓",
            stringResource(R.string.header_status_connecting, activeUseConnection.label.uppercase()),
        )
    }
    if (activeShareNames.isNotEmpty()) {
        rows += HeaderStatusRow(
            DotState.Active,
            "↑",
            stringResource(
                R.string.header_status_sharing,
                activeShareNames.joinToString(", ") { it.uppercase() },
            ),
        )
    } else if (shareState.startingIds.isNotEmpty()) {
        rows += HeaderStatusRow(
            DotState.Pending,
            "↑",
            stringResource(R.string.header_status_starting),
        )
    }
    if (rows.isEmpty()) {
        rows += HeaderStatusRow(DotState.None, null, stringResource(R.string.header_status_offline))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = statusBarTop + 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = stringResource(R.string.header_logo_content_desc),
                modifier = Modifier.height(32.dp),
            )

            Column {
                Text(
                    text = "SPORA",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                rows.forEachIndexed { i, row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = if (i == 0) 4.dp else 3.dp),
                    ) {
                        StatusDot(row.dot)
                        if (row.direction != null) {
                            Text(
                                text = row.direction,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                        }
                        Text(
                            text = row.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        SmallIconButton(
            icon = SporaIcons.Feedback,
            contentDescription = stringResource(R.string.feedback_button_content_desc),
            onClick = onFeedbackClick,
            tint = TextPrimary,
            iconSize = 19.dp,
        )
    }
}

@Composable
private fun BottomNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = max(navBarBottom.value + 6, 18f).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(SurfaceNav)
            .padding(start = 24.dp, end = 24.dp, top = 13.dp, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            icon = SporaIcons.NavShare,
            label = stringResource(R.string.tab_share),
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f),
        )
        NavItem(
            icon = SporaIcons.NavUse,
            label = stringResource(R.string.tab_use),
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) TextOnDark else TextOnDarkMuted

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp)
            .alpha(if (selected) 1f else 0.5f),
    ) {
        Icon(
            imageVector = icon,
            // The label Text below already names the tab for TalkBack
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = tint,
        )
    }
}
