package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.teamAuthLabel

/**
 * Cloudflare Zero Trust enrolment, as one compact card.
 *
 * Only the credentials the CHOSEN method needs are shown, and the two that are
 * secrets (service-token secret, enrolment JWT) are masked: an organization
 * credential must not be readable over a shoulder or land in a screenshot
 * attached to a bug report. Neither of them travels via argv - see
 * ConnectionProfile.toEnv().
 */
@Composable
internal fun ZeroTrustSection(
    teamAuth: TeamAuth,
    team: String,
    accessClientId: String,
    accessClientSecret: String,
    accessEmail: String,
    accessToken: String,
    gateway: Boolean,
    enabled: Boolean,
    edit: ProfileEdit,
    modifier: Modifier = Modifier,
) {
    EngineGroup(modifier = modifier) {
        FieldLabel(stringResource(R.string.team_auth_label), info = stringResource(R.string.team_auth_desc))
        DropdownSelector(
            options = TeamAuth.entries,
            selected = teamAuth,
            onSelect = { value -> edit { copy(teamAuth = value) } },
            label = { teamAuthLabel(it) },
            enabled = enabled,
        )

        DependentBlock(visible = teamAuth != TeamAuth.OFF) {
            Spacer(Modifier.height(EngineSpacing.Field))
            ProfileTextField(
                value = team,
                onValueChange = { value -> edit { copy(team = value) } },
                label = stringResource(R.string.team_label),
                placeholder = stringResource(R.string.team_hint),
                info = stringResource(R.string.team_help),
                enabled = enabled,
            )

            when (teamAuth) {
                TeamAuth.SERVICE_TOKEN -> {
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    ProfileTextField(
                        value = accessClientId,
                        onValueChange = { value -> edit { copy(accessClientId = value) } },
                        label = stringResource(R.string.access_id_label),
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    ProfileTextField(
                        value = accessClientSecret,
                        onValueChange = { value -> edit { copy(accessClientSecret = value) } },
                        label = stringResource(R.string.access_secret_label),
                        info = stringResource(R.string.access_secret_help),
                        masked = true,
                        enabled = enabled,
                    )
                }

                TeamAuth.EMAIL -> {
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    ProfileTextField(
                        value = accessEmail,
                        onValueChange = { value -> edit { copy(accessEmail = value) } },
                        label = stringResource(R.string.access_email_label),
                        enabled = enabled,
                    )
                }

                TeamAuth.TOKEN -> {
                    Spacer(Modifier.height(EngineSpacing.Inline))
                    ProfileTextField(
                        value = accessToken,
                        onValueChange = { value -> edit { copy(accessToken = value) } },
                        label = stringResource(R.string.access_token_label),
                        info = stringResource(R.string.access_secret_help),
                        masked = true,
                        enabled = enabled,
                    )
                }

                TeamAuth.OFF -> Unit
            }

            Spacer(Modifier.height(EngineSpacing.Inline))
            EngineDivider()
            SwitchRow(
                title = stringResource(R.string.gateway_title),
                info = stringResource(R.string.gateway_desc),
                checked = gateway,
                enabled = enabled,
                onChange = { value -> edit { copy(gateway = value) } },
            )
        }
    }
}
