package to.spora.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.spora.android.Diagnostics
import to.spora.android.R
import to.spora.android.RelaySettings
import to.spora.android.RelaySettingsState
import to.spora.android.ui.theme.BorderHairline
import to.spora.android.ui.theme.ColorError
import to.spora.android.ui.theme.SurfaceCard
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextPrimary

/** The settings pages: the list, and a page per section. */
enum class SettingsPage(@StringRes val title: Int) {
    LIST(R.string.settings_title),
    RELAYS(R.string.settings_relays_header),
    TELEMETRY(R.string.settings_telemetry_header),
}

/** Keeps the open pages across a recreation, by name. */
val SettingsStackSaver: Saver<List<SettingsPage>, String> = Saver(
    save = { stack -> stack.joinToString(",") { it.name } },
    restore = { saved ->
        saved.split(',').mapNotNull { name -> SettingsPage.entries.find { it.name == name } }
    },
)

/**
 * The settings, over the main screen while [stack] is not empty; its last
 * page is shown, and back drops it. A section opened from elsewhere (OPEN
 * SETTINGS under a share error) is the whole stack, so back returns there.
 */
@Composable
fun SettingsPages(
    stack: List<SettingsPage>,
    onStackChange: (List<SettingsPage>) -> Unit,
    onTelemetryRequired: () -> Unit,
) {
    val page = stack.lastOrNull() ?: return
    // Each page its own scroll position and saved state
    key(page) {
        FullScreenPage(
            title = stringResource(page.title),
            onBack = { onStackChange(stack.dropLast(1)) },
        ) {
            when (page) {
                SettingsPage.LIST -> SettingsList(onOpen = { onStackChange(stack + it) })
                SettingsPage.RELAYS -> RelaySettingsContent()
                SettingsPage.TELEMETRY -> TelemetrySettingsContent(onRequired = onTelemetryRequired)
            }
        }
    }
}

@Composable
private fun SettingsList(onOpen: (SettingsPage) -> Unit) {
    val relays by RelaySettings.state.collectAsState()
    val telemetry by Diagnostics.status.collectAsState()
    val telemetryUnavailable by Diagnostics.unavailable.collectAsState()

    LaunchedEffect(Unit) {
        Diagnostics.refresh()
    }

    val currentTelemetry = telemetry
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionRow(
            icon = SporaIcons.Relays,
            title = stringResource(R.string.settings_relays_label),
            summary = relaysSummary(relays),
            summaryIsError = !relays.hasRelays,
            onClick = { onOpen(SettingsPage.RELAYS) },
        )
        SectionRow(
            icon = SporaIcons.Diagnostics,
            title = stringResource(R.string.settings_telemetry_label),
            summary = when {
                currentTelemetry != null -> stringResource(
                    if (currentTelemetry.uploadEnabled) R.string.settings_telemetry_on
                    else R.string.settings_telemetry_off,
                )
                telemetryUnavailable -> stringResource(R.string.settings_telemetry_unavailable)
                // The service is still starting
                else -> null
            },
            onClick = { onOpen(SettingsPage.TELEMETRY) },
        )
    }
}

@Composable
private fun relaysSummary(settings: RelaySettingsState): String = when {
    settings.useSpora && settings.own.isEmpty() ->
        stringResource(R.string.settings_relays_summary_spora)
    settings.useSpora ->
        stringResource(R.string.settings_relays_summary_spora_own, settings.own.size)
    settings.own.isNotEmpty() ->
        stringResource(R.string.settings_relays_summary_own, settings.own.size)
    else -> stringResource(R.string.settings_relays_summary_none)
}

// A section of the settings: its icon, name and state at a glance
@Composable
private fun SectionRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    summaryIsError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (summaryIsError) ColorError else TextMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            imageVector = SporaIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}
