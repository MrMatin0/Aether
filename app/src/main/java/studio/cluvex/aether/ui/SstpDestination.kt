package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import studio.cluvex.aether.model.SstpConfig
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.engine.ProfileEdit

/**
 * The SSTP server settings, shown under the chain picker while the SSTP chain
 * is selected, and the VPN Gate browser as a full-screen dialog over them.
 *
 * Picking a relay keeps the user's own options (verify, SNI, MRU/MTU) and only
 * swaps the server, through [toSstpConfig] on the current config.
 *
 * THE BROWSER IS NOT PART OF THE LOCK. It used to be closed the moment the
 * tunnel came up and could not be reopened until it went down again, so a
 * connected user could not even refresh the relay list or test a relay -
 * none of which touches the live session. Browsing, refreshing and testing
 * now work in every state; only USING a relay writes the profile, so only
 * that waits for an idle tunnel ([enabled]), and the screen says so.
 */
@Composable
internal fun SstpDestination(
    config: SstpConfig,
    enabled: Boolean,
    edit: ProfileEdit,
) {
    var browsing by rememberSaveable { mutableStateOf(false) }

    SstpSettingsPanel(
        config = config,
        onConfigChange = { changed -> edit { copy(sstpConfig = changed) } },
        enabled = enabled,
        onBrowseVpnGate = { browsing = true },
    )
    Spacer(Modifier.height(8.dp))
    Hint(stringResource(R.string.sstp_packet_tunnel_note))

    if (browsing) {
        VpnGateBrowserDialog(config = config, enabled = enabled, edit = edit, onDismiss = { browsing = false })
    }
}

/**
 * The VPN Gate relay list (country, sort by country or ping, refresh, probe)
 * as a full-screen dialog. Shared by the SSTP settings page and the SSTP sheet
 * of the connection tab, so there is exactly one relay browser in the app.
 *
 * Using a relay writes the profile through [edit] and closes the dialog; it is
 * refused while [enabled] is false, like on the settings page.
 */
@Composable
internal fun VpnGateBrowserDialog(
    config: SstpConfig,
    enabled: Boolean,
    edit: ProfileEdit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        VpnGateScreen(
            onServerSelected = { server ->
                if (enabled) {
                    edit { copy(sstpConfig = server.toSstpConfig(sstpConfig)) }
                    onDismiss()
                }
            },
            onBack = onDismiss,
            selectedHostname = config.hostname.takeIf { it.isNotBlank() },
            canSelect = enabled,
        )
    }
}
