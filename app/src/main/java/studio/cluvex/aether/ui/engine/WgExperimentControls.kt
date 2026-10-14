package studio.cluvex.aether.ui.engine

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.R
import studio.cluvex.aether.data.WgExperimentPrefs
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * WireGuard-only experiments. The explanation sits behind the (?); the one
 * line that stays visible is the reason the switches are disabled, because a
 * greyed-out switch with no reason reads as a bug.
 */
@Composable
internal fun WgExperimentControls(protocol: Protocol, enabled: Boolean) {
    val config by WgExperimentPrefs.state.collectAsStateWithLifecycle()
    val isWg = protocol == Protocol.WIREGUARD
    FieldLabel(stringResource(R.string.wg_lab_title), info = stringResource(R.string.wg_lab_note))
    if (!isWg) {
        Text(
            stringResource(R.string.wg_lab_select_wg),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    val editable = enabled && isWg
    SwitchRow(
        title = stringResource(R.string.wg_lab_hop_title),
        info = stringResource(R.string.wg_lab_hop_note),
        checked = config.portHopping,
        enabled = editable,
        onChange = WgExperimentPrefs::setPortHopping,
    )
    SwitchRow(
        title = stringResource(R.string.wg_lab_padding_title),
        info = stringResource(R.string.wg_lab_padding_note),
        checked = config.dataPadding,
        enabled = editable,
        onChange = WgExperimentPrefs::setDataPadding,
    )
}
