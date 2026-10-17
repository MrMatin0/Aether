package studio.cluvex.aether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreAvailability
import studio.cluvex.aether.core.PsiphonRegions
import studio.cluvex.aether.core.XrayOutbound
import studio.cluvex.aether.model.ChainMode
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.CoreRoutes
import studio.cluvex.aether.model.Hop
import studio.cluvex.aether.model.PsiphonProtocol
import studio.cluvex.aether.model.SstpSource
import studio.cluvex.aether.model.TorBridgeMode
import studio.cluvex.aether.model.Underlay
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

/*
 * THE CORE SELECTOR: the connection tab's one decision, right under the orb.
 *
 *   [ Aether ] [ Psiphon ] [ Tor ] [ SSTP ] [ Xray ]
 *   ( Psiphon · 🇩🇪 Germany · CDN fronting · via Aether   > )
 *
 * WHY CORES AND NOT ROUTES. The deck it replaces offered fourteen route
 * names ("Tor over Psiphon over Aether") before a user could see a single
 * option of the core they actually wanted. People choose a CORE; what it
 * rides on is a property of that choice. So the pills pick the chain entry
 * (ChainMode.entryHop) and each core's sheet picks its underlay. Both views
 * come from model/CoreRoute.kt, which derives the pair from ChainMode.hops,
 * so nothing new is persisted and the two can never disagree.
 *
 * ONE SUMMARY, NOT THREE TILES. Under the pills sits one card that says, in
 * one line, what the selected core will do on the next connect. Tapping it,
 * or tapping the pill that is already selected, opens that core's sheet
 * (CoreSheets.kt). Tapping another pill switches cores at once: it is the
 * cheapest possible change, and the sheet is one more tap away.
 *
 * HONEST STATES.
 *   LOCKED   the tunnel is up or on its way: pills and summary stay visible
 *            (they still say what is running) but are disabled, the summary
 *            shows a lock, and an open sheet is closed by ConnectionHome.
 *   MISSING  a core this APK does not ship: its pill is dimmed, and tapping
 *            it opens a sheet that names the missing file instead of
 *            switching to a route that cannot start.
 *
 * PROPORTIONS (8dp grid). Pills share the width equally: min 56dp tall (64dp
 * when ROOMY), a 20dp icon above a labelMedium name, 8dp apart. TIGHT and
 * MINIMAL windows get 44dp icon-only pills and a 44dp summary; Compose
 * extends a smaller node's touch bounds to the 48dp minimum, and there is
 * free space above and below for it. Large text (1.3x and up) or a block
 * narrower than 320dp lets the pills scroll sideways rather than clip a core
 * name: "Psiph" tells nobody anything. [coreSelectorHeight] is what
 * [heroReserve] budgets, so the orb keeps one size in every state.
 */

/**
 * The ONE rule for "may the profile change now": at rest, or after a failure
 * (which is a retry). Same rule the Settings hub uses for its lock; HomeScreen
 * computes it once for the whole shell.
 */
internal fun quickControlsEditable(state: ConnectionState): Boolean =
    state is ConnectionState.Idle || state is ConnectionState.Error

/** Why an underlay cannot be picked right now. */
internal enum class UnderlayBlock {
    /** Not one of the fourteen modes (SSTP via anything). Never offered. */
    UNSUPPORTED,

    /** A core of that route is not in this APK. */
    CORE_MISSING,

    /** The route has an Xray hop and no usable Xray config is saved. */
    XRAY_CONFIG_MISSING,
}

/**
 * Whether [core] may ride on [underlay] here, or why not. [xrayReady] is "a
 * config the parser accepts is saved", not merely "some text is saved": a
 * config Xray cannot run would fail the connect just the same.
 */
