package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ProteusArgs
import studio.cluvex.aether.core.ProteusMode
import studio.cluvex.aether.core.ProteusSettings
import studio.cluvex.aether.core.ProteusSettingsStore
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.FieldLabel
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * EXPERIMENTAL: the Proteus options, shown under the selected Proteus mode on
 * the Chain page (same placement rule as Psiphon's options).
 *
 * These are NOT ConnectionProfile fields while the feature is experimental:
 * they live in [ProteusSettingsStore], are saved on every edit, and are read by
 * ChainStack at connect time. So they are local to this device and are not part
 * of a profile export, which the ready notice says out loud.
 */
@Composable
internal fun ProteusOptions(enabled: Boolean) {
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    var settings by remember(context) { mutableStateOf(ProteusSettingsStore.load(context)) }
    val update: (ProteusSettings) -> Unit = { next ->
        settings = next
        ProteusSettingsStore.save(context, next)
    }

    // Resolved OUTSIDE the label lambdas: the dropdown renders them per row.
    val modeLabels = mapOf(
        ProteusMode.TUNNEL to stringResource(R.string.proteus_mode_tunnel),
        ProteusMode.STREAM to stringResource(R.string.proteus_mode_stream),
    )
    val modeDescription = when (settings.mode) {
        ProteusMode.TUNNEL -> stringResource(R.string.proteus_mode_tunnel_desc)
        ProteusMode.STREAM -> stringResource(R.string.proteus_mode_stream_desc)
    }
    val persistOn = stringResource(R.string.proteus_persist_on)
    val persistOff = stringResource(R.string.proteus_persist_off)
    val serverValid = remember(settings.server) { ProteusArgs.parseServer(settings.server) != null }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        NoticeBar(
            text = stringResource(R.string.proteus_experimental),
            tone = accents.working,
            icon = Icons.Rounded.Science,
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        ProfileTextField(
            value = settings.server,
            onValueChange = { value -> update(settings.copy(server = value)) },
            label = stringResource(R.string.proteus_server_label),
            enabled = enabled,
            placeholder = stringResource(R.string.proteus_server_hint),
            helpText = if (settings.server.isNotBlank() && !serverValid) {
                stringResource(R.string.proteus_server_invalid)
            } else {
                null
            },
            singleLine = true,
        )

        Spacer(Modifier.height(EngineSpacing.Inline))
        ProfileTextField(
            value = settings.psf,
            onValueChange = { value -> update(settings.copy(psf = value)) },
            label = stringResource(R.string.proteus_psf_label),
            enabled = enabled,
            placeholder = stringResource(R.string.proteus_psf_hint),
            helpText = stringResource(R.string.proteus_psf_help),
            singleLine = false,
        )

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.proteus_mode_label))
        Spacer(Modifier.height(EngineSpacing.Inline))
        DropdownSelector(
            options = ProteusMode.entries.toList(),
            selected = settings.mode,
            onSelect = { choice -> update(settings.copy(mode = choice)) },
            label = { choice -> modeLabels[choice] ?: choice.wire },
            enabled = enabled,
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(modeDescription)

        Spacer(Modifier.height(EngineSpacing.Field))
        FieldLabel(stringResource(R.string.proteus_persist_label))
        Spacer(Modifier.height(EngineSpacing.Inline))
        DropdownSelector(
            options = listOf(false, true),
            selected = settings.persist,
            onSelect = { choice -> update(settings.copy(persist = choice)) },
            label = { choice -> if (choice) persistOn else persistOff },
            enabled = enabled,
        )
        Spacer(Modifier.height(EngineSpacing.Inline))
        Hint(stringResource(R.string.proteus_persist_help))

        Spacer(Modifier.height(EngineSpacing.Field))
        if (settings.isComplete) {
            NoticeBar(
                text = stringResource(R.string.proteus_ready),
                tone = accents.protected,
                icon = Icons.Rounded.CheckCircle,
            )
        } else {
            NoticeBar(
                text = stringResource(R.string.proteus_not_configured),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
        }
    }
}
