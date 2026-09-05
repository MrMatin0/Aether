package studio.cluvex.aether.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.TrafficMonitor
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import java.util.Locale

/** Focused presentation rewrite. Controller, VPN permission and profile flows stay untouched. */
@Composable
fun HomeScreen(
    state: ConnectionState,
    profile: ConnectionProfile,
    connectedSince: Long?,
    ipInfo: IpEndpoint?,
    ipLoading: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { runCatching { SettingsPage.valueOf(it) }.getOrNull() }
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error
    BackHandler(destination != 0 || page != null) { if (page != null) pageName = null else destination = 0 }
    val haptics = LocalHapticFeedback.current
    val action = stringResource(when {
        state.isConnected -> R.string.action_disconnect
        state.isBusy -> R.string.action_cancel
        state is ConnectionState.Error -> R.string.action_retry
        else -> R.string.action_connect
    })
    val activate = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggleConnection() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) {
                    IconButton(onClick = { pageName = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.passage_back)) }
                    Text(settingsPageTitle(page), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                } else Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                LanguageToggle(accent = accents.brand)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    page != null -> SettingsPageBody(page, state, profile, onProfileChange, editable)
                    destination == 2 -> SettingsHub(!editable, { pageName = it.name }, modifier = Modifier.fillMaxSize())
                    destination == 1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.nav_diagnostics), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp)); DiagnosticsPanel(alwaysExpanded = true, consoleMaxHeight = 420.dp); Spacer(Modifier.height(24.dp))
                    }
                    else -> ConnectionHome(state, profile, connectedSince, ipInfo, ipLoading, activate)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                if (state.isConnected || state.isBusy) OutlinedButton(onClick = activate, enabled = state !is ConnectionState.Disconnecting, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action, style = MaterialTheme.typography.titleMedium) }
                else Button(onClick = activate, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(if (state is ConnectionState.Error) Icons.Rounded.Refresh else Icons.Rounded.Shield, null); Spacer(Modifier.width(8.dp)); Text(action, style = MaterialTheme.typography.titleMedium) }
                NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0), tonalElevation = 0.dp) {
                    listOf(R.string.nav_home, R.string.nav_diagnostics, R.string.nav_settings).forEachIndexed { i, label ->
                        NavigationBarItem(destination == i, { destination = i; pageName = null }, icon = { Icon(listOf(Icons.Rounded.Shield, Icons.Rounded.BugReport, Icons.Rounded.Settings)[i], null) }, label = { Text(stringResource(label)) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accents.brand, selectedTextColor = accents.brand, indicatorColor = accents.brandWash))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHome(state: ConnectionState, profile: ConnectionProfile, connectedSince: Long?, ipInfo: IpEndpoint?, ipLoading: Boolean, onPrimary: () -> Unit) {
    val accents = LocalAetherAccents.current
    val tone = when { state.isConnected -> accents.protected; state is ConnectionState.Error -> accents.failed; state.isBusy -> accents.working; else -> accents.neutral }
    val wash = when { state.isConnected -> accents.protectedWash; state is ConnectionState.Error -> accents.failedWash; state.isBusy -> accents.workingWash; else -> MaterialTheme.colorScheme.surfaceVariant }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(14.dp))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = wash, contentColor = tone, shape = RoundedCornerShape(24.dp)) { Text(stringResource(when { state.isConnected -> R.string.passage_verified; state is ConnectionState.Error -> R.string.pill_failed; state.isBusy -> R.string.pill_working; else -> R.string.pill_off }), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
            Spacer(Modifier.height(14.dp)); Text(title(state), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp)); Text(hint(state, profile), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        PassageMark(tone)
        if (state.isBusy) ConnectionSteps(state) else Text(stringResource(R.string.passage_route), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(22.dp))
        OutlinedButton(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.page_engine_title), style = MaterialTheme.typography.titleSmall); Text(scanLabel(profile.scanMode), style = MaterialTheme.typography.bodySmall) }; Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
        if (state.isConnected) SessionLedger(connectedSince, ipInfo, ipLoading) else if (state is ConnectionState.Idle) { Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.passage_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } else if (state is ConnectionState.Error) TextButton(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.passage_see_error)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun PassageMark(tone: Color) { val line = MaterialTheme.colorScheme.outlineVariant; Canvas(Modifier.padding(vertical = 12.dp).size(220.dp, 140.dp)) { val cx = size.width / 2; val base = size.height * .86f; val top = size.height * .48f; repeat(4) { i -> val r = size.width * .32f - size.width * .059f * i; val p = Path().apply { moveTo(cx-r, base); lineTo(cx-r, top); cubicTo(cx-r, top-r*.55f, cx-r*.55f, top-r, cx, top-r); cubicTo(cx+r*.55f, top-r, cx+r, top-r*.55f, cx+r, top); lineTo(cx+r, base) }; drawPath(p, if (i < 2) line else tone, style = Stroke(1.4.dp.toPx())) }; drawLine(line, Offset(size.width*.08f, base), Offset(size.width*.92f, base), 1.dp.toPx()); drawLine(tone, Offset(cx, base+3.dp.toPx()), Offset(cx, size.height), 1.4.dp.toPx(), cap = StrokeCap.Round) } }

@Composable private fun ConnectionSteps(state: ConnectionState) { val active = when (state) { is ConnectionState.Launching -> 0; is ConnectionState.Verifying -> 2; else -> 1 }; val labels = listOf(R.string.phase_engine, R.string.phase_tunnel, R.string.phase_verify, R.string.phase_ready); val a = LocalAetherAccents.current; Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) { labels.forEachIndexed { i, label -> val c = when { i < active -> a.protected; i == active -> a.working; else -> MaterialTheme.colorScheme.onSurfaceVariant }; Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (i < active) "✓" else "${i+1}", color = c, modifier = Modifier.width(32.dp)); Text(stringResource(label), color = c, modifier = Modifier.weight(1f)) } }; Text(stringResource(R.string.passage_scan_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }

@Composable private fun SessionLedger(connectedSince: Long?, ipInfo: IpEndpoint?, ipLoading: Boolean) { val meta by EngineMeta.state.collectAsStateWithLifecycle(); val ping by PingMonitor.state.collectAsStateWithLifecycle(); val traffic by TrafficMonitor.sample.collectAsStateWithLifecycle(); val scope = rememberCoroutineScope(); val ip = if (ipInfo?.viaTunnel == true) ipInfo.ip else if (ipLoading) stringResource(R.string.ip_checking) else stringResource(R.string.ip_unavailable); Column(Modifier.fillMaxWidth().padding(top = 24.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.passage_session), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Uptime(connectedSince) }; Spacer(Modifier.height(10.dp)); Ledger(stringResource(R.string.ip_server_label), ip); Ledger(stringResource(R.string.meta_protocol), meta.protocol ?: stringResource(R.string.ip_unavailable), false); Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.meta_latency), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); TextButton(enabled = !ping.running, onClick = { scope.launch { PingMonitor.pingOnce(true) } }) { Text(if (ping.ms >= 0) String.format(Locale.US, "%d ms", ping.ms) else stringResource(R.string.meta_latency_test), fontFamily = FontFamily.Monospace); Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)) } }; Ledger(stringResource(R.string.traffic_download), TrafficMonitor.formatBytes(if (traffic.live) traffic.downloadBytes else 0)); Ledger(stringResource(R.string.traffic_upload), TrafficMonitor.formatBytes(if (traffic.live) traffic.uploadBytes else 0)); if (!meta.endpoint.isNullOrBlank()) { var open by rememberSaveable { mutableStateOf(false) }; TextButton(onClick = { open = !open }) { Text(stringResource(R.string.passage_details)); Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }; if (open) Ledger(stringResource(R.string.meta_endpoint), meta.endpoint.orEmpty()) } } }

