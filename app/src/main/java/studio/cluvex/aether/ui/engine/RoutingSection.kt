package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.InfoTip
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.components.ValueRow
import studio.cluvex.aether.ui.splitLabel

/**
 * Which apps go through the tunnel, and which never reach the network.
 *
 * The two app pickers are opened, not owned, here: the dialogs are hoisted to
 * [studio.cluvex.aether.ui.EngineDestination] so their open/closed state
 * survives a rotation. This card only takes the SELECTION COUNTS, not the
 * package lists - a `List<String>` is an unstable type to Compose, and taking
 * one would make this card recompose on every unrelated keystroke elsewhere.
 */
@Composable
internal fun RoutingSection(
    proxyMode: Boolean,
    splitMode: SplitMode,
    splitAppCount: Int,
    blockedAppCount: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    onPickSplitApps: () -> Unit,
    onPickBlockedApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EngineGroup(modifier = modifier, label = stringResource(R.string.settings_group_apps)) {
        SwitchRow(
            title = stringResource(R.string.proxy_mode_title),
            info = stringResource(R.string.proxy_mode_desc),
            checked = proxyMode,
            enabled = enabled,
            onChange = { value -> edit { copy(proxyMode = value) } },
        )
        DependentBlock(visible = proxyMode) {
            ValueRow(
                label = stringResource(R.string.proxy_socks_label),
                value = "127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}",
            )
            ValueRow(
                label = stringResource(R.string.proxy_http_label),
                value = "127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
            )
            Spacer(Modifier.height(EngineSpacing.Inline))
        }
        EngineDivider()

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.split_mode))
        SegmentedSelector(
            options = SplitMode.entries,
            selected = splitMode,
            onSelect = { value -> edit { copy(splitMode = value) } },
            label = { splitLabel(it) },
            enabled = enabled,
        )
        DependentBlock(visible = splitMode != SplitMode.OFF) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            ActionPill(
                label = stringResource(R.string.split_select_apps, splitAppCount),
                onClick = onPickSplitApps,
                icon = Icons.Rounded.Apps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(EngineSpacing.Field))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionPill(
                label = stringResource(R.string.blocked_select_apps, blockedAppCount),
                onClick = onPickBlockedApps,
                icon = Icons.Rounded.Block,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            InfoTip(stringResource(R.string.blocked_apps_desc))
        }
    }
}
