package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionRule
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.components.ValueRow

/**
 * Turn the phone into a gateway for a laptop or a second phone on the same
 * Wi-Fi / hotspot.
 *
 * WHY IT IS NOT COLLAPSED ANY MORE: this panel's whole job is to report state —
 * is the bridge up, on which address, and if not, why not. Hiding that behind a
 * chevron meant the answer to "nothing is sharing" was two taps away and looked
 * like a blank row until you expanded it. Each of the four possible states is
 * now said out loud.
 *
 * The self-healing start() and the immediate start/stop on toggle are unchanged;
 * start() is async and thread-safe, so it can never block the UI.
 */
@Composable
fun SharePanel(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shareActive by ShareBridge.active.collectAsState()
    // The ACTUAL bound ports (null while a listener is still binding), so what
    // is on screen always matches what the bridge listens on.
    val socksPort by ShareBridge.socksPort.collectAsState()
    val httpPort by ShareBridge.httpPort.collectAsState()

    // Re-resolve the LAN address whenever sharing or connectivity flips.
    val lanIp = remember(shareActive, state.isConnected, profile.lanShare) {
        ShareBridge.lanAddress()
    }

    LaunchedEffect(state.isConnected, profile.lanShare, shareActive) {
        if (state.isConnected && profile.lanShare && !shareActive) {
            withContext(Dispatchers.IO) { ShareBridge.start() }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionRule(label = stringResource(R.string.share_title))
        Text(
            text = stringResource(R.string.share_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Hairline(alpha = 0.55f)
        SwitchRow(
            title = stringResource(R.string.share_toggle),
            description = stringResource(R.string.share_toggle_desc),
            checked = profile.lanShare,
            enabled = true,
            onChange = { on ->
                onProfileChange(profile.copy(lanShare = on))
                // Take effect immediately for the current session; the service
                // also honours the flag on connect.
                if (state.isConnected) {
                    if (on) ShareBridge.start() else ShareBridge.stop()
                }
            },
        )
        Hairline(alpha = 0.55f)

        AnimatedVisibility(visible = profile.lanShare) {
            Column {
                Spacer(Modifier.height(14.dp))
                when {
                    !state.isConnected -> NoticeBar(
                        text = stringResource(R.string.share_need_connect),
                        icon = Icons.Rounded.WifiTethering,
                    )
                    lanIp == null -> NoticeBar(
                        text = stringResource(R.string.share_need_wifi),
                        icon = Icons.Rounded.WifiTethering,
                    )
                    shareActive -> {
                        Column {
                            Hint(stringResource(R.string.share_howto))
                            Spacer(Modifier.height(6.dp))
                            ValueRow(
                                label = stringResource(R.string.share_http_label),
                                value = "$lanIp:${httpPort ?: ShareBridge.HTTP_SHARE_PORT}",
                            )
                            ValueRow(
                                label = stringResource(R.string.share_socks_label),
                                value = "$lanIp:${socksPort ?: ShareBridge.SOCKS_SHARE_PORT}",
                            )
                            Spacer(Modifier.height(12.dp))
                            NoticeBar(
                                text = stringResource(R.string.share_warning),
                                tone = MaterialTheme.colorScheme.error,
                                icon = Icons.Rounded.Warning,
                            )
                        }
                    }
                    // Binding, or a bind failed: never leave the panel blank.
                    else -> NoticeBar(
                        text = stringResource(R.string.share_starting),
                        icon = Icons.Rounded.WifiTethering,
                    )
                }
            }
        }
    }
}
