package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import studio.cluvex.aether.R
import studio.cluvex.aether.core.VpnGateRepository
import studio.cluvex.aether.model.ProbeState
import studio.cluvex.aether.model.SstpProbe
import studio.cluvex.aether.model.VpnGateFilter
import studio.cluvex.aether.model.VpnGateServer
import studio.cluvex.aether.model.VpnGateSort
import studio.cluvex.aether.model.filteredAndSorted
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.IconBadge
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.StatTile
import studio.cluvex.aether.ui.components.normalizeTechnicalText
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseInOut
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/** Enough parallel probes to finish fast, few enough not to look like a port scan. */
private const val GATE_PROBE_PARALLELISM = 6

/** How many relays "Test top N" probes. */
private const val GATE_PROBE_BATCH = 20

/** Horizontal gutter of the list. Sticky controls span the full width. */
private val GATE_GUTTER = 20.dp

private sealed interface GateLoad {
    data object Loading : GateLoad
    data object Ready : GateLoad
    data class Failed(val message: String) : GateLoad
}

/**
 * VPN Gate relay browser.
 *
 * Layout, top to bottom: header (back, title, refresh) -> hero stats (relays,
 * countries, best measured SSTP latency) -> sticky controls (search, sort,
 * verified filter, country chips, "test top 20") -> relay cards -> a plain
 * note about who runs these relays.
 *
 * Every card shows flag, country, SSTP host, the user's OWN probe result (or
 * VPN Gate's ping, marked with ~), speed / sessions / uptime and a score bar
 * relative to the best relay in the list. Tapping a card expands its details;
 * "Test" runs a real TLS + SSTP handshake; "Use relay" hands it to the caller
 * (`server.toSstpConfig()` builds the config).
 *
 * The list keeps showing while a refresh runs, failed probes sink to the
 * bottom, items animate into their new order, and all motion respects the
 * system "remove animations" setting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VpnGateScreen(
    onServerSelected: (VpnGateServer) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** SSTP host of the relay currently in use, to highlight it. */
    selectedHostname: String? = null,
) {
    BackHandler(onBack = onBack)
    val accents = LocalAetherAccents.current
    val reduced = LocalReducedMotion.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf<List<VpnGateServer>>(emptyList()) }
    var load by remember { mutableStateOf<GateLoad>(GateLoad.Loading) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(VpnGateSort.SCORE) }
    var country by rememberSaveable { mutableStateOf("") }
    var verifiedOnly by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val probes = remember { mutableStateMapOf<String, SstpProbe>() }
    var batch by remember { mutableStateOf<Job?>(null) }
    var batchDone by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        load = GateLoad.Loading
        VpnGateRepository.fetchServers()
            .onSuccess { fetched ->
                servers = fetched
                load = GateLoad.Ready
            }
            .onFailure { error -> load = GateLoad.Failed(error.message ?: error.javaClass.simpleName) }
    }

    val criteria = VpnGateFilter(
        query = normalizeTechnicalText(query).trim(),
        countryCode = country,
        verifiedOnly = verifiedOnly,
    )
    val visible by remember(servers, criteria, sort, locale) {
        derivedStateOf {
            servers.filteredAndSorted(criteria, sort, probes) { code -> CountryFlags.displayName(code, locale, "") }
        }
    }
    val countries = remember(servers) {
        servers.groupingBy { it.countryCode }.eachCount().entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    val maxScore = remember(servers) { (servers.maxOfOrNull { it.score } ?: 1L).coerceAtLeast(1L) }
    val bestPing by remember {
        derivedStateOf {
            probes.values
                .filter { it.state == ProbeState.SSTP_OK && it.latencyMs >= 0 }
                .minOfOrNull { it.latencyMs }
        }
    }

    fun probeOne(server: VpnGateServer) {
        if (probes[server.id]?.state == ProbeState.RUNNING) return
        probes[server.id] = SstpProbe(ProbeState.RUNNING)
        scope.launch {
            try {
                probes[server.id] = VpnGateRepository.probe(server)
            } catch (e: CancellationException) {
                probes.remove(server.id)
                throw e
            }
        }
    }

    fun probeBatch(targets: List<VpnGateServer>) {
        batch?.cancel()
        batchDone = 0
        batchTotal = targets.size
        batch = scope.launch {
            val gate = Semaphore(GATE_PROBE_PARALLELISM)
            try {
                targets.map { server ->
                    launch {
                        gate.withPermit {
                            probes[server.id] = SstpProbe(ProbeState.RUNNING)
                            try {
                                probes[server.id] = VpnGateRepository.probe(server)
                            } catch (e: CancellationException) {
                                probes.remove(server.id)
                                throw e
                            }
                            batchDone += 1
                        }
                    }
                }.joinAll()
            } finally {
                if (batch === coroutineContext[Job]) batch = null
            }
        }
    }

    val current = load
    val loading = current is GateLoad.Loading
    Box(modifier.fillMaxSize()) {
        AmbientBackground(accent = accents.brand, active = servers.isNotEmpty())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            GateHeader(loading = loading, onBack = onBack, onRefresh = { reloadToken++ })
            when {
                servers.isEmpty() && current is GateLoad.Failed ->
                    GateError(message = current.message, onRetry = { reloadToken++ })
                servers.isEmpty() -> GateSkeleton()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading) {
                        item(key = "refreshing") {
                            LinearProgressIndicator(
                                Modifier
                                    .padding(horizontal = GATE_GUTTER)
                                    .fillMaxWidth()
                                    .clip(CircleShape),
                            )
                        }
                    }
                    if (current is GateLoad.Failed) {
                        item(key = "refresh-failed") {
                            NoticeBar(
                                text = stringResource(R.string.vpngate_refresh_failed),
                                modifier = Modifier.padding(horizontal = GATE_GUTTER),
                                tone = accents.failed,
                                icon = Icons.Rounded.CloudOff,
                            )
                        }
                    }
                    item(key = "hero") {
                        GateHero(
                            relays = servers.size,
                            countries = countries.size,
                            bestPing = bestPing,
                            modifier = Modifier.padding(horizontal = GATE_GUTTER),
                        )
                    }
                    stickyHeader(key = "controls") {
                        GateControls(
                            query = query,
                            onQuery = { query = it },
                            sort = sort,
                            onSort = { sort = it },
                            verifiedOnly = verifiedOnly,
                            onVerifiedOnly = { verifiedOnly = it },
                            countries = countries,
                            country = country,
                            onCountry = { country = it },
                            shown = visible.size,
                            batchRunning = batch != null,
                            batchDone = batchDone,
                            batchTotal = batchTotal,
                            onProbeBatch = { probeBatch(visible.take(GATE_PROBE_BATCH)) },
                            onStopBatch = {
                                batch?.cancel()
                                batch = null
                            },
                        )
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            GateEmpty(
                                onReset = {
                                    query = ""
                                    country = ""
                                    verifiedOnly = false
                                },
                                modifier = Modifier.padding(horizontal = GATE_GUTTER),
                            )
                        }
                    }
                    items(visible, key = { it.id }) { server ->
                        GateRelayCard(
                            server = server,
                            probe = probes[server.id],
                            maxScore = maxScore,
                            selected = selectedHostname != null &&
                                server.sstpHostname.equals(selectedHostname, ignoreCase = true),
                            expanded = expandedId == server.id,
                            onToggle = { expandedId = if (expandedId == server.id) null else server.id },
                            onProbe = { probeOne(server) },
                            onUse = { onServerSelected(server) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = if (reduced) null else tween<Float>(AetherDur.Quick),
                                    placementSpec = if (reduced) null else tween<IntOffset>(AetherDur.Base, easing = AetherEaseOut),
                                    fadeOutSpec = if (reduced) null else tween<Float>(AetherDur.Quick),
                                )
                                .padding(horizontal = GATE_GUTTER),
                        )
                    }
                    item(key = "privacy") {
                        NoticeBar(
                            text = stringResource(R.string.vpngate_privacy_note),
                            modifier = Modifier.padding(start = GATE_GUTTER, end = GATE_GUTTER, top = 8.dp),
                            tone = accents.working,
                            icon = Icons.Rounded.Info,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GateHeader(loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.vpngate_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.vpngate_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.vpngate_refresh))
            }
        }
    }
}

