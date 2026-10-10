package studio.cluvex.aether.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.SstpConfig
import studio.cluvex.aether.model.SstpSource
import studio.cluvex.aether.ui.components.ControlSection
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.IconBadge
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.components.SectionTitle
import studio.cluvex.aether.ui.components.SwitchRow
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherMono
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.aetherDuration

/** Letters, digits, dots, dashes and underscores, or a bracketed / bare IPv6 literal. */
private val SSTP_HOST_PATTERN = Regex("^[A-Za-z0-9._-]+$|^\\[?[0-9A-Fa-f:]+]?$")

/**
 * SSTP settings, in the app's editorial layout:
 *
 *  - a relay hero card: the selected VPN Gate relay, or a way to browse them
 *  - Server: hostname + port side by side (pasting `host:port` fills both)
 *  - Sign-in: username, password with reveal, a one-tap VPN Gate default
 *  - Security: certificate verification, with a warning while it is off
 *  - Advanced (collapsed): custom SNI, MRU, MTU
 *
 * Stateless: the caller owns [config] and persists every [onConfigChange].
 */
@Composable
fun SstpSettingsPanel(
    config: SstpConfig,
    onConfigChange: (SstpConfig) -> Unit,
    enabled: Boolean,
    onBrowseVpnGate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable {
        mutableStateOf(config.customSni.isNotBlank() || config.mru != 0 || config.mtu != 0)
    }
    val hostError = config.hostname.isNotEmpty() && !SSTP_HOST_PATTERN.matches(config.hostname)

    ControlSection(
        title = stringResource(R.string.sstp_title),
        subtitle = stringResource(R.string.sstp_subtitle),
        icon = Icons.Rounded.VpnLock,
        modifier = modifier,
    ) {
        SstpRelayHero(config = config, enabled = enabled, onBrowse = onBrowseVpnGate)

        // ------------------------------------------------------------ server --
        SectionTitle(stringResource(R.string.sstp_section_server), Modifier.padding(top = 32.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LtrOutlinedTextField(
                value = config.hostname,
                onValueChange = { raw ->
                    val (host, port) = splitSstpHostPort(raw)
                    val sameHost = host.equals(config.hostname, ignoreCase = true)
                    onConfigChange(
                        config.copy(
                            hostname = host,
                            port = port ?: config.port,
                            source = if (sameHost) config.source else SstpSource.MANUAL,
                            countryCode = if (sameHost) config.countryCode else "",
                            countryName = if (sameHost) config.countryName else "",
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text(stringResource(R.string.sstp_hostname)) },
                placeholder = { Text("public-vpn-123.opengw.net", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = if (hostError) {
                    { Text(stringResource(R.string.sstp_hostname_error)) }
                } else {
                    null
                },
                keyboardType = KeyboardType.Uri,
                isError = hostError,
                leadingIcon = { Icon(Icons.Rounded.Dns, null) },
            )
            LtrOutlinedTextField(
                value = config.port.toString(),
                onValueChange = { text ->
                    // Only valid ports are committed; anything else reverts on blur.
                    val port = text.toIntOrNull()
                    if (port != null && port in 1..65535) onConfigChange(config.copy(port = port))
                },
                modifier = Modifier.width(112.dp),
                enabled = enabled,
                label = { Text(stringResource(R.string.sstp_port)) },
                keyboardType = KeyboardType.Number,
                inputTransform = { it.filter(Char::isDigit).take(5) },
            )
        }

        // ----------------------------------------------------------- sign-in --
        SectionTitle(stringResource(R.string.sstp_section_auth), Modifier.padding(top = 24.dp))
        LtrOutlinedTextField(
            value = config.username,
            onValueChange = { onConfigChange(config.copy(username = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.sstp_username)) },
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
        )
        Spacer(Modifier.height(12.dp))
        LtrOutlinedTextField(
            value = config.password,
            onValueChange = { onConfigChange(config.copy(password = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.sstp_password)) },
            keyboardType = KeyboardType.Password,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Rounded.VpnKey, null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        stringResource(if (passwordVisible) R.string.sstp_hide_password else R.string.sstp_show_password),
                    )
                }
            },
        )
        val gateCredentials = config.username == SstpConfig.VPNGATE_USERNAME &&
            config.password == SstpConfig.VPNGATE_PASSWORD
        AnimatedVisibility(visible = !gateCredentials) {
            AssistChip(
                onClick = {
                    onConfigChange(
                        config.copy(username = SstpConfig.VPNGATE_USERNAME, password = SstpConfig.VPNGATE_PASSWORD),
                    )
                },
                label = { Text(stringResource(R.string.sstp_use_vpngate_defaults)) },
                leadingIcon = { Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp)) },
                enabled = enabled,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Hint(stringResource(R.string.sstp_auth_note))

        // ---------------------------------------------------------- security --
        SectionTitle(stringResource(R.string.sstp_section_security), Modifier.padding(top = 24.dp))
        SwitchRow(
            title = stringResource(R.string.sstp_verify_cert),
            description = stringResource(R.string.sstp_verify_cert_desc),
            checked = config.verifyCert,
            enabled = enabled,
            onChange = { onConfigChange(config.copy(verifyCert = it)) },
        )
        AnimatedVisibility(visible = !config.verifyCert) {
            NoticeBar(
                text = stringResource(R.string.sstp_verify_off_warning),
                tone = accents.working,
                icon = Icons.Rounded.Warning,
            )
        }

        // ---------------------------------------------------------- advanced --
        Spacer(Modifier.height(24.dp))
        Hairline()
        SstpAdvancedHeader(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen })
        AnimatedVisibility(
            visible = advancedOpen,
            enter = expandVertically(tween(aetherDuration(AetherDur.Base), easing = AetherEaseOut)) +
                fadeIn(tween(aetherDuration(AetherDur.Quick))),
            exit = shrinkVertically(tween(aetherDuration(AetherDur.Quick))) +
                fadeOut(tween(aetherDuration(AetherDur.Quick))),
        ) {
            Column(Modifier.fillMaxWidth()) {
                LtrOutlinedTextField(
                    value = config.customSni,
                    onValueChange = { onConfigChange(config.copy(customSni = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.sstp_sni)) },
                    placeholder = { Text(stringResource(R.string.sstp_sni_hint)) },
                    keyboardType = KeyboardType.Uri,
                    leadingIcon = { Icon(Icons.Rounded.Badge, null) },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SstpMtuField(
                        label = R.string.sstp_mru,
                        value = config.mru,
                        enabled = enabled,
                        onValue = { onConfigChange(config.copy(mru = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    SstpMtuField(
                        label = R.string.sstp_mtu,
                        value = config.mtu,
                        enabled = enabled,
                        onValue = { onConfigChange(config.copy(mtu = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint(
                    stringResource(
                        R.string.sstp_mtu_hint,
                        SstpConfig.MIN_MTU,
                        SstpConfig.MAX_MTU,
                        SstpConfig.DEFAULT_MTU,
                    ),
                )
            }
        }
    }
}

/** The top card: the chosen VPN Gate relay, or the way to go and pick one. */
@Composable
private fun SstpRelayHero(config: SstpConfig, enabled: Boolean, onBrowse: () -> Unit) {
    val accents = LocalAetherAccents.current
    val locale = LocalConfiguration.current.locales[0]
    val fromGate = config.source == SstpSource.VPNGATE && config.hostname.isNotBlank()
    Surface(
        onClick = onBrowse,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = accents.card,
        border = BorderStroke(1.dp, if (fromGate) accents.brand else accents.cardBorder),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(accents.brandWash, Color.Transparent)))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fromGate) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(CountryFlags.flag(config.countryCode), fontSize = 26.sp)
                }
            } else {
                IconBadge(Icons.Rounded.TravelExplore, accents.brand, size = 48.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (fromGate) {
                    Text(stringResource(R.string.sstp_current_relay), style = AetherMetaLabel, color = accents.brand)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        CountryFlags.displayName(config.countryCode, locale, config.countryName.ifBlank { config.hostname }),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${config.hostname}:${config.port}",
                        style = AetherMetaLabel.copy(fontFamily = AetherMono, textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(stringResource(R.string.sstp_browse), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sstp_browse_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (fromGate) {
                Text(stringResource(R.string.sstp_change), style = MaterialTheme.typography.labelLarge, color = accents.brand)
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SstpAdvancedHeader(open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(aetherDuration(AetherDur.Quick), easing = AetherEaseOut),
        label = "advancedChevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sstp_section_advanced), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.sstp_advanced_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ExpandMore,
            null,
            Modifier.rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * MRU / MTU input. The old field coerced every keystroke into 576..4096, so
 * typing "1" on the way to "1400" instantly became "576". Now only complete,
 * in-range values are committed, blank means default, and anything else
 * reverts when the field loses focus.
 */
@Composable
private fun SstpMtuField(
    @StringRes label: Int,
    value: Int,
    enabled: Boolean,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LtrOutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { text ->
            val parsed = text.toIntOrNull()
            if (text.isEmpty()) {
                onValue(0)
            } else if (parsed != null && parsed in SstpConfig.MIN_MTU..SstpConfig.MAX_MTU) {
                onValue(parsed)
            }
        },
        modifier = modifier,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        placeholder = { Text(SstpConfig.DEFAULT_MTU.toString()) },
        keyboardType = KeyboardType.Number,
        inputTransform = { it.filter(Char::isDigit).take(4) },
    )
}

/**
 * Accepts what people actually paste: `host`, `host:port`, `sstp://host:port/`
 * or `https://host/`. Returns the bare host and the port when one was given.
 */
internal fun splitSstpHostPort(raw: String): Pair<String, Int?> {
    var text = raw.trim()
    text = text.substringAfter("://", text)
    text = text.substringBefore('/')
    val colon = text.lastIndexOf(':')
    if (colon > 0 && text.indexOf(':') == colon) {
        val port = text.substring(colon + 1).toIntOrNull()
        if (port != null && port in 1..65535) return text.substring(0, colon) to port
    }
    return text to null
}
