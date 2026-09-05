package studio.cluvex.aether.ui.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * What happens when the tunnel drops.
 *
 * Strict lockdown is nested under the kill switch on purpose: on its own it is a
 * switch that appears to do nothing, and a user who finds it first and turns it
 * on has armed a lockdown that survives a manual disconnect without ever seeing
 * the switch that explains it.
 */
@Composable
internal fun SafetySection(
    quickReconnect: Boolean,
    killSwitch: Boolean,
    strictKillSwitch: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineSection(
        title = stringResource(R.string.section_safety),
        subtitle = stringResource(R.string.section_safety_note),
        icon = Icons.Rounded.Lock,
        headerGap = EngineSpacing.SwitchHeader,
        modifier = modifier,
    ) {
        SwitchRow(
            title = stringResource(R.string.quick_reconnect),
            description = stringResource(R.string.quick_reconnect_desc),
            checked = quickReconnect,
            enabled = enabled,
            onChange = { value -> edit { copy(quickReconnect = value) } },
        )
        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.kill_switch_title),
            description = stringResource(R.string.kill_switch_desc),
            checked = killSwitch,
            enabled = enabled,
            onChange = { value -> edit { copy(killSwitch = value) } },
        )
        DependentBlock(visible = killSwitch) {
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.strict_kill_switch_title),
                description = stringResource(R.string.strict_kill_switch_desc),
                checked = strictKillSwitch,
                enabled = enabled,
                onChange = { value -> edit { copy(strictKillSwitch = value) } },
            )
        }
    }
}