@Composable private fun Ledger(label: String, value: String, mono: Boolean = true) { Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(value, modifier = Modifier.weight(1.25f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono) FontFamily.Monospace else null, textDirection = TextDirection.Ltr)) }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }

@Composable private fun Uptime(since: Long?) { var now by remember(since) { mutableLongStateOf(SystemClock.elapsedRealtime()) }; LaunchedEffect(since) { if (since != null) while (true) { now = SystemClock.elapsedRealtime(); delay(1000L - ((now-since).coerceAtLeast(0) % 1000L)) } }; val s = if (since == null) 0 else ((now-since).coerceAtLeast(0)/1000); Text(if (since == null) "…" else String.format(Locale.US, "%02d:%02d:%02d", s/3600, (s%3600)/60, s%60), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun title(state: ConnectionState): String = stringResource(when (state) { is ConnectionState.Idle -> R.string.passage_ready; is ConnectionState.Launching -> R.string.state_launching; is ConnectionState.Connecting -> R.string.state_connecting; is ConnectionState.Verifying -> R.string.state_verifying; is ConnectionState.Connected -> R.string.passage_connected; is ConnectionState.Reconnecting -> R.string.state_reconnecting; is ConnectionState.Disconnecting -> R.string.state_disconnecting; is ConnectionState.Error -> R.string.passage_failed })
@Composable private fun hint(state: ConnectionState, profile: ConnectionProfile): String = when (state) { is ConnectionState.Idle -> stringResource(R.string.passage_start_hint); is ConnectionState.Connected -> stringResource(R.string.passage_connected_hint); is ConnectionState.Launching, is ConnectionState.Connecting -> stringResource(R.string.busy_hint, scanLabel(profile.scanMode)); is ConnectionState.Verifying -> stringResource(R.string.state_verify_hint); is ConnectionState.Reconnecting -> stringResource(R.string.reconnect_attempt, state.attempt, state.maxAttempts); is ConnectionState.Disconnecting -> stringResource(R.string.state_disconnecting); is ConnectionState.Error -> state.message }