@Composable
private fun GateHero(relays: Int, countries: Int, bestPing: Int?, modifier: Modifier = Modifier) {
    AetherCard(modifier = modifier, padding = 20.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.vpngate_stat_relays),
                value = relays.toString(),
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Dns,
                tint = MaterialTheme.colorScheme.primary,
            )
            StatTile(
                label = stringResource(R.string.vpngate_stat_countries),
                value = countries.toString(),
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Public,
                tint = MaterialTheme.colorScheme.primary,
            )
            StatTile(
                label = stringResource(R.string.vpngate_stat_best_ping),
                value = if (bestPing == null) "\u2014" else "$bestPing ms",
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Speed,
                tint = if (bestPing == null) MaterialTheme.colorScheme.onSurfaceVariant else gateLatencyTone(bestPing),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GateControls(
    query: String,
    onQuery: (String) -> Unit,
    sort: VpnGateSort,
    onSort: (VpnGateSort) -> Unit,
    verifiedOnly: Boolean,
    onVerifiedOnly: (Boolean) -> Unit,
    countries: List<Pair<String, Int>>,
    country: String,
    onCountry: (String) -> Unit,
    shown: Int,
    batchRunning: Boolean,
    batchDone: Int,
    batchTotal: Int,
    onProbeBatch: () -> Unit,
    onStopBatch: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
            .padding(vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GATE_GUTTER),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.vpngate_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.vpngate_clear_search))
                    }
                }
            },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = accents.card,
                unfocusedContainerColor = accents.card,
                unfocusedBorderColor = accents.cardBorder,
            ),
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = GATE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(VpnGateSort.entries) { option ->
                val selected = option == sort
                FilterChip(
                    selected = selected,
                    onClick = { onSort(option) },
                    label = { Text(stringResource(option.labelRes())) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
            item {
                VerticalDivider(
                    Modifier
                        .height(24.dp)
                        .padding(horizontal = 4.dp),
                )
            }
            item {
                FilterChip(
                    selected = verifiedOnly,
                    onClick = { onVerifiedOnly(!verifiedOnly) },
                    label = { Text(stringResource(R.string.vpngate_filter_verified)) },
                    leadingIcon = { Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(16.dp)) },
                )
            }
        }
        if (countries.size > 1) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = GATE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    FilterChip(
                        selected = country.isEmpty(),
                        onClick = { onCountry("") },
                        label = { Text(stringResource(R.string.vpngate_filter_all)) },
                    )
                }
                items(countries, key = { it.first }) { (code, count) ->
                    FilterChip(
                        selected = country == code,
                        onClick = { onCountry(if (country == code) "" else code) },
                        label = { Text("${CountryFlags.flag(code)}  $code  $count") },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = GATE_GUTTER + 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.vpngate_count, shown, shown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (batchRunning) {
                Text(
                    stringResource(R.string.vpngate_probe_running, batchDone, batchTotal),
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.working,
                )
                TextButton(onClick = onStopBatch) { Text(stringResource(R.string.vpngate_probe_stop)) }
            } else {
                TextButton(onClick = onProbeBatch, enabled = shown > 0) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vpngate_probe_top, minOf(GATE_PROBE_BATCH, shown)))
                }
            }
        }
        if (batchRunning) {
            LinearProgressIndicator(
                progress = { if (batchTotal == 0) 0f else batchDone / batchTotal.toFloat() },
                modifier = Modifier
                    .padding(horizontal = GATE_GUTTER)
                    .fillMaxWidth()
                    .clip(CircleShape),
                color = accents.working,
            )
        }
    }
}