internal fun underlayBlock(
    core: Hop,
    underlay: Underlay,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
): UnderlayBlock? {
    val mode = CoreRoutes.modeOf(core, underlay) ?: return UnderlayBlock.UNSUPPORTED
    return when {
        !cores.canRun(mode) -> UnderlayBlock.CORE_MISSING
        underlay.usesXray && !xrayReady -> UnderlayBlock.XRAY_CONFIG_MISSING
        else -> null
    }
}

/**
 * Whether a pill tap may land on [mode]: every core ships, and a STACKED Xray
 * route has a config to run. Xray alone is always allowed: that is where the
 * config gets pasted.
 */
internal fun coreModeAllowed(
    mode: ChainMode,
    cores: CoreAvailability.Snapshot,
    xrayReady: Boolean,
): Boolean = cores.canRun(mode) && (mode == ChainMode.XRAY || !mode.usesXray || xrayReady)

/**
 * The Settings page that owns the rest of [core]'s options: what the end of
 * its sheet links to.
 */
internal fun coreSettingsPage(core: Hop): SettingsPage = when (core) {
    Hop.AETHER -> SettingsPage.CONNECTION
    Hop.TOR -> SettingsPage.BRIDGES
    Hop.PSIPHON, Hop.SSTP, Hop.XRAY -> SettingsPage.CHAIN
}

/** Below this block width the labelled pills scroll instead of sharing it. */
internal val CORE_PILLS_SCROLL_BELOW = 320.dp

/** Between two pills, and between the pills and the summary. */
internal val CORE_SELECTOR_GAP = 8.dp

/** Narrowest a labelled pill gets once the row scrolls. */
private val CORE_PILL_SCROLL_MIN = 72.dp

/** The pill colour crossfade. Short and linear-feeling: no scale, no bounce. */
private const val CORE_PILL_FADE_MS = 150

/**
 * Equal-width pills, or a sideways scroll: large text and narrow windows
 * scroll, so a core name is never clipped or ellipsized.
 */
internal fun corePillsScroll(width: Dp, fontScale: Float): Boolean =
    fontScale >= 1.3f || width < CORE_PILLS_SCROLL_BELOW

/** TIGHT and MINIMAL windows: icons only (TalkBack still reads the name). */
internal fun corePillsIconOnly(density: HomeDensity): Boolean = density >= HomeDensity.TIGHT

/** Minimum pill height at [density]. */
internal fun corePillHeight(density: HomeDensity): Dp = when (density) {
    HomeDensity.ROOMY -> 64.dp
    HomeDensity.COMPACT -> 56.dp
    HomeDensity.TIGHT, HomeDensity.MINIMAL -> 44.dp
}

/** Minimum summary card height at [density]: one line either way. */
internal fun coreSummaryHeight(density: HomeDensity): Dp =
    if (corePillsIconOnly(density)) 44.dp else 52.dp

/**
 * The whole block at [density] and font scale 1: pills, gap, summary. This is
 * what [heroReserve] budgets for it.
 */
internal fun coreSelectorHeight(density: HomeDensity): Dp =
    corePillHeight(density) + CORE_SELECTOR_GAP + coreSummaryHeight(density)

/**
 * "Psiphon · 🇩🇪 Germany · CDN fronting · via Aether": the parts that have
 * something to say, in order, joined by a middle dot.
 */
internal fun coreSummaryLine(parts: List<String>): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" \u00B7 ")

/** "Direct", "via Aether", "via Psiphon + Aether"... read from the core outwards. */
@Composable
internal fun underlayLabel(underlay: Underlay): String = stringResource(
    when (underlay) {
        Underlay.DIRECT -> R.string.cs_underlay_direct
        Underlay.AETHER -> R.string.cs_underlay_aether
        Underlay.PSIPHON -> R.string.cs_underlay_psiphon
        Underlay.PSIPHON_AETHER -> R.string.cs_underlay_psiphon_aether
        Underlay.XRAY -> R.string.cs_underlay_xray
        Underlay.AETHER_XRAY -> R.string.cs_underlay_aether_xray
    },
)

