package studio.cluvex.aether.ui.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.cluvex.aether.R
import studio.cluvex.aether.data.WgExperimentPrefs
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SwitchRow

@Composable
internal fun WgExperimentControls(protocol: Protocol, enabled: Boolean) {
    val config by WgExperimentPrefs.state.collectAsStateWithLifecycle()
    FieldLabel(stringResource(R.string.wg_lab_title))
    Hint(stringResource(R.string.wg_lab_note))
    val editable = enabled && protocol == Protocol.WIREGUARD
    SwitchRow(
        title = stringResource(R.string.wg_lab_hop_title),
        description = stringResource(R.string.wg_lab_hop_note),
        checked = config.portHopping,
        enabled = editable,
        onChange = WgExperimentPrefs::setPortHopping,
    )
    SwitchRow(
        title = stringResource(R.string.wg_lab_padding_title),
        description = stringResource(R.string.wg_lab_padding_note),
        checked = config.dataPadding,
        enabled = editable,
        onChange = WgExperimentPrefs::setDataPadding,
    )
    if (protocol != Protocol.WIREGUARD) Hint(stringResource(R.string.wg_lab_select_wg))
}
