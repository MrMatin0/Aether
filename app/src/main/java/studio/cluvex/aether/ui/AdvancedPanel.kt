package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.engine.ConnectionSection
import studio.cluvex.aether.ui.engine.EngineSpacing
import studio.cluvex.aether.ui.engine.EngineTuningSection
import studio.cluvex.aether.ui.engine.ProfileEdit
import studio.cluvex.aether.ui.engine.ResetSection
import studio.cluvex.aether.ui.engine.ResilienceSection
import studio.cluvex.aether.ui.engine.RoutesSection
import studio.cluvex.aether.ui.engine.RoutingSection
import studio.cluvex.aether.ui.engine.SafetySection
import studio.cluvex.aether.ui.engine.TransportSection
import studio.cluvex.aether.ui.engine.ZeroTrustSection

/** How much of the engine surface is on screen. */
private enum class Depth { BASIC, FULL }

/** Which app list the one hoisted picker dialog is currently editing. */
private enum class AppPicker { SPLIT, BLOCKED }

/**
 * The engine settings page.
 *
 * ROOT CAUSE THIS FIXES (usability): the original panel presented roughly forty
 * controls as one flat list inside a collapsed card. Protocol - which decides
 * whether you connect at all - sat at the same visual level as the TLS curve
 * list, so people changed MTU or fragment sizes at random, could not get back to
 * a working configuration, and reported the tunnel as broken. Nothing said which
 * controls matter, and nothing said the defaults are usually right.
 *
 * The Essentials / Everything split fixed the WHAT and the card-per-group pass
 * fixed the SHAPE: every group is a card with a badge and a name, so a scroll
 * through the page has landmarks and one card is the unit a screenshot can be
 * cropped to when someone asks for help.
 *
 * WHAT THIS PASS FIXES: the file itself. All ten cards were inlined in ONE
 * 730-line composable, which is how three real bugs survived in it -
 * fragmentation's two inputs sat in a different card from the switch that owns
 * them, the two "seconds" fields accepted numbers the engine silently clamps,
 * and an open app picker did not survive a rotation. Each card is now its own
 * file under ui/engine, this function is the table of contents, and the repeated
 * shapes (card + header, "reveal these extra controls", technical text field,
 * seconds field) live once in EngineSettingsKit.
 *
 * Cards receive only the fields they render plus one stable [ProfileEdit]
 * callback, instead of the whole profile: a keystroke allocates a new
 * ConnectionProfile, and while every card took the whole thing, every card
 * recomposed on every keystroke anywhere on the page.
 *
 * The scan mode is the one control here that is not a dropdown: it is a
 * three-option radio group that states what each mode does and how long it
 * takes (see ScanModeSelector). It decides how long the user sits on the
 * connecting screen, which is not something a collapsed one-word row can say.
 *
 * The depth choice is [rememberSaveable]: it used to reset to Essentials on
 * every rotation and every return to the page, which is maddening when you are
 * three fields deep in Everything.
 *
 * Every field still writes to exactly the same ConnectionProfile property
 * through the same synchronous onProfileChange path, so the scrambled-input fix
 * and the DataStore write behaviour are untouched. Technical fields still go
 * through LtrOutlinedTextField - never a bare OutlinedTextField.
 *
 * [startExpanded] is kept for source compatibility with older callers; the
 * surface is a page now, so it is always open.
 */