@StringRes
private fun VpnGateSort.labelRes(): Int = when (this) {
    VpnGateSort.SCORE -> R.string.vpngate_sort_score
    VpnGateSort.SPEED -> R.string.vpngate_sort_speed
    VpnGateSort.PING -> R.string.vpngate_sort_ping
    VpnGateSort.SESSIONS -> R.string.vpngate_sort_sessions
}

@Composable
private fun gateLatencyTone(ms: Int): Color {
    val accents = LocalAetherAccents.current
    return when {
        ms < 0 -> accents.neutral
        ms < 150 -> accents.protected
        ms < 300 -> accents.working
        else -> accents.failed
    }
}

@Composable
private fun GateRelayCard(
    server: VpnGateServer,
    probe: SstpProbe?,
    maxScore: Long,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val locale = LocalConfiguration.current.locales[0]
    val countryLabel = remember(server.countryCode, server.countryName, locale) {
        CountryFlags.displayName(server.countryCode, locale, server.countryName)
    }
    val borderColor by animateColorAsState(
        targetValue = if (selected) accents.brand else accents.cardBorder,
        animationSpec = tween(aetherDuration(AetherDur.Quick)),
        label = "relayBorder",
    )
    Surface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) accents.brandWash else accents.card,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GateFlagBadge(server.countryCode)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        countryLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        server.sstpHostname,
                        style = AetherMetaLabel.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GateProbeBadge(probe = probe, apiPingMs = server.apiPingMs)
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                GateMetric(stringResource(R.string.vpngate_metric_speed), server.formattedSpeed, Modifier.weight(1f))
                GateMetric(stringResource(R.string.vpngate_metric_sessions), server.numSessions.toString(), Modifier.weight(1f))
                GateMetric(stringResource(R.string.vpngate_metric_uptime), server.formattedUptime, Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            GateScoreBar(fraction = (server.score.toDouble() / maxScore.toDouble()).toFloat().coerceIn(0f, 1f))

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut)) +
                    fadeIn(tween(aetherDuration(AetherDur.Quick))),
                exit = shrinkVertically(tween(aetherDuration(AetherDur.Quick))) +
                    fadeOut(tween(aetherDuration(AetherDur.Quick))),
            ) {
                GateRelayDetails(server)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionPill(
                    label = stringResource(R.string.vpngate_test),
                    onClick = onProbe,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.NetworkCheck,
                    enabled = probe?.state != ProbeState.RUNNING,
                )
                ActionPill(
                    label = stringResource(if (selected) R.string.vpngate_selected else R.string.vpngate_use),
                    onClick = onUse,
                    modifier = Modifier.weight(1f),
                    icon = if (selected) Icons.Rounded.Check else Icons.Rounded.Bolt,
                    filled = true,
                    tint = if (selected) accents.protected else accents.brand,
                )
            }
        }
    }
}

