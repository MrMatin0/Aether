package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * How many automatic engine restarts the user can allow. Hoisted out of the
 * composable so the list is not rebuilt on every recomposition.
 */
private val RetryLimits = listOf(3, 5, 10, 15, 20)

/**
 * Leak protection and what the app does about a session that keeps failing.
 * Expert depth only.
 *
 * The retry limit only exists while smart reconnect is on, and it is nested
 * under it for the same reason strict lockdown is nested under the kill switch:
 * on its own it is a number that quietly does nothing.
 */
@Composable
internal fun ResilienceSection(
    ipv6LeakProtection: Boolean,
    smartReconnect: Boolean,
    reconnectRetryLimit: Int,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineSection(
        title = stringResource(R.string.section_security),
        subtitle = stringResource(R.string.section_security_note),
        icon = Icons.Rounded.Refresh,
        headerGap = EngineSpacing.SwitchHeader,
        modifier = modifier,
    ) {
        SwitchRow(
            title = stringResource(R.string.ipv6_leak_title),
            description = stringResource(R.string.ipv6_leak_desc),
            checked = ipv6LeakProtection,
            enabled = enabled,
            onChange = { value -> edit { copy(ipv6LeakProtection = value) } },
        )
        EngineDivider()
        SwitchRow(
            title = stringResource(R.string.smart_reconnect_title),
            description = stringResource(R.string.smart_reconnect_desc),
            checked = smartReconnect,
            enabled = enabled,
            onChange = { value -> edit { copy(smartReconnect = value) } },
        )
        DependentBlock(visible = smartReconnect) {
            Spacer(Modifier.height(EngineSpacing.Header))
            FieldLabel(stringResource(R.string.reconnect_limit_label))
            DropdownSelector(
                options = RetryLimits,
                selected = reconnectRetryLimit,
                onSelect = { value -> edit { copy(reconnectRetryLimit = value) } },
                label = { "$it" },
                enabled = enabled,
            )
        }
    }
}
