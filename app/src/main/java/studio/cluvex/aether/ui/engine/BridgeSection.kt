package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.core.BridgeCatalog
import studio.cluvex.aether.core.BridgeFailure
import studio.cluvex.aether.core.BridgeLine
import studio.cluvex.aether.core.BridgeResult
import studio.cluvex.aether.core.BridgeService
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.PluggableTransports
import studio.cluvex.aether.data.BridgeStore
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.TorBridgeMode
import studio.cluvex.aether.ui.bridgeModeDescription
import studio.cluvex.aether.ui.bridgeModeLabel
import studio.cluvex.aether.ui.bridgeTransportDescription
import studio.cluvex.aether.ui.bridgeTransportIcon
import studio.cluvex.aether.ui.bridgeTransportLabel
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionRule
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * WHERE tor enters the network from.
 *
 * ### Why bridges need a page of their own
 *
 * Everything else in Settings is a value you pick. This is a TASK: choose a
 * type, ask a server on the internet for addresses, wait, maybe answer a
 * captcha, then choose which of the answers to keep. It has a network request in
 * the middle of it and it can fail in five distinguishable ways, none of which
 * fit in a row between two switches - and the Chain page is already seven cards
 * plus two option blocks.
 *
 * ### The one thing this page must never do
 *
 * Offer a bridge type this build cannot run. tor treats a `Bridge obfs4 ...`
 * line with no `ClientTransportPlugin` as a FATAL configuration error and exits
 * during startup, so a build without lyrebird that lets the user pick obfs4 does
 * not degrade - it produces a Tor hop that dies instantly, with nothing in the
 * log about bridges. Unavailable types are therefore disabled with the missing
 * binary named, which is the same rule the chain modes follow (see
 * [studio.cluvex.aether.core.PluggableTransports]).
 *
 * ### Why the fetch buttons say which path they used
 *
 * A bridge request has to reach bridges.torproject.org, and a network that
 * blocks Tor usually blocks that too - which is exactly the situation in which
 * somebody is on this page. Tor Browser solves it with domain fronting; this app
 * has no meek binary to front with, so it uses the tunnel the user already has
 * up and says so. "This only worked through Aether" is the single most useful
 * thing the user can learn here, because it tells them not to disconnect before
 * they have their bridges.
 */
