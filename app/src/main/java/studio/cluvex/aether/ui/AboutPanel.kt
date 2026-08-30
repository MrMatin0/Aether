package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.StatTile
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents

private const val URL_ORIGINAL_GITHUB = "https://github.com/CluvexStudio/Aether"
private const val URL_ORIGINAL_TELEGRAM = "https://t.me/CluvexStudio"
private const val URL_PORT_GITHUB = "https://github.com/QW-AI-Code"

// Deliberately English-only, mirroring the upstream README's feature list.
private val ORIGINAL_FEATURES = listOf(
    "Automatic endpoint discovery with end-to-end data-plane validation",
    "MASQUE (HTTP/3 & HTTP/2) with optional TLS ClientHello fragmentation",
    "WireGuard and nested WireGuard (WARP-in-WARP \"gool\")",
    "Traffic obfuscation for DPI-heavy networks",
    "Automatic reconnection with quick-reconnect to the last good gateway",
    "Local SOCKS5 proxy — CLI for Linux, Windows, macOS and Android (Termux)",
)

// What this edition adds on top of upstream (which ships no Android or Windows
// GUI — CLI/Termux only).
private val PORT_IMPROVEMENTS = listOf(
    "Full native Android app — upstream is CLI-only (no Android or Windows GUI)",
    "One-tap system-wide VPN via Android VpnService — no manual proxy setup",
    "Embedded hev-socks5-tunnel (tun2socks) running in-process on a native thread",
    "Live \"Your IP / Server IP\" badge with multi-provider geolocation",
    "Step-by-step connectivity self-test with crash-persistent diagnostic logs",
    "Automatic reconnect with backoff and per-scan-mode connect timeouts",
    "Protocol, scan-mode and IP-version controls in a hand-built design system",
    "Light and dark themes, in-app language switch (English + فارسی)",
    "Quick Settings tile — connect/disconnect straight from the notification shade",
    "Share the VPN over Wi\u2011Fi/hotspot — built-in HTTP + SOCKS5 proxy for laptops",
    "Determinate connect progress, cumulative data meter and a real log console",
    "Signed per-ABI release APKs published automatically from GitHub Actions",
    "Engine version shown in About, so the bundled core is always verifiable",
    "Zero Trust (WARP for organizations), split routing rules and custom in-tunnel DNS",
)

/**
 * Credits and provenance: the upstream engine (Cluvex Studio) and what this
 * Android edition adds.
 *
 * WHAT CHANGED AND WHY: the two version numbers are now the first thing on the
 * page, as tiles. They are the reason anyone opens About — a bug report without
 * them is unactionable — and they used to be rows four and five of a hairline
 * list. Only the two long feature lists stay behind a toggle; rendering twenty
 * bullet points by default buried the one line someone needed.
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier) {
    var showFeatures by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    // BuildConfig.VERSION_NAME is the honest fallback: a hardcoded release number
    // here would end up verbatim in bug reports when getPackageInfo throws
    // (disabled caller app, OEM quirks).
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: BuildConfig.VERSION_NAME
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AetherCard {
            CardHeader(
                title = stringResource(R.string.about_build_title),
                subtitle = stringResource(R.string.about_build_sub),
                icon = Icons.Rounded.Info,
                tint = accents.brand,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = stringResource(R.string.about_version, "").trim(),
                    value = versionName,
                    tint = accents.brand,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                // BuildConfig.CORE_VERSION is stamped at build time from
                // native/aether/CORE_VERSION, i.e. from whatever sync-core.sh
                // actually vendored for THIS build — verifiable, not decorative.
                StatTile(
                    label = stringResource(R.string.about_core_version, "").trim(),
                    value = BuildConfig.CORE_VERSION,
                    tint = accents.brand,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        AetherCard {
            CardHeader(
                title = stringResource(R.string.about_links_title),
                subtitle = stringResource(R.string.about_original_note),
                icon = Icons.Rounded.Link,
                tint = accents.brand,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_original_title),
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinkRow(R.drawable.ic_github, "github.com/CluvexStudio/Aether", URL_ORIGINAL_GITHUB)
            LinkRow(R.drawable.ic_telegram, "t.me/CluvexStudio", URL_ORIGINAL_TELEGRAM)

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.about_port_title),
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinkRow(R.drawable.ic_github, "github.com/QW-AI-Code", URL_PORT_GITHUB)
        }

        Spacer(Modifier.height(16.dp))

        AetherCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFeatures = !showFeatures },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (showFeatures) 180f else 0f),
                )
            }

            AnimatedVisibility(visible = showFeatures) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.about_original_title),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FeatureList(ORIGINAL_FEATURES)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.about_port_title),
                        style = AetherMetaLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FeatureList(PORT_IMPROVEMENTS)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "\u2014",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
