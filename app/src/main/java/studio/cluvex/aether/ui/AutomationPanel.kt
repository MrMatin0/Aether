package studio.cluvex.aether.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.ui.components.*

@Composable
fun AutomationPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val behaviour by AppPrefs.state.collectAsState()
    Column(modifier.fillMaxWidth()) {
        ControlSection(stringResource(R.string.automation_title), stringResource(R.string.automation_subtitle), Icons.Rounded.Schedule) {
            SwitchRow(stringResource(R.string.auto_launch_title), stringResource(R.string.auto_launch_desc), behaviour.autoConnectOnLaunch,
                enabled = true, onChange = { AppPrefs.update(context, behaviour.copy(autoConnectOnLaunch = it)) })
            Hairline()
            SwitchRow(stringResource(R.string.auto_boot_title), stringResource(R.string.auto_boot_desc), behaviour.autoConnectOnBoot,
                enabled = true, onChange = { AppPrefs.update(context, behaviour.copy(autoConnectOnBoot = it)) })
            Hairline()
            SwitchRow(stringResource(R.string.history_keep_title), stringResource(R.string.history_keep_desc), behaviour.keepHistory,
                enabled = true, onChange = { AppPrefs.update(context, behaviour.copy(keepHistory = it)) })
        }
        Spacer(Modifier.height(32.dp))
        ControlSection(stringResource(R.string.automation_system_title), stringResource(R.string.automation_system_sub), Icons.Rounded.Settings) {
            // Full-width destinations avoid squeezing translated system-setting names.
            NavRow(stringResource(R.string.automation_always_on), onClick = { openSystemScreen(context, Settings.ACTION_VPN_SETTINGS) }, icon = Icons.Rounded.VpnKey)
            Hairline()
            NavRow(stringResource(R.string.automation_battery), onClick = { openSystemScreen(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }, icon = Icons.Rounded.BatteryAlert)
            Hint(stringResource(R.string.automation_system_hint))
        }
    }
}
private fun openSystemScreen(context: Context, action: String) {
    if (runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
        Toast.makeText(context, R.string.automation_open_failed, Toast.LENGTH_LONG).show()
}