@Composable
internal fun BridgeSection(
    bridgeMode: TorBridgeMode,
    transport: BridgeTransport,
    bridgeLines: String,
    chain: ChainMode,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Install-time facts: read once, never per recomposition.
    val cores = remember(context) { CoreAvailability.of(context) }
    val plugins = remember(context) { PluggableTransports.of(context) }
    val store = remember(context) { BridgeStore(context) }
    val stored by store.state.collectAsStateWithLifecycle(initialValue = BridgeStore.Snapshot())

    val catalog = remember(context, stored.catalogJson) {
        BridgeCatalog.current(context, stored.catalogJson)
    }
    val selected = remember(bridgeLines) { BridgeLine.parseAll(bridgeLines) }
    val selectedLines = remember(selected) { selected.map { it.line }.toSet() }
    val unusable = remember(selected, plugins) {
        selected.size - BridgeLine.usable(selected, plugins.supportedTorNames).size
    }

    var country by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<BridgeOutcome?>(null) }
    var challenge by remember { mutableStateOf<BridgeChallenge?>(null) }

    /** Writes a set of lines back, canonicalised, and never while connected. */
    fun setLines(lines: List<String>) {
        val text = BridgeLine.canonicalText(lines)
        edit { copy(torBridgeLines = text) }
    }

    fun toggle(line: String) {
        val current = selected.map { it.line }
        setLines(if (line in current) current - line else current + line)
    }

    EngineSection(
        title = stringResource(R.string.section_bridges),
        subtitle = stringResource(R.string.section_bridges_note),
        icon = Icons.Rounded.AltRoute,
        modifier = modifier,
    ) {
        if (!cores.torBinary) {
            NoticeBar(
                text = stringResource(R.string.bridges_no_tor_core),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        } else if (!chain.usesTor) {
            // Not an error: configuring bridges before switching the chain over is
            // a perfectly sensible order to do this in. It just must not look
            // like the setting is live when it is not.
            NoticeBar(
                text = stringResource(R.string.bridges_chain_has_no_tor),
                tone = accents.working,
                icon = Icons.Rounded.Info,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        // ------------------------------------------------------------ sources
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            FieldLabel(stringResource(R.string.bridges_mode_label))
            TorBridgeMode.entries.forEach { mode ->
                BridgeModeCard(
                    mode = mode,
                    selected = bridgeMode,
                    enabled = enabled,
                    onSelect = { edit { copy(torBridgeMode = mode) } },
                )
            }
        }

        DependentBlock(visible = bridgeMode.isOn) {
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SectionRule(stringResource(R.string.bridges_type_title), topSpace = 24)
            Hint(stringResource(R.string.bridges_type_hint))
            Spacer(Modifier.height(EngineSpacing.Inline))
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                BridgeTransport.entries.forEach { type ->
                    TransportCard(
                        type = type,
                        selected = transport,
                        runnable = plugins.has(type),
                        count = catalog[type]?.size ?: 0,
                        enabled = enabled,
                        onSelect = { edit { copy(torBridgeTransport = type) } },
                    )
                }
            }
            if (plugins.missing.isNotEmpty()) {
                Spacer(Modifier.height(EngineSpacing.Inline))
                NoticeBar(
                    text = stringResource(
                        R.string.bridges_plugins_missing,
                        plugins.missing.mapNotNull { it.plugin?.id }.distinct()
                            .joinToString(", "),
                    ),
                    tone = accents.working,
                    icon = Icons.Rounded.Warning,
                )
            }

            // -------------------------------------------------------- the rows
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()

            when (bridgeMode) {
                TorBridgeMode.BUILTIN -> BuiltinBlock(
                    transport = transport,
                    lines = catalog[transport].orEmpty(),
                    selectedLines = selectedLines,
                    updatedAt = stored.catalogUpdatedAt,
                    busy = busy,
                    enabled = enabled,
                    onToggle = ::toggle,
                    onUseAll = { setLines(catalog[transport].orEmpty()) },
                    onRefresh = {
                        busy = true
                        outcome = null
                        scope.launch {
                            outcome = when (val result = BridgeService.refreshBuiltin(context)) {
                                is BridgeResult.Ok -> BridgeOutcome.Refreshed(
                                    count = result.value.values.sumOf { it.size },
                                    viaTunnel = result.viaTunnel,
                                )
                                is BridgeResult.Failed -> BridgeOutcome.Failed(
                                    result.failure,
                                    result.code,
                                )
                            }
                            busy = false
                        }
                    },
                    onAsk = {
                        busy = true
                        outcome = null
                        scope.launch {
                            val result = BridgeService.recommended(
                                country = country.takeIf { it.length == 2 },
                                transports = plugins.available,
                            )
                            outcome = when (result) {
                                is BridgeResult.Ok -> {
                                    val bundles = result.value.bundles
                                    val offered = bundles.flatMap { it.lines }
                                    val usable = BridgeLine.usable(
                                        BridgeLine.parseAll(offered),
                                        plugins.supportedTorNames,
                                    ).map { it.line }
                                    // Bridges moat handed over for this client
                                    // alone are kept like a personal request:
                                    // they are not in any public list and they
                                    // do not come back on demand.
                                    val private = bundles.filter { it.isPrivate }
                                        .flatMap { it.lines }
                                    if (private.isNotEmpty()) store.addPersonal(private)
                                    if (usable.isNotEmpty()) {
                                        bundles.firstOrNull()?.let { first ->
                                            BridgeTransport.fromMoatName(first.type)?.let { type ->
                                                edit { copy(torBridgeTransport = type) }
                                            }
                                        }
                                        setLines(usable)
                                    }
                                    if (bundles.isEmpty()) {
                                        BridgeOutcome.NotNeeded(result.value.country)
                                    } else {
                                        BridgeOutcome.Recommended(
                                            count = usable.size,
                                            country = result.value.country,
                                            viaTunnel = result.viaTunnel,
                                        )
                                    }
                                }
                                is BridgeResult.Failed ->
                                    BridgeOutcome.Failed(result.failure, result.code)
                            }
                            busy = false
                        }
                    },
                    country = country,
                    onCountryChange = { country = it },
                )

                TorBridgeMode.REQUESTED -> RequestBlock(
                    transport = transport,
                    personal = stored.personalLines,
                    selectedLines = selectedLines,
                    busy = busy,
                    enabled = enabled,
                    onToggle = ::toggle,
                    onForget = { line -> scope.launch { store.removePersonal(line) } },
                    onRequest = {
                        busy = true
                        outcome = null
                        scope.launch {
                            when (val result = BridgeService.challenge(transport)) {
                                is BridgeResult.Ok -> challenge = BridgeChallenge(
                                    token = result.value.challenge,
                                    imageBase64 = result.value.image,
                                    viaTunnel = result.viaTunnel,
                                )
                                is BridgeResult.Failed ->
                                    outcome = BridgeOutcome.Failed(result.failure, result.code)
                            }
                            busy = false
                        }
                    },
                )

                TorBridgeMode.CUSTOM -> CustomBlock(
                    text = bridgeLines,
                    parsedCount = selected.size,
                    enabled = enabled,
                    onChange = { raw -> edit { copy(torBridgeLines = raw) } },
                )

                TorBridgeMode.OFF -> Unit
            }

            outcome?.let {
                Spacer(Modifier.height(EngineSpacing.Field))
                OutcomeNotice(it)
            }

            // ------------------------------------------------------- in use
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SectionRule(
                stringResource(R.string.bridges_active_title, selected.size),
                topSpace = 24,
            )
            if (selected.isEmpty()) {
                NoticeBar(
                    text = stringResource(R.string.bridges_active_empty),
                    tone = accents.working,
                    icon = Icons.Rounded.Info,
                )
            } else {
                if (unusable > 0) {
                    NoticeBar(
                        text = stringResource(R.string.bridges_active_unusable, unusable),
                        tone = accents.failed,
                        icon = Icons.Rounded.Warning,
                    )
                    Spacer(Modifier.height(EngineSpacing.Inline))
                }
                selected.forEach { parsed ->
                    ActiveBridgeRow(
                        label = parsed.shortLabel,
                        line = parsed.line,
                        runnable = parsed.transportName.isEmpty() ||
                            parsed.transportName in plugins.supportedTorNames,
                        enabled = enabled,
                        onRemove = { setLines(selected.map { it.line } - parsed.line) },
                    )
                }
                Spacer(Modifier.height(EngineSpacing.Inline))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionPill(
                        label = stringResource(R.string.bridges_copy_all),
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(selected.joinToString("\n") { it.line }),
                            )
                        },
                        icon = Icons.Rounded.ContentCopy,
                    )
                    ActionPill(
                        label = stringResource(R.string.bridges_clear),
                        onClick = { setLines(emptyList()) },
                        icon = Icons.Rounded.Delete,
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(EngineSpacing.Inline))
            Hint(stringResource(R.string.bridges_privacy_note))
        }
    }

    challenge?.let { pending ->
        BridgeRequestDialog(
            challenge = pending,
            transport = transport,
            onDismiss = { challenge = null },
            onSubmit = { solution ->
                val token = pending.token
                challenge = null
                busy = true
                outcome = null
                scope.launch {
                    val result = BridgeService.solve(context, transport, token, solution)
                    outcome = when (result) {
                        is BridgeResult.Ok -> {
                            setLines(selected.map { it.line } + result.value)
                            BridgeOutcome.Received(result.value.size, result.viaTunnel)
                        }
                        is BridgeResult.Failed ->
                            BridgeOutcome.Failed(result.failure, result.code)
                    }
                    busy = false
                }
            },
        )
    }
}

