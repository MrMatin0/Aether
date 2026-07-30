package studio.cluvex.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R

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

// What this edition adds on top of upstream (which ships no Android or
// Windows GUI — CLI/Termux only).
private val PORT_IMPROVEMENTS = listOf(
    "Full native Android app — upstream is CLI-only (no Android or Windows GUI)",
    "One-tap system-wide VPN via Android VpnService — no manual proxy setup",
    "Embedded hev-socks5-tunnel (tun2socks) running in-process on a native thread",
    "Live \"Your IP / Server IP\" badge with multi-provider geolocation",
    "Step-by-step connectivity self-test with crash-persistent diagnostic logs",
    "Automatic reconnect with backoff and per-scan-mode connect timeouts",
    "Protocol, scan-mode and IP-version controls in a Material 3 UI (English + فارسی)",
    "Quick Settings tile — connect/disconnect straight from the notification shade",
    "Share the VPN over Wi‑Fi/hotspot — built-in HTTP + SOCKS5 proxy for laptops & other phones",
    "Advanced settings reachable right from the home screen",
    "Signed per-ABI release APKs published automatically from GitHub Actions",
    "Engine version shown in About, so the bundled core is always verifiable",
    "Zero Trust (WARP for organizations), split routing rules and custom in-tunnel DNS",
)

/**
 * Collapsible "About" card: credits the upstream Aether project (Cluvex
 * Studio) with its GitHub + Telegram links and feature set, then lists what
 * this Android edition (QW-AI-Code) adds on top.
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "aboutArrow")
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.1.0"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Engine (core) version — same idea as the Windows edition's
                    // About page, which shows app version AND core version so a
                    // user can verify the bundled engine is current.
                    // BuildConfig.CORE_VERSION is stamped at build time from
                    // native/aether/CORE_VERSION, i.e. from whatever
                    // scripts/sync-core.sh actually vendored for THIS build.
                    Text(
                        text = stringResource(R.string.about_core_version, BuildConfig.CORE_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))

                    // ---- Original project (Cluvex Studio) ----
                    SectionHeader(
                        title = stringResource(R.string.about_original_title),
                        note = stringResource(R.string.about_original_note),
                    )
                    LinkRow(R.drawable.ic_github, "github.com/CluvexStudio/Aether", URL_ORIGINAL_GITHUB)
                    LinkRow(R.drawable.ic_telegram, "t.me/CluvexStudio", URL_ORIGINAL_TELEGRAM)
                    Spacer(Modifier.height(6.dp))
                    FeatureList(ORIGINAL_FEATURES)

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(14.dp))

                    // ---- This Android edition (QW-AI-Code) ----
                    SectionHeader(
                        title = stringResource(R.string.about_port_title),
                        note = stringResource(R.string.about_port_note),
                    )
                    LinkRow(R.drawable.ic_github, "github.com/QW-AI-Code", URL_PORT_GITHUB)
                    Spacer(Modifier.height(6.dp))
                    FeatureList(PORT_IMPROVEMENTS)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, note: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
