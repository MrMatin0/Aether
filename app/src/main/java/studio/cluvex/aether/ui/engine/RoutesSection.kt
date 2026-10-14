package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R

/**
 * Per-destination rules: what never leaves the device, and what skips the
 * tunnel. The format help is behind the (?) on the card label.
 *
 * Both lists are multi-line and both are sanitised by
 * [studio.cluvex.aether.model.ConnectionProfile.sanitizedRules] before they can
 * ever become an engine argument, so a malformed entry is dropped rather than
 * split into extra argv tokens.
 */
@Composable
internal fun RoutesSection(
    routeBlock: String,
    routeDirect: String,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineGroup(
        modifier = modifier,
        label = stringResource(R.string.section_routes),
        info = stringResource(R.string.routes_help),
    ) {
        ProfileTextField(
            value = routeBlock,
            onValueChange = { value -> edit { copy(routeBlock = value) } },
            label = stringResource(R.string.route_block_label),
            placeholder = stringResource(R.string.route_block_hint),
            singleLine = false,
            enabled = enabled,
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        ProfileTextField(
            value = routeDirect,
            onValueChange = { value -> edit { copy(routeDirect = value) } },
            label = stringResource(R.string.route_direct_label),
            placeholder = stringResource(R.string.route_direct_hint),
            singleLine = false,
            enabled = enabled,
        )
    }
}