/** What the last request did. Data only: the wording lives in [OutcomeNotice]. */
internal sealed interface BridgeOutcome {
    data class Refreshed(val count: Int, val viaTunnel: Boolean) : BridgeOutcome
    data class Recommended(val count: Int, val country: String?, val viaTunnel: Boolean) :
        BridgeOutcome
    data class NotNeeded(val country: String?) : BridgeOutcome
    data class Received(val count: Int, val viaTunnel: Boolean) : BridgeOutcome
    data class Failed(val failure: BridgeFailure, val code: Int?) : BridgeOutcome
}

/** A captcha waiting to be answered. */
internal data class BridgeChallenge(
    val token: String,
    val imageBase64: String,
    val viaTunnel: Boolean,
)

@Composable
private fun OutcomeNotice(outcome: BridgeOutcome) {
    val accents = LocalAetherAccents.current
    val route = { viaTunnel: Boolean ->
        if (viaTunnel) {
            stringResource(R.string.bridges_route_tunnel)
        } else {
            stringResource(R.string.bridges_route_direct)
        }
    }
    when (outcome) {
        is BridgeOutcome.Refreshed -> NoticeBar(
            text = stringResource(R.string.bridges_refreshed, outcome.count) + " " +
                route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
        )
        is BridgeOutcome.Recommended -> NoticeBar(
            text = stringResource(
                R.string.bridges_recommended,
                outcome.count,
                outcome.country?.uppercase() ?: stringResource(R.string.bridges_country_unknown),
            ) + " " + route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
        )
        is BridgeOutcome.NotNeeded -> NoticeBar(
            text = stringResource(
                R.string.bridges_not_needed,
                outcome.country?.uppercase() ?: stringResource(R.string.bridges_country_unknown),
            ),
            tone = accents.working,
            icon = Icons.Rounded.Info,
        )
        is BridgeOutcome.Received -> NoticeBar(
            text = stringResource(R.string.bridges_received, outcome.count) + " " +
                route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
        )
        is BridgeOutcome.Failed -> NoticeBar(
            text = when (outcome.failure) {
                BridgeFailure.UNREACHABLE -> stringResource(R.string.bridges_err_unreachable)
                BridgeFailure.CAPTCHA_REFUSED -> stringResource(R.string.bridges_err_captcha)
                BridgeFailure.NO_TRANSPORT -> stringResource(R.string.bridges_err_no_transport)
                BridgeFailure.SERVER -> stringResource(
                    R.string.bridges_err_server,
                    outcome.code ?: 0,
                )
                BridgeFailure.MALFORMED -> stringResource(R.string.bridges_err_malformed)
            },
            tone = accents.failed,
            icon = Icons.Rounded.Warning,
        )
    }
}