/**
 * The summary card's one line for [profile]'s current core. Only what is
 * doing something: Aether's scan mode gives way to "Pinned endpoint", an
 * Automatic Psiphon protocol and a Direct underlay are left out.
 */
@Composable
internal fun coreSummary(
    profile: ConnectionProfile,
    cores: CoreAvailability.Snapshot,
    xray: XrayOutbound?,
): String {
    val chain = profile.chain
    val core = chain.entryHop
    if (!cores.has(core)) {
        return coreSummaryLine(listOf(core.label, stringResource(R.string.cs_pill_missing)))
    }
    val underlay = CoreRoutes.underlayOf(chain)
    val via = if (underlay == Underlay.DIRECT) "" else underlayLabel(underlay)
    val parts: List<String> = when (core) {
        Hop.AETHER -> listOf(
            protocolLabel(profile.protocol),
            if (profile.hasManualPeer) {
                stringResource(R.string.cs_summary_pinned)
            } else {
                scanLabel(profile.scanMode.effectiveFor(BuildConfig.CORE_VERSION))
            },
            via,
        )
        Hop.PSIPHON -> {
            val region = PsiphonRegions.sanitize(profile.psiphonRegion)
            listOf(
                if (region == PsiphonRegions.AUTOMATIC) {
                    "${PsiphonRegions.GLOBE} " + stringResource(R.string.psiphon_region_auto)
                } else {
                    "${PsiphonRegions.flag(region)} ${PsiphonRegions.name(region)}"
                },
                if (profile.psiphonProtocol == PsiphonProtocol.AUTO) "" else psiphonProtocolLabel(profile.psiphonProtocol),
                via,
            )
        }
        Hop.TOR -> listOf(
            when (profile.torBridgeMode) {
                TorBridgeMode.OFF -> stringResource(R.string.cs_summary_no_bridges)
                TorBridgeMode.CUSTOM -> stringResource(R.string.cs_summary_custom_bridges)
                TorBridgeMode.BUILTIN, TorBridgeMode.REQUESTED -> stringResource(
                    R.string.cs_summary_bridges,
                    bridgeTransportLabel(profile.torBridgeTransport),
                )
            },
            via,
        )
        Hop.SSTP -> {
            val config = profile.sstpConfig
            val locale = LocalConfiguration.current.locales[0]
            listOf(
                when {
                    config.hostname.isBlank() -> stringResource(R.string.cs_summary_no_server)
                    config.source == SstpSource.VPNGATE && config.countryCode.isNotBlank() ->
                        CountryFlags.flag(config.countryCode) + " " +
                            CountryFlags.displayName(
                                config.countryCode,
                                locale,
                                config.countryName.ifBlank { config.hostname },
                            )
                    // LTR-isolated: a hostname must not be reordered in Persian.
                    else -> "\u2066${config.hostname}\u2069"
                },
            )
        }
        Hop.XRAY -> listOf(
            if (xray == null) {
                stringResource(R.string.cs_summary_no_config)
            } else {
                val security = xray.security.trim().lowercase(Locale.ROOT)
                coreSummaryLine(
                    listOf(
                        xray.summary.substringBefore(" \u00B7 "),
                        if (security.isEmpty() || security == "none") "" else security.uppercase(Locale.ROOT),
                    ),
                )
            },
        )
    }
    return coreSummaryLine(listOf(core.label) + parts)
}

/**
 * The pills and the summary card. Stateless: [onPill] decides whether a tap
 * switches cores or opens a sheet (ConnectionHome), [onOpenSheet] opens the
 * selected core's sheet.
 */