@Composable
private fun GateFlagBadge(code: String) {
    Box(
        Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(CountryFlags.flag(code), fontSize = 26.sp)
    }
}

/** The user's own probe result; before a probe, VPN Gate's ping marked with "~". */
@Composable
private fun GateProbeBadge(probe: SstpProbe?, apiPingMs: Int) {
    val accents = LocalAetherAccents.current
    val latency = probe?.latencyMs ?: -1
    val tone: Color
    val label: String
    when (probe?.state) {
        null -> {
            tone = accents.neutral
            label = if (apiPingMs > 0) "~$apiPingMs ms" else "\u2014"
        }
        ProbeState.RUNNING -> {
            tone = accents.working
            label = stringResource(R.string.vpngate_probe_testing)
        }
        ProbeState.SSTP_OK -> {
            tone = gateLatencyTone(latency)
            label = "$latency ms"
        }
        ProbeState.REACHABLE_NO_SSTP -> {
            tone = accents.failed
            label = stringResource(R.string.vpngate_probe_no_sstp)
        }
        ProbeState.UNREACHABLE -> {
            tone = accents.failed
            label = stringResource(R.string.vpngate_probe_down)
        }
    }
    Surface(shape = CircleShape, color = tone.copy(alpha = 0.14f), contentColor = tone) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (probe?.state == ProbeState.RUNNING) {
                CircularProgressIndicator(Modifier.size(12.dp), color = tone, strokeWidth = 1.5.dp)
            } else {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                color = tone,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GateMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Relative to the best relay in the list: VPN Gate scores are unbounded. */
@Composable
private fun GateScoreBar(fraction: Float) {
    val accents = LocalAetherAccents.current
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(aetherDuration(AetherDur.Slow), easing = AetherEaseOut),
        label = "score",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.vpngate_metric_score),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(accents.brand, accents.protected))),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "${(fraction * 100f).roundToInt()}%",
            style = AetherMetaLabel.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GateRelayDetails(server: VpnGateServer) {
    Column(Modifier.padding(top = 16.dp)) {
        Hairline()
        Spacer(Modifier.height(8.dp))
        GateDetailRow(R.string.vpngate_detail_ip, server.ip)
        GateDetailRow(R.string.vpngate_detail_port, server.sstpPort.toString())
        GateDetailRow(R.string.vpngate_detail_users, server.formattedUsers)
        GateDetailRow(R.string.vpngate_detail_traffic, server.formattedTraffic)
        if (server.logPolicy.isNotBlank()) GateDetailRow(R.string.vpngate_detail_logs, server.logPolicy)
        if (server.operatorName.isNotBlank()) GateDetailRow(R.string.vpngate_detail_operator, server.operatorName)
        if (server.operatorMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                server.operatorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GateDetailRow(@StringRes label: Int, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = AetherMetaLabel.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GateSkeleton() {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(AetherDur.Base * 3, easing = AetherEaseInOut), RepeatMode.Reverse),
        label = "pulse",
    )
    val alpha = if (reduced) 0.55f else pulse
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = GATE_GUTTER, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.vpngate_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        repeat(4) { GateSkeletonCard(alpha) }
    }
}

@Composable
private fun GateSkeletonCard(alpha: Float) {
    val block = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    AetherCard(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(block),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(block),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.8f)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(block),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(block),
        )
    }
}

@Composable
private fun GateError(message: String, onRetry: () -> Unit) {
    val accents = LocalAetherAccents.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(Icons.Rounded.CloudOff, accents.failed, size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.vpngate_error_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vpngate_error_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = AetherMetaLabel.copy(fontFamily = AetherMono),
                color = accents.failed,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
        ActionPill(
            label = stringResource(R.string.vpngate_retry),
            onClick = onRetry,
            icon = Icons.Rounded.Refresh,
            filled = true,
            tint = accents.brand,
        )
    }
}

@Composable
private fun GateEmpty(onReset: () -> Unit, modifier: Modifier = Modifier) {
    AetherCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Rounded.TravelExplore, MaterialTheme.colorScheme.primary, size = 56.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.vpngate_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.vpngate_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            ActionPill(
                label = stringResource(R.string.vpngate_reset_filters),
                onClick = onReset,
                icon = Icons.Rounded.Close,
            )
        }
    }
}
