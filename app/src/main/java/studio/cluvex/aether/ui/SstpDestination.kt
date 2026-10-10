package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.cluvex.aether.R
import studio.cluvex.aether.core.SstpConfig
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.engine.ProfileEdit

/**
 * The SSTP server settings, shown under the chain picker while the SSTP chain
 * is selected, and the VPN Gate browser as a full-screen dialog over them.
 *
 * Picking a relay keeps the user's own options (verify, SNI, MRU/MTU) and only
 * swaps the server, through [toSstpConfig] on the current config.
 */
@Composable
internal fun SstpDestination(
    config: SstpConfig,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) browsing = false }

    SstpSettingsPanel(
        config = config,
        onConfigChange = { changed -> edit { copy(sstpConfig = changed) } },
        enabled = enabled,
        onBrowseVpnGate = { if (enabled) browsing = true },
    )
    Spacer(Modifier.height(8.dp))
    Hint(stringResource(R.string.sstp_packet_tunnel_note))

    if (browsing) {
        Dialog(
            onDismissRequest = { browsing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            VpnGateScreen(
                onServerSelected = { server ->
                    edit { copy(sstpConfig = server.toSstpConfig(sstpConfig)) }
                    browsing = false
                },
                onBack = { browsing = false },
                selectedHostname = config.hostname.takeIf { it.isNotBlank() },
            )
        }
    }
}
