package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.*

/** Explain network exposure before the user enables sharing, not afterwards. */
@Composable
fun SharePanel(state: ConnectionState, profile: ConnectionProfile, onProfileChange: (ConnectionProfile) -> Unit, modifier: Modifier = Modifier) {
    val shareActive by ShareBridge.active.collectAsStateWithLifecycle()
    val socksPort by ShareBridge.socksPort.collectAsStateWithLifecycle()
    val httpPort by ShareBridge.httpPort.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = state.isConnected
    val sharing = profile.lanShare
    val lanIp by produceState<String?>(null, connected, sharing, lifecycle) {
        value = null
        if (connected && sharing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = withContext(Dispatchers.IO) { ShareBridge.lanAddress() }; delay(5_000L) }
        }
    }
    LaunchedEffect(connected, sharing, shareActive) {
        if (connected && sharing && !shareActive) withContext(Dispatchers.IO) { ShareBridge.start() }
    }
    val live = sharing && connected && shareActive && lanIp != null && (httpPort != null || socksPort != null)
    ControlSection(stringResource(R.string.share_title), stringResource(R.string.share_subtitle), Icons.Rounded.WifiTethering, modifier) {
        NoticeBar(stringResource(R.string.share_warning), tone = MaterialTheme.colorScheme.error, icon = Icons.Rounded.Warning)
        Spacer(Modifier.height(12.dp))
        SwitchRow(stringResource(R.string.share_toggle), stringResource(R.string.share_toggle_desc), sharing, enabled = true, onChange = { on ->
            onProfileChange(profile.copy(lanShare = on))
            if (connected) { if (on) ShareBridge.start() else ShareBridge.stop() }
        })
        Hairline()
        if (sharing) {
            Spacer(Modifier.height(24.dp))
            val address = lanIp
            when {
                !connected -> NoticeBar(stringResource(R.string.share_need_connect), icon = Icons.Rounded.WifiTethering)
                address == null -> NoticeBar(stringResource(R.string.share_need_wifi), icon = Icons.Rounded.WifiTethering)
                live -> {
                    SectionTitle(stringResource(R.string.share_howto))
                    val host = if (':' in address) "[$address]" else address
                    httpPort?.let { ValueRow(stringResource(R.string.share_http_label), "$host:$it") }
                    socksPort?.let { ValueRow(stringResource(R.string.share_socks_label), "$host:$it") }
                    if (httpPort == null || socksPort == null) Hint(stringResource(R.string.share_starting))
                }
                else -> NoticeBar(stringResource(R.string.share_starting), icon = Icons.Rounded.WifiTethering)
            }
        }
    }
}
