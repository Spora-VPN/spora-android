package net.spora.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.spora.android.ui.ShareScreen
import net.spora.android.ui.SporaIcons
import net.spora.android.ui.UseScreen
import net.spora.android.ui.theme.Slate
import net.spora.android.ui.theme.SporaTheme
import net.spora.android.ui.theme.TextLight
import net.spora.android.ui.theme.TextLightMuted
import net.spora.android.ui.theme.TextMain
import uniffi.spora_ffi.initAndroidLogging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedConnectionStore.init(this)
        UseConnectionStore.init(this)
        ShareState.loadConnections(SharedConnectionStore.getAll())
        ConnectState.loadConnections(UseConnectionStore.getAll())
        initAndroidLogging()
        enableEdgeToEdge()
        setContent {
            SporaTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
                1 -> UseScreen()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Slate)
                    .drawBehind {
                        // Simple person icon shape
                        val cx = size.width / 2
                        val cy = size.height * 0.35f
                        val headR = size.width * 0.15f
                        drawCircle(
                            color = Color(0xFFD2D6CC),
                            radius = headR,
                            center = Offset(cx, cy),
                        )
                        drawOval(
                            color = Color(0xFFD2D6CC),
                            topLeft = Offset(cx - size.width * 0.25f, cy + headR * 0.8f),
                            size = Size(size.width * 0.5f, size.height * 0.35f),
                        )
                    },
            )

            Text(
                text = "SPORA",
                style = MaterialTheme.typography.titleMedium,
                color = TextMain,
            )
        }

        // 3x3 dot grid menu
        MenuGrid()
    }
}

@Composable
private fun MenuGrid() {
    val dotColor = TextMain
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(dotColor, CircleShape),
                    )
                }
            }
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