@Composable
internal fun CoreSelector(
    selected: Hop,
    summary: String,
    editable: Boolean,
    density: HomeDensity,
    cores: CoreAvailability.Snapshot,
    onPill: (Hop) -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconOnly = corePillsIconOnly(density)
    val pillHeight = corePillHeight(density)
    val fontScale = LocalDensity.current.fontScale
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CORE_SELECTOR_GAP)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scrolls = !iconOnly && corePillsScroll(maxWidth, fontScale)
            // Row mirrors itself in RTL, so the Persian order reads from the right.
            if (scrolls) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(CORE_SELECTOR_GAP),
                ) {
                    CoreRoutes.cores.forEach { core ->
                        CorePill(
                            core = core,
                            selected = core == selected,
                            missing = !cores.has(core),
                            locked = !editable,
                            iconOnly = false,
                            height = pillHeight,
                            onClick = { onPill(core) },
                            modifier = Modifier.widthIn(min = CORE_PILL_SCROLL_MIN),
                        )
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(CORE_SELECTOR_GAP),
                ) {
                    CoreRoutes.cores.forEach { core ->
                        CorePill(
                            core = core,
                            selected = core == selected,
                            missing = !cores.has(core),
                            locked = !editable,
                            iconOnly = iconOnly,
                            height = pillHeight,
                            onClick = { onPill(core) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        CoreSummaryCard(
            core = selected,
            summary = summary,
            locked = !editable,
            height = coreSummaryHeight(density),
            onClick = onOpenSheet,
        )
    }
}

/**
 * One core. Selected: brand wash, 1dp brand border, tinted icon. Otherwise the
 * card fill and border. The three colours crossfade in [CORE_PILL_FADE_MS]
 * (instant under reduced motion, via [aetherDuration]); nothing scales.
 *
 * A Tab for TalkBack, inside the row's selectableGroup, so "selected" and the
 * position are announced. Icon-only pills hand the name to the icon.
 */
@Composable
private fun CorePill(
    core: Hop,
    selected: Boolean,
    missing: Boolean,
    locked: Boolean,
    iconOnly: Boolean,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val duration = aetherDuration(CORE_PILL_FADE_MS)
    val fill by animateColorAsState(
        targetValue = if (selected) accents.brandWash else accents.card,
        animationSpec = tween(duration, easing = AetherEaseOut),
        label = "core-pill-fill",
    )
    val edge by animateColorAsState(
        targetValue = if (selected) accents.brand else accents.cardBorder,
        animationSpec = tween(duration, easing = AetherEaseOut),
        label = "core-pill-edge",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) accents.brand else colors.onSurfaceVariant,
        animationSpec = tween(duration, easing = AetherEaseOut),
        label = "core-pill-ink",
    )
    val shape = RoundedCornerShape(AetherRadius.Card)
    val missingWords = stringResource(R.string.cs_pill_missing)
    Column(
        modifier
            .heightIn(min = height)
            .alpha(if (missing) 0.45f else 1f)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .selectable(
                selected = selected,
                enabled = !locked,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { if (missing) stateDescription = missingWords }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            hopIcon(core),
            contentDescription = if (iconOnly) core.label else null,
            modifier = Modifier.size(20.dp),
            tint = ink,
        )
        if (!iconOnly) {
            Spacer(Modifier.height(4.dp))
            // Never ellipsized: a row that cannot fit the names scrolls instead.
            Text(
                core.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * What the selected core will do on the next connect, in one line, and the way
 * into its sheet. A Button for TalkBack with an onClickLabel naming the core;
 * a lock instead of the chevron while the profile is locked.
 */
@Composable
private fun CoreSummaryCard(
    core: Hop,
    summary: String,
    locked: Boolean,
    height: Dp,
    onClick: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AetherRadius.Card)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = !locked,
                onClickLabel = stringResource(R.string.cs_summary_open, core.label),
                role = Role.Button,
                onClick = onClick,
            ),
        shape = shape,
        color = accents.card,
        border = BorderStroke(1.dp, accents.cardBorder),
    ) {
        Row(
            Modifier
                .heightIn(min = height)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                hopIcon(core),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (locked) colors.onSurfaceVariant else accents.brand,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                summary,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            if (locked) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.qc_locked),
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant,
                )
            } else {
                // AutoMirrored: points left in Persian, towards the end.
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}