// ------------------------------------------------------------------- sources

@Composable
private fun BridgeModeCard(
    mode: TorBridgeMode,
    selected: TorBridgeMode,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val active = mode == selected
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(
                selected = active,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (active) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                tint = if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = bridgeModeLabel(mode),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bridgeModeDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransportCard(
    type: BridgeTransport,
    selected: BridgeTransport,
    runnable: Boolean,
    count: Int,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val active = type == selected
    val alpha = if (runnable) 1f else 0.45f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(
                selected = active,
                enabled = enabled && runnable,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = bridgeTransportIcon(type),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                tint = (if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = alpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bridgeTransportLabel(type),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    if (runnable && count > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = accents.brand.copy(alpha = 0.14f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = stringResource(R.string.bridges_builtin_count, count),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = AetherMetaLabel,
                                color = accents.brand,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bridgeTransportDescription(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
                if (!runnable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.bridges_type_unavailable,
                            type.plugin?.id ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accents.failed,
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------- the blocks

@Composable
private fun ColumnScopeBuiltin(
    content: @Composable () -> Unit,
) = content()

@Composable
private fun BuiltinBlock(
    transport: BridgeTransport,
    lines: List<String>,
    selectedLines: Set<String>,
    updatedAt: Long,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onUseAll: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: () -> Unit,
    country: String,
    onCountryChange: (String) -> Unit,
) {
    val accents = LocalAetherAccents.current
    SectionRule(stringResource(R.string.bridges_builtin_title), topSpace = 24)
    NoticeBar(
        text = stringResource(R.string.bridges_builtin_note),
        tone = accents.working,
        icon = Icons.Rounded.Public,
    )
    Spacer(Modifier.height(EngineSpacing.Field))

    if (lines.isEmpty()) {
        NoticeBar(
            text = stringResource(R.string.bridges_builtin_none),
            tone = accents.failed,
            icon = Icons.Rounded.Warning,
        )
    } else {
        lines.forEach { line ->
            val parsed = BridgeLine.parse(line)
            SelectableBridgeRow(
                label = parsed?.shortLabel ?: line,
                line = parsed?.line ?: line,
                checked = (parsed?.line ?: line) in selectedLines,
                enabled = enabled,
                onToggle = { onToggle(parsed?.line ?: line) },
            )
        }
        Spacer(Modifier.height(EngineSpacing.Inline))
        ActionPill(
            label = stringResource(R.string.bridges_use_all, transport.torName.ifEmpty { "" }),
            onClick = onUseAll,
            icon = Icons.Rounded.Checklist,
            enabled = enabled,
        )
    }

    Spacer(Modifier.height(EngineSpacing.Divider))
    EngineDivider()
    SectionRule(stringResource(R.string.bridges_fetch_title), topSpace = 24)
    NoticeBar(
        text = stringResource(R.string.bridges_fetch_note),
        tone = accents.working,
        icon = Icons.Rounded.Info,
    )
    Spacer(Modifier.height(EngineSpacing.Field))
    LtrOutlinedTextField(
        value = country,
        onValueChange = onCountryChange,
        inputTransform = { raw ->
            raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase().take(2)
        },
        enabled = enabled && !busy,
        singleLine = true,
        label = { Text(stringResource(R.string.bridges_country_label)) },
        placeholder = { Text(stringResource(R.string.bridges_country_hint)) },
        supportingText = { Text(stringResource(R.string.bridges_country_help)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(EngineSpacing.Inline))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionPill(
            label = stringResource(
                if (busy) R.string.bridges_working else R.string.bridges_ask,
            ),
            onClick = onAsk,
            icon = Icons.Rounded.CloudDownload,
            enabled = enabled && !busy,
            filled = true,
        )
        ActionPill(
            label = stringResource(R.string.bridges_refresh),
            onClick = onRefresh,
            icon = Icons.Rounded.Refresh,
            enabled = enabled && !busy,
        )
    }
    Spacer(Modifier.height(EngineSpacing.Inline))
    Hint(
        if (updatedAt > 0L) {
            stringResource(R.string.bridges_updated, formatBridgeDate(updatedAt))
        } else {
            stringResource(R.string.bridges_updated_never)
        },
    )
}

@Composable
private fun RequestBlock(
    transport: BridgeTransport,
    personal: List<String>,
    selectedLines: Set<String>,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onForget: (String) -> Unit,
    onRequest: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    SectionRule(stringResource(R.string.bridges_personal_title), topSpace = 24)
    NoticeBar(
        text = stringResource(R.string.bridges_personal_note),
        tone = accents.protected,
        icon = Icons.Rounded.Key,
    )
    Spacer(Modifier.height(EngineSpacing.Field))
    ActionPill(
        label = stringResource(
            if (busy) R.string.bridges_working else R.string.bridges_request,
        ),
        onClick = onRequest,
        icon = Icons.Rounded.CloudDownload,
        enabled = enabled && !busy && transport.needsPlugin,
        filled = true,
    )
    if (!transport.needsPlugin) {
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(stringResource(R.string.bridges_request_vanilla))
    }
    Spacer(Modifier.height(EngineSpacing.Inline))
    Hint(stringResource(R.string.bridges_fetch_note))

    Spacer(Modifier.height(EngineSpacing.Field))
    FieldLabel(stringResource(R.string.bridges_personal_list))
    if (personal.isEmpty()) {
        Hint(stringResource(R.string.bridges_personal_empty))
    } else {
        personal.forEach { line ->
            val parsed = BridgeLine.parse(line)
            SelectableBridgeRow(
                label = parsed?.shortLabel ?: line,
                line = parsed?.line ?: line,
                checked = (parsed?.line ?: line) in selectedLines,
                enabled = enabled,
                onToggle = { onToggle(parsed?.line ?: line) },
                onRemove = { onForget(line) },
            )
        }
    }
}

@Composable
private fun CustomBlock(
    text: String,
    parsedCount: Int,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val raw = remember(text) { text.lines().count { it.isNotBlank() } }
    SectionRule(stringResource(R.string.bridges_custom_title), topSpace = 24)
    Hint(stringResource(R.string.bridges_custom_note))
    Spacer(Modifier.height(EngineSpacing.Inline))
    LtrOutlinedTextField(
        value = text,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = false,
        label = { Text(stringResource(R.string.bridges_custom_label)) },
        placeholder = { Text(stringResource(R.string.bridges_custom_placeholder)) },
        supportingText = {
            Text(stringResource(R.string.bridges_custom_count, parsedCount, raw - parsedCount))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----------------------------------------------------------------- the rows

/**
 * One bridge, with its own checkbox.
 *
 * Per-line selection rather than "use this transport" is deliberate: bridges die
 * one at a time, and "the first one works, the other two do not" is a thing the
 * user can only act on if they can turn one off. The full line is shown, LTR and
 * ellipsised, because a fingerprint is how two bridges on the same host are told
 * apart - and because a user comparing what they pasted against what a friend
 * sent them needs to see it.
 */
@Composable
private fun SelectableBridgeRow(
    label: String,
    line: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
                    .copy(textDirection = TextDirection.Ltr),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall
                    .copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.bridges_forget),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ActiveBridgeRow(
    label: String,
    line: String,
    runnable: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (runnable) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (runnable) accents.protected else accents.failed,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
                    .copy(textDirection = TextDirection.Ltr),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall
                    .copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.bridges_remove),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * `2026-09-09 21:15`, in the device's own locale-independent form.
 *
 * Deliberately not a relative time ("3 days ago"): the only question this answers
 * is "is this list older than the block I am trying to get past", and a date
 * answers it without a recomposing clock.
 */
private fun formatBridgeDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(epochMillis))
