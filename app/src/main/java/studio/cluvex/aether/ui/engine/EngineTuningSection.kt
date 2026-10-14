package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * The last-resort dials, as two compact cards: Engine (TLS curve list, log
 * verbosity) and Timers & retries (the two timing windows side by side, then
 * the retry switches). The defaults are right for almost everybody.
 *
 * The two timing fields are [SecondsField]s, which means the value that can be
 * typed here is exactly the value the engine will honour.
 */
@Composable
internal fun EngineTuningSection(
    tlsGroups: String,
    validateSecs: Int,
    reconnectSecs: Int,
    noDataCheck: Boolean,
    noProfileRetry: Boolean,
    coreLogLevel: CoreLogLevel,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        EngineGroup(label = stringResource(R.string.settings_group_engine)) {
            ProfileTextField(
                value = tlsGroups,
                onValueChange = { value -> edit { copy(tlsGroups = value) } },
                label = stringResource(R.string.tls_groups_label),
                placeholder = stringResource(R.string.tls_groups_hint),
                enabled = enabled,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
            FieldLabel(stringResource(R.string.core_log_level_label))
            DropdownSelector(
                options = CoreLogLevel.entries,
                selected = coreLogLevel,
                onSelect = { value -> edit { copy(coreLogLevel = value) } },
                label = { it.name },
                enabled = enabled,
            )
        }

        Spacer(Modifier.height(EngineSpacing.Group))
        EngineGroup(label = stringResource(R.string.settings_group_timers)) {
            FieldPair(
                first = { m ->
                    SecondsField(
                        value = validateSecs,
                        onValueChange = { value -> edit { copy(validateSecs = value) } },
                        label = stringResource(R.string.validate_secs_label),
                        placeholder = stringResource(R.string.secs_hint),
                        max = EngineLimits.ValidateSecs,
                        enabled = enabled,
                        modifier = m,
                    )
                },
                second = { m ->
                    SecondsField(
                        value = reconnectSecs,
                        onValueChange = { value -> edit { copy(reconnectSecs = value) } },
                        label = stringResource(R.string.reconnect_secs_label),
                        placeholder = stringResource(R.string.secs_hint),
                        max = EngineLimits.ReconnectSecs,
                        enabled = enabled,
                        modifier = m,
                    )
                },
            )
            Spacer(Modifier.height(EngineSpacing.Divider))
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.no_data_check_title),
                info = stringResource(R.string.no_data_check_desc),
                checked = noDataCheck,
                enabled = enabled,
                onChange = { value -> edit { copy(noDataCheck = value) } },
            )
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.no_profile_retry_title),
                info = stringResource(R.string.no_profile_retry_desc),
                checked = noProfileRetry,
                enabled = enabled,
                onChange = { value -> edit { copy(noProfileRetry = value) } },
            )
        }
    }
}
