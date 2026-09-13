package studio.cluvex.aether.ui.engine

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.text.AnnotatedString
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
import studio.cluvex.aether.ui.bridgeModeIcon
import studio.cluvex.aether.ui.bridgeModeLabel
import studio.cluvex.aether.ui.bridgeModeTag
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.NoticeBar
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
 * fit in a row between two switches.
 *
 * ### Why it is a numbered sequence and not a settings page
 *
 * It used to be rendered as one: five equally loud headings, nine
 * always-expanded option cards, up to four notice bars stacked on top of each
 * other, and the same bridge listed twice - once with a checkbox and once again
 * at the bottom under "In use". That is around nine phone screens, and the line
 * that decides everything ("what will tor actually be given") was last.
 *
 * So the page now reads top-down as what it is:
 *
 *  1. a status strip that answers "am I set up" before any option is shown;
 *  2. **where** the bridges come from;
 *  3. **which type** - skipped entirely for pasted lines, which carry their own;
 *  4. **getting them**, with the wait named and the result dismissible;
 *  5. **what tor will use**, which is the only list that is not a picker.
 *
 * Detail is shown at the point of decision and nowhere else: an unselected
 * option carries one line, the selected one carries its full description, and a
 * bridge row keeps its 400-byte verbatim line behind one tap.
 *
 * ### The one thing this page must never do
 *
 * Offer a bridge type this build cannot run. tor treats a `Bridge obfs4 ...`
 * line with no `ClientTransportPlugin` as a FATAL configuration error and exits
 * during startup, so a build without lyrebird that lets the user pick obfs4 does
 * not degrade - it produces a Tor hop that dies instantly, with nothing in the
 * log about bridges. Unavailable types are therefore disabled with the missing
 * binary named ON the chip, which is the same rule the chain modes follow (see
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

    /** Writes a set of lines back, canonicalised. Refused while connected by [edit]. */
    val setLines: (List<String>) -> Unit = { lines ->
        edit { copy(torBridgeLines = BridgeLine.canonicalText(lines)) }
    }
    val toggle: (String) -> Unit = { line ->
        val current = selected.map { it.line }
        setLines(if (line in current) current - line else current + line)
    }
    val copyText: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
    }

    // Pasted lines carry their own transport token, so a type picker in CUSTOM
    // mode was a control with nothing to control - and it silently implied that
    // picking obfs4 there would do something to the text in the box.
    val picksType = bridgeMode == TorBridgeMode.BUILTIN || bridgeMode == TorBridgeMode.REQUESTED
    val stepGet = if (picksType) 3 else 2

    // Resolved unconditionally: stringResource is a composable call, and the
    // strip below picks between them in a plain expression.
    val offTitle = stringResource(R.string.bridges_status_off)
    val offNote = stringResource(R.string.bridges_status_off_note)
    val emptyTitle = stringResource(R.string.bridges_status_empty)
    val emptyNote = stringResource(R.string.bridges_status_empty_note)
    val readyTitle = stringResource(R.string.bridges_status_ready, selected.size)
    val readyNote = stringResource(R.string.bridges_status_ready_note)
    val unusableTag = stringResource(R.string.bridges_tag_unusable, unusable)
    val noTorTag = stringResource(R.string.bridges_tag_no_tor)

    EngineSection(
        title = stringResource(R.string.section_bridges),
        subtitle = stringResource(R.string.section_bridges_note),
        icon = Icons.Rounded.AltRoute,
        modifier = modifier,
    ) {
        if (!cores.torBinary) {
            // The only condition that makes the whole page moot, and the only
            // one still loud enough to be a notice bar.
            NoticeBar(
                text = stringResource(R.string.bridges_no_tor_core),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
        } else {
            val tags = buildList {
                if (bridgeMode.isOn && unusable > 0) {
                    add(BridgeTag(unusableTag, accents.failed, Icons.Rounded.Warning))
                }
                // Not an error: configuring bridges before switching the chain
                // over is a sensible order to do this in. It just must not look
                // like the setting is live when it is not.
                if (bridgeMode.isOn && !chain.usesTor) {
                    add(BridgeTag(noTorTag, accents.working, Icons.Rounded.Info))
                }
            }
            BridgeStatusStrip(
                title = when {
                    !bridgeMode.isOn -> offTitle
                    selected.isEmpty() -> emptyTitle
                    else -> readyTitle
                },
                note = when {
                    !bridgeMode.isOn -> offNote
                    selected.isEmpty() -> emptyNote
                    else -> readyNote
                },
                tone = when {
                    !bridgeMode.isOn -> accents.neutral
                    selected.isEmpty() -> accents.working
                    else -> accents.protected
                },
                icon = when {
                    !bridgeMode.isOn -> Icons.Rounded.Block
                    selected.isEmpty() -> Icons.Rounded.Info
                    else -> Icons.Rounded.CheckCircle
                },
                tags = tags,
            )

            // ------------------------------------------------------- 1. source
            Spacer(Modifier.height(BridgeSpacing.Step))
            BridgeStepHeader(step = 1, title = stringResource(R.string.bridges_mode_label))
            Spacer(Modifier.height(EngineSpacing.Inline))
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                TorBridgeMode.entries.forEach { mode ->
                    val active = mode == bridgeMode
                    BridgeChoiceRow(
                        title = bridgeModeLabel(mode),
                        summary = if (active) {
                            bridgeModeDescription(mode)
                        } else {
                            bridgeModeTag(mode)
                        },
                        icon = bridgeModeIcon(mode),
                        selected = active,
                        enabled = enabled && !busy,
                        onSelect = {
                            // Cleared HERE rather than in an effect keyed on the
                            // mode: a successful fetch changes the transport
                            // itself, and an effect would wipe the result it
                            // just produced.
                            outcome = null
                            edit { copy(torBridgeMode = mode) }
                        },
                    )
                }
            }

            DependentBlock(visible = bridgeMode.isOn) {
                // --------------------------------------------------- 2. type
                if (picksType) {
                    Spacer(Modifier.height(BridgeSpacing.Step))
                    EngineDivider()
                    Spacer(Modifier.height(BridgeSpacing.Step))
                    BridgeStepHeader(
                        step = 2,
                        title = stringResource(R.string.bridges_type_title),
                        note = stringResource(R.string.bridges_type_hint),
                    )
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    BridgeTransportPicker(
                        selected = transport,
                        runnable = { type -> plugins.has(type) },
                        countOf = { type -> catalog[type]?.size ?: 0 },
                        enabled = enabled && !busy,
                        onSelect = { type ->
                            outcome = null
                            edit { copy(torBridgeTransport = type) }
                        },
                    )
                    if (plugins.missing.isNotEmpty()) {
                        Hint(
                            stringResource(
                                R.string.bridges_plugins_missing,
                                plugins.missing
                                    .mapNotNull { it.plugin?.id }
                                    .distinct()
                                    .joinToString(", "),
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(BridgeSpacing.Step))
                EngineDivider()
                Spacer(Modifier.height(BridgeSpacing.Step))

                // ---------------------------------------------------- 3. get
                when (bridgeMode) {
                    TorBridgeMode.BUILTIN -> BuiltinBlock(
                        step = stepGet,
                        lines = catalog[transport].orEmpty(),
                        selectedLines = selectedLines,
                        updatedAt = stored.catalogUpdatedAt,
                        busy = busy,
                        enabled = enabled,
                        country = country,
                        onCountryChange = { country = it },
                        onToggle = toggle,
                        onCopy = copyText,
                        onUseAll = { setLines(catalog[transport].orEmpty()) },
                        // Only the built-in lines are dropped: a personal or
                        // pasted bridge the user also ticked is not part of
                        // this list and has no business being cleared by it.
                        onUseNone = {
                            val theseLines = catalog[transport].orEmpty()
                                .mapNotNull { BridgeLine.parse(it)?.line }
                                .toSet()
                            setLines(selected.map { it.line } - theseLines)
                        },
                        onRefresh = {
                            busy = true
                            outcome = null
                            scope.launch {
                                outcome = when (
                                    val result = BridgeService.refreshBuiltin(context)
                                ) {
                                    is BridgeResult.Ok -> BridgeOutcome.Refreshed(
                                        count = result.value.values.sumOf { it.size },
                                        viaTunnel = result.viaTunnel,
                                    )
                                    is BridgeResult.Failed ->
                                        BridgeOutcome.Failed(result.failure, result.code)
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
                                        val usable = BridgeLine.usable(
                                            BridgeLine.parseAll(bundles.flatMap { it.lines }),
                                            plugins.supportedTorNames,
                                        ).map { it.line }
                                        // Bridges moat handed over for this
                                        // client alone are kept like a personal
                                        // request: they are in no public list
                                        // and they do not come back on demand.
                                        val handedOver = bundles
                                            .filter { it.isPrivate }
                                            .flatMap { it.lines }
                                        if (handedOver.isNotEmpty()) {
                                            store.addPersonal(handedOver)
                                        }
                                        if (usable.isNotEmpty()) {
                                            BridgeTransport.fromMoatName(bundles.first().type)
                                                ?.let { type ->
                                                    edit { copy(torBridgeTransport = type) }
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
                    )

                    TorBridgeMode.REQUESTED -> RequestBlock(
                        step = stepGet,
                        transport = transport,
                        personal = stored.personalLines,
                        selectedLines = selectedLines,
                        supported = plugins.supportedTorNames,
                        busy = busy,
                        enabled = enabled,
                        onToggle = toggle,
                        onCopy = copyText,
                        onForget = { line -> scope.launch { store.removePersonal(line) } },
                        onRequest = {
                            busy = true
                            outcome = null
                            scope.launch {
                                when (val result = BridgeService.challenge(transport)) {
                                    is BridgeResult.Ok -> challenge = BridgeChallenge(
                                        token = result.value.challenge,
                                        imageBase64 = result.value.image,
                                    )
                                    is BridgeResult.Failed ->
                                        outcome =
                                            BridgeOutcome.Failed(result.failure, result.code)
                                }
                                busy = false
                            }
                        },
                    )

                    TorBridgeMode.CUSTOM -> CustomBlock(
                        step = stepGet,
                        text = bridgeLines,
                        parsedCount = selected.size,
                        enabled = enabled,
                        onChange = { raw -> edit { copy(torBridgeLines = raw) } },
                        onPaste = {
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            if (pasted.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    R.string.bridges_paste_none,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                val merged = if (bridgeLines.isBlank()) {
                                    pasted
                                } else {
                                    bridgeLines.trimEnd() + "\n" + pasted
                                }
                                edit { copy(torBridgeLines = merged) }
                            }
                        },
                    )

                    TorBridgeMode.OFF -> Unit
                }

                outcome?.let { current ->
                    Spacer(Modifier.height(EngineSpacing.Field))
                    OutcomeNotice(current) { outcome = null }
                }

                // ------------------------------------------------- 4. in use
                Spacer(Modifier.height(BridgeSpacing.Step))
                EngineDivider()
                Spacer(Modifier.height(BridgeSpacing.Step))
                BridgeStepHeader(
                    step = stepGet + 1,
                    title = stringResource(R.string.bridges_active_title, selected.size),
                    done = selected.isNotEmpty() && unusable == 0,
                )
                Spacer(Modifier.height(EngineSpacing.Inline))
                if (selected.isEmpty()) {
                    BridgeEmptyState(
                        text = stringResource(R.string.bridges_active_empty),
                        icon = Icons.Rounded.Info,
                    )
                } else {
                    selected.forEach { parsed ->
                        val runnable = parsed.transportName.isEmpty() ||
                            parsed.transportName in plugins.supportedTorNames
                        ActiveBridgeRow(
                            label = parsed.shortLabel,
                            detail = bridgeRowDetail(parsed, runnable),
                            line = parsed.line,
                            runnable = runnable,
                            enabled = enabled,
                            onCopy = { copyText(parsed.line) },
                            onRemove = { setLines(selected.map { it.line } - parsed.line) },
                            removeLabel = stringResource(R.string.bridges_remove),
                        )
                    }
                    if (unusable > 0) {
                        Hint(stringResource(R.string.bridges_active_unusable, unusable))
                    }
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionPill(
                            label = stringResource(R.string.bridges_copy_all),
                            onClick = { copyText(selected.joinToString("\n") { it.line }) },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.ContentCopy,
                        )
                        ActionPill(
                            label = stringResource(R.string.bridges_clear),
                            onClick = { setLines(emptyList()) },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Delete,
                            enabled = enabled,
                        )
                    }
                }
                Hint(stringResource(R.string.bridges_privacy_note))
            }
        }
    }

    challenge?.let { pending ->
        BridgeRequestDialog(
            challenge = pending,
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
internal data class BridgeChallenge(val token: String, val imageBase64: String)

@Composable
private fun OutcomeNotice(outcome: BridgeOutcome, onDismiss: () -> Unit) {
    val accents = LocalAetherAccents.current
    // Resolved OUTSIDE the branches: stringResource is a composable call and
    // cannot be made from a plain lambda.
    val viaTunnel = stringResource(R.string.bridges_route_tunnel)
    val direct = stringResource(R.string.bridges_route_direct)
    val unknownCountry = stringResource(R.string.bridges_country_unknown)
    val route: (Boolean) -> String = { if (it) " $viaTunnel" else " $direct" }
    when (outcome) {
        is BridgeOutcome.Refreshed -> BridgeOutcomeCard(
            text = stringResource(R.string.bridges_refreshed, outcome.count) +
                route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
            onDismiss = onDismiss,
        )
        is BridgeOutcome.Recommended -> BridgeOutcomeCard(
            text = stringResource(
                R.string.bridges_recommended,
                outcome.count,
                outcome.country?.uppercase() ?: unknownCountry,
            ) + route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
            onDismiss = onDismiss,
        )
        is BridgeOutcome.NotNeeded -> BridgeOutcomeCard(
            text = stringResource(
                R.string.bridges_not_needed,
                outcome.country?.uppercase() ?: unknownCountry,
            ),
            tone = accents.working,
            icon = Icons.Rounded.Info,
            onDismiss = onDismiss,
        )
        is BridgeOutcome.Received -> BridgeOutcomeCard(
            text = stringResource(R.string.bridges_received, outcome.count) +
                route(outcome.viaTunnel),
            tone = accents.protected,
            icon = Icons.Rounded.CheckCircle,
            onDismiss = onDismiss,
        )
        is BridgeOutcome.Failed -> BridgeOutcomeCard(
            text = when (outcome.failure) {
                BridgeFailure.UNREACHABLE -> stringResource(R.string.bridges_err_unreachable)
                BridgeFailure.CAPTCHA_REFUSED -> stringResource(R.string.bridges_err_captcha)
                BridgeFailure.NO_TRANSPORT -> stringResource(R.string.bridges_err_no_transport)
                BridgeFailure.SERVER ->
                    stringResource(R.string.bridges_err_server, outcome.code ?: 0)
                BridgeFailure.MALFORMED -> stringResource(R.string.bridges_err_malformed)
            },
            tone = accents.failed,
            icon = Icons.Rounded.Warning,
            onDismiss = onDismiss,
        )
    }
}

// --------------------------------------------------------------- the blocks

@Composable
private fun BuiltinBlock(
    step: Int,
    lines: List<String>,
    selectedLines: Set<String>,
    updatedAt: Long,
    busy: Boolean,
    enabled: Boolean,
    country: String,
    onCountryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCopy: (String) -> Unit,
    onUseAll: () -> Unit,
    onUseNone: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    BridgeStepHeader(
        step = step,
        title = stringResource(R.string.bridges_builtin_title),
        note = stringResource(R.string.bridges_builtin_note),
    )
    Spacer(Modifier.height(EngineSpacing.Field))

    if (lines.isEmpty()) {
        NoticeBar(
            text = stringResource(R.string.bridges_builtin_none),
            tone = accents.failed,
            icon = Icons.Rounded.Warning,
        )
    } else {
        // One control, both directions. "Select all of them" with no way back
        // meant undoing it was eight taps on eight checkboxes.
        val allPicked = lines.all { (BridgeLine.parse(it)?.line ?: it) in selectedLines }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(
                text = stringResource(R.string.bridges_available, lines.size),
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = stringResource(
                    if (allPicked) R.string.bridges_use_none else R.string.bridges_use_all,
                ),
                onClick = if (allPicked) onUseNone else onUseAll,
                icon = Icons.Rounded.Checklist,
                enabled = enabled,
            )
        }
        lines.forEach { line ->
            val canonical = BridgeLine.parse(line)
            val value = canonical?.line ?: line
            SelectableBridgeRow(
                label = canonical?.shortLabel ?: line,
                detail = bridgeRowDetail(canonical, runnable = true),
                line = value,
                checked = value in selectedLines,
                enabled = enabled,
                onToggle = { onToggle(value) },
                onCopy = { onCopy(value) },
            )
        }
    }

    Spacer(Modifier.height(EngineSpacing.Divider))
    EngineDivider()
    Spacer(Modifier.height(EngineSpacing.Field))
    FieldLabel(stringResource(R.string.bridges_fetch_title))
    Hint(stringResource(R.string.bridges_fetch_note))
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
            label = stringResource(R.string.bridges_ask),
            onClick = onAsk,
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.CloudDownload,
            enabled = enabled && !busy,
            filled = true,
        )
        ActionPill(
            label = stringResource(R.string.bridges_refresh),
            onClick = onRefresh,
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Refresh,
            enabled = enabled && !busy,
        )
    }
    BridgeBusyBar(visible = busy)
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
    step: Int,
    transport: BridgeTransport,
    personal: List<String>,
    selectedLines: Set<String>,
    supported: Set<String>,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onCopy: (String) -> Unit,
    onForget: (String) -> Unit,
    onRequest: () -> Unit,
) {
    BridgeStepHeader(
        step = step,
        title = stringResource(R.string.bridges_personal_title),
        note = stringResource(R.string.bridges_personal_note),
    )
    Spacer(Modifier.height(EngineSpacing.Field))
    ActionPill(
        label = stringResource(R.string.bridges_request),
        onClick = onRequest,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Rounded.CloudDownload,
        enabled = enabled && !busy && transport.needsPlugin,
        filled = true,
    )
    if (!transport.needsPlugin) {
        Hint(stringResource(R.string.bridges_request_vanilla))
    }
    BridgeBusyBar(visible = busy)
    Hint(stringResource(R.string.bridges_fetch_note))

    Spacer(Modifier.height(EngineSpacing.Field))
    FieldLabel(stringResource(R.string.bridges_personal_list))
    if (personal.isEmpty()) {
        BridgeEmptyState(
            text = stringResource(R.string.bridges_personal_empty),
            icon = Icons.Rounded.Key,
        )
    } else {
        val forget = stringResource(R.string.bridges_forget)
        personal.forEach { line ->
            val canonical = BridgeLine.parse(line)
            val value = canonical?.line ?: line
            val runnable = canonical == null ||
                canonical.transportName.isEmpty() ||
                canonical.transportName in supported
            SelectableBridgeRow(
                label = canonical?.shortLabel ?: line,
                detail = bridgeRowDetail(canonical, runnable),
                line = value,
                checked = value in selectedLines,
                enabled = enabled,
                onToggle = { onToggle(value) },
                onCopy = { onCopy(value) },
                onRemove = { onForget(line) },
                removeLabel = forget,
            )
        }
    }
}

@Composable
private fun CustomBlock(
    step: Int,
    text: String,
    parsedCount: Int,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    val raw = remember(text) { text.lines().count { it.isNotBlank() } }
    BridgeStepHeader(
        step = step,
        title = stringResource(R.string.bridges_custom_title),
        note = stringResource(R.string.bridges_custom_note),
    )
    Spacer(Modifier.height(EngineSpacing.Field))
    LtrOutlinedTextField(
        value = text,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = false,
        label = { Text(stringResource(R.string.bridges_custom_label)) },
        placeholder = { Text(stringResource(R.string.bridges_custom_placeholder)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.bridges_custom_count,
                    parsedCount,
                    (raw - parsedCount).coerceAtLeast(0),
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(EngineSpacing.Inline))
    // A bridge line arrives on the clipboard, from an email or a Telegram
    // message, and typing 400 characters of certificate by hand is not a thing
    // anyone does correctly.
    ActionPill(
        label = stringResource(R.string.bridges_paste),
        onClick = onPaste,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Rounded.ContentPaste,
        enabled = enabled,
    )
    Hint(stringResource(R.string.bridges_custom_type_note))
}

// ----------------------------------------------------------------- the rows

/**
 * The second line of a bridge row: what is left once the address is on line one.
 *
 * A fingerprint is how two bridges on the same host are told apart, and a user
 * comparing what they pasted against what a friend sent them needs it - but
 * forty hex digits is not a thing anyone reads, so it is shortened at both ends.
 * The parameters are counted rather than printed: a webtunnel URL or a snowflake
 * ICE list is longer than the row and tells the user nothing they can act on.
 */
@Composable
private fun bridgeRowDetail(parsed: BridgeLine.Parsed?, runnable: Boolean): String? {
    if (parsed == null) return null
    val bits = mutableListOf<String>()
    val fingerprint = parsed.fingerprint
    if (fingerprint != null) bits += shortFingerprint(fingerprint)
    if (parsed.params.isNotEmpty()) {
        bits += stringResource(R.string.bridges_params, parsed.params.size)
    }
    if (!runnable) {
        bits += stringResource(
            R.string.bridges_needs,
            parsed.transport?.plugin?.id ?: parsed.transportName,
        )
    }
    return bits.joinToString(" \u00B7 ").ifEmpty { null }
}

/** `C5B7A1\u20267C4F`. Both ends, because bridges on one host share a prefix. */
private fun shortFingerprint(fingerprint: String): String =
    if (fingerprint.length <= 12) {
        fingerprint
    } else {
        fingerprint.take(6) + "\u2026" + fingerprint.takeLast(4)
    }

/**
 * `2026-09-09 21:15`.
 *
 * Deliberately not a relative time ("3 days ago"): the only question this answers
 * is "is this list older than the block I am trying to get past", and a date
 * answers it without a recomposing clock.
 */
private fun formatBridgeDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(epochMillis))
