package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.SstpConfig
import studio.cluvex.aether.model.SstpSource

/**
 * Settings panel for SSTP configuration. Shows when the user has an SSTP
 * server selected (either from VPNGate or manually configured).
 *
 * Layout:
 *  - Server hostname field (or VPNGate server display)
 *  - Port (usually 443)
 *  - Username / password (defaults to vpn/vpn for VPNGate)
 *  - TLS certificate verification toggle
 *  - Custom SNI field
 *  - PPP MRU/MTU fields
 *  - "Browse VPNGate" button
 */
@Composable
fun SstpSettingsPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    onBrowseVpnGate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sstpConfig = profile.sstpConfig

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Text(
            "SSTP Configuration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Configure SSTP (Secure Socket Tunneling Protocol) connection. " +
                "VPNGate servers use vpn/vpn credentials by default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // Browse VPNGate button
        OutlinedButton(
            onClick = onBrowseVpnGate,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Public, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Browse VPNGate Servers")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Current server info (if from VPNGate)
        if (sstpConfig.source == SstpSource.VPNGATE && sstpConfig.hostname.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.VpnKey, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "VPNGate Server",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            sstpConfig.hostname,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Server hostname
        OutlinedTextField(
            value = sstpConfig.hostname,
            onValueChange = {
                onProfileChange(profile.copy(
                    sstpConfig = sstpConfig.copy(hostname = it, source = SstpSource.MANUAL)
                ))
            },
            label = { Text("Server Hostname") },
            placeholder = { Text("vpn123.opengw.net") },
            leadingIcon = { Icon(Icons.Rounded.Dns, null) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Port
        OutlinedTextField(
            value = if (sstpConfig.port == 443) "" else sstpConfig.port.toString(),
            onValueChange = { text ->
                val port = text.toIntOrNull()?.coerceIn(1, 65535) ?: 443
                onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(port = port)))
            },
            label = { Text("Port") },
            placeholder = { Text("443") },
            leadingIcon = { Icon(Icons.Rounded.Numbers, null) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Username
        OutlinedTextField(
            value = sstpConfig.username,
            onValueChange = {
                onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(username = it)))
            },
            label = { Text("Username") },
            placeholder = { Text("vpn") },
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Password
        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = sstpConfig.password,
            onValueChange = {
                onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(password = it)))
            },
            label = { Text("Password") },
            placeholder = { Text("vpn") },
            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        "Toggle visibility",
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // TLS verification toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Verify TLS Certificate",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Disable for VPNGate servers with self-signed certs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sstpConfig.verifyCert,
                onCheckedChange = {
                    onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(verifyCert = it)))
                },
                enabled = enabled,
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Advanced section
        Text(
            "Advanced",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Custom SNI
        OutlinedTextField(
            value = sstpConfig.customSni,
            onValueChange = {
                onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(customSni = it)))
            },
            label = { Text("Custom SNI (optional)") },
            placeholder = { Text("Leave blank to use hostname") },
            leadingIcon = { Icon(Icons.Rounded.Badge, null) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // PPP MRU/MTU row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = if (sstpConfig.mru == 0) "" else sstpConfig.mru.toString(),
                onValueChange = { text ->
                    val mru = text.toIntOrNull()?.coerceIn(576, 4096) ?: 0
                    onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(mru = mru)))
                },
                label = { Text("MRU") },
                placeholder = { Text("1500") },
                singleLine = true,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = if (sstpConfig.mtu == 0) "" else sstpConfig.mtu.toString(),
                onValueChange = { text ->
                    val mtu = text.toIntOrNull()?.coerceIn(576, 4096) ?: 0
                    onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(mtu = mtu)))
                },
                label = { Text("MTU") },
                placeholder = { Text("1500") },
                singleLine = true,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // Compression toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "PPP Compression",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "CCP compression (rarely useful over TLS)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sstpConfig.compression,
                onCheckedChange = {
                    onProfileChange(profile.copy(sstpConfig = sstpConfig.copy(compression = it)))
                },
                enabled = enabled,
            )
        }
    }
}
