package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.*

/** Version facts first; credits and feature descriptions use the active locale. */
@Composable
fun AboutPanel(modifier: Modifier = Modifier) {
    var showFeatures by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: BuildConfig.VERSION_NAME
    }
    val expandedLabel = stringResource(if (showFeatures) R.string.ux_expanded else R.string.ux_collapsed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        AetherCard {
            CardHeader(stringResource(R.string.about_build_title), subtitle = stringResource(R.string.about_build_sub), icon = Icons.Rounded.Info)
            StatTile(stringResource(R.string.about_version, "").trim(), version, modifier = Modifier.fillMaxWidth())
            StatTile(stringResource(R.string.about_core_version, "").trim(), BuildConfig.CORE_VERSION, modifier = Modifier.fillMaxWidth())
        }
        AetherCard {
            CardHeader(stringResource(R.string.about_links_title), subtitle = stringResource(R.string.about_original_note), icon = Icons.Rounded.Link)
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.about_original_title))
            LinkRow(R.drawable.ic_github, "github.com/CluvexStudio/Aether", "https://github.com/CluvexStudio/Aether")
            LinkRow(R.drawable.ic_telegram, "t.me/CluvexStudio", "https://t.me/CluvexStudio")
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.about_port_title))
            LinkRow(R.drawable.ic_github, "github.com/QW-AI-Code", "https://github.com/QW-AI-Code")
        }
        AetherCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { showFeatures = !showFeatures }
                .semantics { stateDescription = expandedLabel }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.about_subtitle), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Icon(if (showFeatures) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (showFeatures) {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.about_original_title))
                FeatureList(stringArrayResource(R.array.ux_original_features))
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.about_port_title))
                FeatureList(stringArrayResource(R.array.ux_android_features))
            }
        }
    }
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
        if (runCatching { uriHandler.openUri(url) }.isFailure) {
            Toast.makeText(context, R.string.ux_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(iconRes), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FeatureList(items: Array<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item -> Text(item, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
