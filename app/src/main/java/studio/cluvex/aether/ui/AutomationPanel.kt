package studio.cluvex.aether.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.SectionRule
import studio.cluvex.aether.ui.components.SwitchRow

/**
 * Automation (1.3.0).
 *
 * The three switches answer the three "why is it not on" complaints: the app
 * was opened and nothing happened, the phone rebooted and nothing happened,
 * and "what exactly are you storing about my sessions".
 *
 * The two buttons underneath open the only two SYSTEM settings that decide
 * whether a tunnel survives in the background. We cannot toggle either from
 * here -- no app can -- so they take you straight to the screen instead of
 * telling you to go hunting for it.
 */
@Composable
fun AutomationPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val behaviour by AppPrefs.state.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        SectionRule(label = stringResource(R.string.automation_title))
        Text(
            text = stringResource(R.string.automation_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Hairline(alpha = 0.55f)
        SwitchRow(
            title = stringResource(R.string.auto_launch_title),
            description = stringResource(R.string.auto_launch_desc),
            checked = behaviour.autoConnectOnLaunch,
            enabled = true,
            onChange = { on ->
                AppPrefs.update(context, behaviour.copy(autoConnectOnLaunch = on))
            },
        )
        Hairline(alpha = 0.55f)
        SwitchRow(
            title = stringResource(R.string.auto_boot_title),
            description = stringResource(R.string.auto_boot_desc),
            checked = behaviour.autoConnectOnBoot,
            enabled = true,
            onChange = { on ->
                AppPrefs.update(context, behaviour.copy(autoConnectOnBoot = on))
            },
        )
        Hairline(alpha = 0.55f)
        SwitchRow(
            title = stringResource(R.string.history_keep_title),
            description = stringResource(R.string.history_keep_desc),
            checked = behaviour.keepHistory,
            enabled = true,
            onChange = { on -> AppPrefs.update(context, behaviour.copy(keepHistory = on)) },
        )
        Hairline(alpha = 0.55f)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionPill(
                label = stringResource(R.string.automation_always_on),
                icon = Icons.Rounded.VpnKey,
                onClick = { openSystemScreen(context, Settings.ACTION_VPN_SETTINGS) },
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                label = stringResource(R.string.automation_battery),
                icon = Icons.Rounded.BatteryAlert,
                onClick = {
                    openSystemScreen(
                        context,
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
        Hint(stringResource(R.string.automation_system_hint))
    }
}

/**
 * Vendor ROMs rename and remove system activities freely, so this can and does
 * fail. Say so instead of throwing.
 */
private fun openSystemScreen(context: Context, action: String) {
    val opened = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, R.string.automation_open_failed, Toast.LENGTH_LONG).show()
    }
}
