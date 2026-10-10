package studio.cluvex.aether.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.VpnGateRepository
import studio.cluvex.aether.model.VpnGateFilter
import studio.cluvex.aether.model.VpnGateServer
import studio.cluvex.aether.model.VpnGateSort
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * VPNGate server browser: a full-screen list of public VPN relays with
 * country flags, speed badges, ping measurements, and one-tap SSTP connect.
 *
 * ### Design language
 *
 * Material 3 cards with gradient accents. Each server gets a card showing:
 *  - Country flag emoji (large, left-aligned)
 *  - Hostname + country name
 *  - Speed / sessions / uptime badges
 *  - Ping indicator (green/yellow/red)
 *  - SSTP support badge
 *  - Connect button
 *
 * The top bar has a search field, sort selector, and SSTP-only filter toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnGateScreen(
    onServerSelected: (VpnGateServer) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var servers by remember { mutableStateOf<List<VpnGateServer>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(VpnGateFilter()) }
    var sortBy by remember { mutableStateOf(VpnGateSort.SCORE) }
    var searchQuery by remember { mutableStateOf("") }
    var pingResults by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    // Fetch servers on first composition
    LaunchedEffect(Unit) {
        isLoading = true
        val result = VpnGateRepository.fetchServers()
        result.onSuccess { fetched ->
            servers = fetched
            isLoading = false
        }.onFailure { e ->
            errorMessage = e.message ?: "Failed to fetch servers"
            isLoading = false
        }
    }

    // Filtered and sorted server list
    val displayServers = remember(servers, filter, sortBy, searchQuery, pingResults) {
        servers
            .let { list ->
                if (filter.sstpOnly) list.filter { it.supportsSstp } else list
            }
            .let { list ->
                if (filter.countryCode.isNotEmpty()) list.filter { it.countryCode == filter.countryCode } else list
            }
            .let { list ->
                if (filter.minSpeedBps > 0) list.filter { it.speedBps >= filter.minSpeedBps } else list
            }
            .let { list ->
                if (searchQuery.isNotBlank()) {
                    val q = searchQuery.lowercase()
                    list.filter {
                        it.hostname.lowercase().contains(q) ||
                            it.countryName.lowercase().contains(q) ||
                            it.countryCode.lowercase().contains(q) ||
                            it.ip.contains(q)
                    }
                } else list
            }
            .map { server ->
                pingResults[server.ip]?.let { ping -> server.copy(pingMs = ping) } ?: server
            }
            .sortedWith(
                when (sortBy) {
                    VpnGateSort.SCORE -> compareByDescending { it.score }
                    VpnGateSort.SPEED -> compareByDescending { it.speedBps }
                    VpnGateSort.PING -> compareBy { if (it.pingMs >= 0) it.pingMs else Int.MAX_VALUE }
                    VpnGateSort.SESSIONS -> compareBy { it.numSessions }
                }
            )
    }

    // Available countries for filter chips
    val countries = remember(servers) {
        servers.map { it.countryCode }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPNGate Servers", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            VpnGateRepository.fetchServers().onSuccess { fetched ->
                                servers = fetched
                                isLoading = false
                            }.onFailure {
                                isLoading = false
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search servers...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Filter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // SSTP only toggle
                FilterChip(
                    selected = filter.sstpOnly,
                    onClick = { filter = filter.copy(sstpOnly = !filter.sstpOnly) },
                    label = { Text("SSTP") },
                    leadingIcon = if (filter.sstpOnly) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                    } else null,
                )

                // Sort selector
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { sortExpanded = true },
                        label = {
                            Text(
                                when (sortBy) {
                                    VpnGateSort.SCORE -> "Best"
                                    VpnGateSort.SPEED -> "Fastest"
                                    VpnGateSort.PING -> "Lowest Ping"
                                    VpnGateSort.SESSIONS -> "Least Busy"
                                }
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        VpnGateSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (sort) {
                                            VpnGateSort.SCORE -> "Best Score"
                                            VpnGateSort.SPEED -> "Fastest Speed"
                                            VpnGateSort.PING -> "Lowest Ping"
                                            VpnGateSort.SESSIONS -> "Least Busy"
                                        }
                                    )
                                },
                                onClick = { sortBy = sort; sortExpanded = false },
                            )
                        }
                    }
                }

                // Ping all button
                TextButton(onClick = {
                    scope.launch {
                        displayServers.take(20).forEach { server ->
                            val ping = VpnGateRepository.measurePing(server)
                            if (ping >= 0) {
                                pingResults = pingResults + (server.ip to ping)
                            }
                        }
                    }
                }) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ping All")
                }
            }

            // Server count
            Text(
                "${displayServers.size} servers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Loading / Error / Server list
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Fetching servers...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.CloudOff, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(errorMessage ?: "", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                scope.launch {
                                    errorMessage = null
                                    isLoading = true
                                    VpnGateRepository.fetchServers().onSuccess {
                                        servers = it; isLoading = false
                                    }.onFailure {
                                        errorMessage = it.message; isLoading = false
                                    }
                                }
                            }) { Text("Retry") }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(displayServers, key = { it.ip }) { server ->
                            VpnGateServerCard(
                                server = server,
                                onConnect = { onServerSelected(server) },
                                onPing = {
                                    scope.launch {
                                        val ping = VpnGateRepository.measurePing(server)
                                        if (ping >= 0) {
                                            pingResults = pingResults + (server.ip to ping)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One server card in the VPNGate list. Shows flag, hostname, speed, ping,
 * session count and a connect button. Designed to be visually rich but
 * information-dense.
 */
@Composable
fun VpnGateServerCard(
    server: VpnGateServer,
    onConnect: () -> Unit,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pingColor = when {
        server.pingMs < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        server.pingMs < 100 -> Color(0xFF4CAF50) // Green
        server.pingMs < 200 -> Color(0xFFFFC107) // Yellow
        else -> Color(0xFFF44336) // Red
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: Flag + hostname + country
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Flag emoji
                Text(
                    text = CountryFlags.flag(server.countryCode),
                    fontSize = 32.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.hostname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${server.countryName} \u2022 ${server.ip}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // SSTP badge
                if (server.supportsSstp) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "SSTP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speed
                StatBadge(
                    icon = Icons.Rounded.Speed,
                    value = server.formattedSpeed,
                    modifier = Modifier.weight(1f),
                )

                // Ping
                StatBadge(
                    icon = Icons.Rounded.NetworkPing,
                    value = server.formattedPing,
                    tint = pingColor,
                    modifier = Modifier.weight(1f),
                )

                // Sessions
                StatBadge(
                    icon = Icons.Rounded.People,
                    value = "${server.numSessions}",
                    modifier = Modifier.weight(1f),
                )

                // Uptime
                StatBadge(
                    icon = Icons.Rounded.Timer,
                    value = server.formattedUptime,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Bottom row: Score bar + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Score indicator
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Score: ${server.score}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (server.score.coerceIn(0, 1000) / 1000f) },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = when {
                            server.score > 700 -> Color(0xFF4CAF50)
                            server.score > 400 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
                        },
                    )
                }

                // Ping button
                IconButton(onClick = onPing) {
                    Icon(
                        Icons.Rounded.NetworkPing, "Measure ping",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Connect button
                Button(
                    onClick = onConnect,
                    enabled = server.supportsSstp,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.VpnKey, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Connect")
                }
            }

            // Operator message (if any)
            if (server.operatorMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = server.operatorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small stat indicator with icon + value. */
@Composable
private fun StatBadge(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}