@Composable
fun AdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") startExpanded: Boolean = false,
) {
    var depth by rememberSaveable { mutableStateOf(Depth.BASIC) }

    // Hoisted out of the two cards that open it, and saveable: both pickers used
    // to be local `remember` flags, so rotating the phone with the app list open
    // threw the dialog away along with whatever the user had ticked in it.
    var picker by rememberSaveable { mutableStateOf<AppPicker?>(null) }

    val full = depth == Depth.FULL

    // One callback for the whole page, and it must stay the SAME instance while
    // the user types. It reads the profile through rememberUpdatedState instead
    // of capturing it, because capturing would rebuild the lambda on every
    // keystroke and un-skip every card underneath.
    val latest by rememberUpdatedState(profile)
    val edit: ProfileEdit = remember(onProfileChange) {
        { change -> onProfileChange(latest.change()) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        DepthSelector(depth = depth, onSelect = { value -> depth = value })

        Spacer(Modifier.height(EngineSpacing.Card))
        ConnectionSection(
            protocol = profile.protocol,
            scanMode = profile.scanMode,
            endpointMode = profile.endpointMode,
            manualPeer = profile.manualPeer,
            manualRange = profile.manualRange,
            ipVersion = profile.ipVersion,
            enabled = enabled,
            edit = edit,
        )

        Spacer(Modifier.height(EngineSpacing.Card))
        SafetySection(
            quickReconnect = profile.quickReconnect,
            killSwitch = profile.killSwitch,
            strictKillSwitch = profile.strictKillSwitch,
            enabled = enabled,
            edit = edit,
        )

        Spacer(Modifier.height(EngineSpacing.Card))
        RoutingSection(
            proxyMode = profile.proxyMode,
            splitMode = profile.splitMode,
            splitAppCount = profile.splitApps.size,
            blockedAppCount = profile.blockedApps.size,
            enabled = enabled,
            edit = edit,
            onPickSplitApps = { picker = AppPicker.SPLIT },
            onPickBlockedApps = { picker = AppPicker.BLOCKED },
        )

        if (full) {
            Spacer(Modifier.height(EngineSpacing.Card))
            TransportSection(
                noize = profile.noize,
                keepalive = profile.keepalive,
                mtu = profile.mtu,
                fragment = profile.fragment,
                fragmentSize = profile.fragmentSize,
                fragmentDelay = profile.fragmentDelay,
                ech = profile.ech,
                masqueHttp2 = profile.masqueHttp2,
                dnsServers = profile.dnsServers,
                enabled = enabled,
                edit = edit,
            )

            Spacer(Modifier.height(EngineSpacing.Card))
            RoutesSection(
                routeBlock = profile.routeBlock,
                routeDirect = profile.routeDirect,
                enabled = enabled,
                edit = edit,
            )

            Spacer(Modifier.height(EngineSpacing.Card))
            ZeroTrustSection(
                teamAuth = profile.teamAuth,
                team = profile.team,
                accessClientId = profile.accessClientId,
                accessClientSecret = profile.accessClientSecret,
                accessEmail = profile.accessEmail,
                accessToken = profile.accessToken,
                gateway = profile.gateway,
                enabled = enabled,
                edit = edit,
            )

            Spacer(Modifier.height(EngineSpacing.Card))
            ResilienceSection(
                ipv6LeakProtection = profile.ipv6LeakProtection,
                smartReconnect = profile.smartReconnect,
                reconnectRetryLimit = profile.reconnectRetryLimit,
                enabled = enabled,
                edit = edit,
            )

            Spacer(Modifier.height(EngineSpacing.Card))
            EngineTuningSection(
                tlsGroups = profile.tlsGroups,
                validateSecs = profile.validateSecs,
                reconnectSecs = profile.reconnectSecs,
                noDataCheck = profile.noDataCheck,
                noProfileRetry = profile.noProfileRetry,
                coreLogLevel = profile.coreLogLevel,
                enabled = enabled,
                edit = edit,
            )
        }

        // Always reachable, in both depths: the way out of a broken
        // configuration must never be hidden behind an expert toggle.
        Spacer(Modifier.height(EngineSpacing.Card))
        ResetSection(enabled = enabled, edit = edit)
    }

    // ONE dialog for both app lists. There used to be two near-identical copies
    // of this block, each with its own flag, which is how they ended up with two
    // different lifetimes.
    picker?.let { target ->
        AppPickerDialog(
            selected = when (target) {
                AppPicker.SPLIT -> profile.splitApps
                AppPicker.BLOCKED -> profile.blockedApps
            },
            onDismiss = { picker = null },
            onConfirm = { apps ->
                edit {
                    when (target) {
                        AppPicker.SPLIT -> copy(splitApps = apps)
                        AppPicker.BLOCKED -> copy(blockedApps = apps)
                    }
                }
                picker = null
            },
        )
    }
}

/**
 * Essentials or Everything.
 *
 * Deliberately NOT gated on [AdvancedPanel]'s `enabled`: reading your own
 * configuration must keep working while the tunnel is up and the fields
 * themselves are locked.
 */
@Composable
private fun DepthSelector(
    depth: Depth,
    onSelect: (Depth) -> Unit,
    modifier: Modifier = Modifier,
) {
    AetherCard(modifier = modifier) {
        SegmentedSelector(
            options = Depth.entries,
            selected = depth,
            onSelect = onSelect,
            label = {
                when (it) {
                    Depth.BASIC -> stringResource(R.string.settings_mode_basic)
                    Depth.FULL -> stringResource(R.string.settings_mode_full)
                }
            },
        )
        Hint(stringResource(R.string.settings_expert_note))
    }
}
