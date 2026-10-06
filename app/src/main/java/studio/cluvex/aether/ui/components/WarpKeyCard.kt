package studio.cluvex.aether.ui.components

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.WarpKeyFetcher
import studio.cluvex.aether.core.WarpKeyPhase
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * Manual WARP key request on the diagnostics tab: one tile per protocol that
 * needs an identity. See [WarpKeyFetcher] for what a tap does. The pill on the
 * header lights up while another VPN carries this device's traffic.
 */
@Composable
internal fun WarpKeyCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    val keys by WarpKeyFetcher.state.collectAsStateWithLifecycle()
    val connection by AetherController.state.collectAsStateWithLifecycle()
    val vpnActive = rememberVpnActive()
    val allowed = WarpKeyFetcher.canRun(connection)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(accents.brand, accents.protected))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.VpnKey, null, Modifier.size(17.dp), accents.onBrand)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.warp_key_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                VpnPill(vpnActive)
            }
            Spacer(Modifier.height(12.dp))
            WarpKeyFetcher.PROTOCOLS.chunked(2).forEachIndexed { row, pair ->
                if (row > 0) Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { protocol ->
                        val phase = keys.phaseOf(protocol)
                        val running = phase == WarpKeyPhase.RUNNING
                        KeyTile(
                            label = tileLabel(protocol),
                            phase = phase,
                            enabled = running || (allowed && !keys.busy),
                            onClick = {
                                if (running) WarpKeyFetcher.cancel() else WarpKeyFetcher.start(context, protocol)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Technical names, deliberately not localised (same rule as SettingsPage.tags). */
private fun tileLabel(protocol: Protocol): String = when (protocol) {
    Protocol.MASQUE -> "MASQUE"
    Protocol.MIM -> "MiM"
    Protocol.WIREGUARD -> "WireGuard"
    Protocol.GOOL -> "Gool"
    Protocol.AUTO -> "Auto"
}

@Composable
private fun KeyTile(
    label: String,
    phase: WarpKeyPhase,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val reduced = LocalReducedMotion.current
    val quick = aetherDuration(AetherDur.Quick)
    val running = phase == WarpKeyPhase.RUNNING

    val tone = when (phase) {
        WarpKeyPhase.IDLE -> accents.brand
        WarpKeyPhase.RUNNING -> accents.working
        WarpKeyPhase.DONE -> accents.protected
        WarpKeyPhase.FAILED -> accents.failed
    }
    val fill by animateColorAsState(
        targetValue = when (phase) {
            WarpKeyPhase.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
            WarpKeyPhase.RUNNING -> accents.workingWash
            WarpKeyPhase.DONE -> accents.protectedWash
            WarpKeyPhase.FAILED -> accents.failedWash
        },
        animationSpec = tween(quick, easing = AetherEaseOut),
        label = "key-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (phase == WarpKeyPhase.IDLE) accents.cardBorder else tone.copy(alpha = 0.7f),
        animationSpec = tween(quick, easing = AetherEaseOut),
        label = "key-edge",
    )
    // A beam that circles the tile while the engine is registering. Only
    // composed while running, so idle tiles cost no frames.
    val angle: State<Float>? = if (running && !reduced) {
        rememberInfiniteTransition(label = "key-sweep").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
            label = "key-angle",
        )
    } else {
        null
    }

    val status = stringResource(
        when (phase) {
            WarpKeyPhase.IDLE -> R.string.warp_key_idle
            WarpKeyPhase.RUNNING -> R.string.warp_key_running
            WarpKeyPhase.DONE -> R.string.warp_key_done
            WarpKeyPhase.FAILED -> R.string.warp_key_failed
        },
    )
    val description = if (running) {
        stringResource(R.string.warp_key_cancel, label)
    } else {
        stringResource(R.string.warp_key_get, label)
    }

    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(14.dp))
            .drawBehind {
                val sweep = angle
                if (sweep == null) {
                    drawRect(edge)
                } else {
                    drawRect(tone.copy(alpha = 0.18f))
                    rotate(sweep.value) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(Color.Transparent, Color.Transparent, tone, Color.Transparent),
                            ),
                            radius = size.maxDimension,
                        )
                    }
                }
            }
            .padding(1.5.dp)
            .clip(RoundedCornerShape(12.5.dp))
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 56.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyGlyph(phase, tone)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (phase == WarpKeyPhase.IDLE) MaterialTheme.colorScheme.onSurfaceVariant else tone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun KeyGlyph(phase: WarpKeyPhase, tone: Color) {
    val accents = LocalAetherAccents.current
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (phase == WarpKeyPhase.DONE) tone else tone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            WarpKeyPhase.IDLE -> Icon(Icons.Rounded.Key, null, Modifier.size(16.dp), tone)
            WarpKeyPhase.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tone,
                strokeWidth = 2.dp,
            )
            WarpKeyPhase.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), accents.onProtected)
            WarpKeyPhase.FAILED -> Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp), tone)
        }
    }
}

@Composable
private fun VpnPill(active: Boolean) {
    val accents = LocalAetherAccents.current
    val quick = aetherDuration(AetherDur.Quick)
    val tone by animateColorAsState(
        targetValue = if (active) accents.protected else accents.neutral,
        animationSpec = tween(quick, easing = AetherEaseOut),
        label = "vpn-tone",
    )
    val fill by animateColorAsState(
        targetValue = if (active) accents.protectedWash else Color.Transparent,
        animationSpec = tween(quick, easing = AetherEaseOut),
        label = "vpn-fill",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (active) tone.copy(alpha = 0.5f) else accents.cardBorder, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.warp_key_vpn),
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
    }
}

/** Whether the default network of this app currently runs through a VPN. */
@Composable
private fun rememberVpnActive(): Boolean {
    val context = LocalContext.current
    var active by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
        active = runCatching {
            manager?.let { cm ->
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }.getOrDefault(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                active = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }

            override fun onLost(network: Network) {
                active = false
            }
        }
        val registered = manager?.let { cm ->
            runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        } == true
        onDispose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }
    return active
}
