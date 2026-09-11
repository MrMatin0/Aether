package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/**
 * The diagnostics destination: a VPN status centre, not a console with a heading.
 *
 * It is an ASSEMBLY and deliberately holds no drawing of its own:
 *
 *   [TelemetryShield] overall health, integrity arc, state bloom
 *   [CheckPipeline]   the four ordered checks, with drill-down and per-node retest
 *   [CyberTerminal]   the log inspector
 *
 * The signature is unchanged (modifier, alwaysExpanded, consoleMaxHeight), so
 * HomeScreen's destination and any standalone dialog call this exactly as
 * before, and the disclosure toggle is still here for the compact embedding.
 *
 * WHAT THE PANEL ITSELF OWNS: the verdict. `failed` wins over `working` wins
 * over `passed`, in that order, and it is computed once and passed down - three
 * components deriving "is this broken" independently is how a screen ends up
 * amber in one place and rose in another during the same second.
 *
 * A single-node retest counts as busy for the whole panel: two probes against
 * the same local SOCKS5 port at once measure each other's contention rather than
 * the tunnel.
 */
@Composable
fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false,
    consoleMaxHeight: Dp = 300.dp,
) {
    val accents = LocalAetherAccents.current
    val checks by DiagnosticsLog.checks.collectAsStateWithLifecycle()
    val lines by DiagnosticsLog.lines.collectAsStateWithLifecycle()

    var expanded by rememberSaveable { mutableStateOf(alwaysExpanded) }
    var running by remember { mutableStateOf(false) }
    var retesting by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val failed = testError || checks.any { it.state == CheckState.FAIL }
    val passedCount = checks.count { it.state == CheckState.PASS }
    val allPassed = checks.isNotEmpty() && passedCount == checks.size
    val working = running || retesting != null || checks.any { it.state == CheckState.RUNNING }
    val busy = running || retesting != null

    val tone = when {
        failed -> accents.failed
        working -> accents.working
        allPassed -> accents.protected
        else -> accents.neutral
    }
    val verdict = stringResource(
        when {
            failed -> R.string.diag_problem
            working -> R.string.pill_working
            allPassed -> R.string.diag_all_ok
            else -> R.string.diag_idle
        },
    )
    val subtitle = stringResource(
        when {
            failed -> R.string.diag_hero_failed
            working -> R.string.diag_hero_working
            allPassed -> R.string.diag_hero_passed
            else -> R.string.diag_hero_idle
        },
    )
    val layer = stringResource(
        when {
            failed -> R.string.diag_layer_fault
            working -> R.string.diag_layer_probing
            allPassed -> R.string.diag_layer_verified
            else -> R.string.diag_layer_idle
        },
    )
    val endToEnd = checks.firstOrNull { it.id == Diagnostics.C_DNS }?.latencyMs
    val unmeasured = stringResource(R.string.diag_unmeasured)

    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))

        TelemetryShield(
            tone = tone,
            verdict = verdict,
            subtitle = subtitle,
            passed = passedCount,
            total = checks.size,
            active = working,
            failed = failed,
        ) {
            Hairline(alpha = 0.6f)
            StatTile(
                label = stringResource(R.string.diag_integrity),
                value = if (checks.isEmpty()) {
                    unmeasured
                } else {
                    stringResource(R.string.diag_integrity_value, passedCount, checks.size)
                },
                icon = Icons.Rounded.VerifiedUser,
                tint = tone,
                mono = false,
            )
            Hairline(alpha = 0.6f)
            StatTile(
                label = stringResource(R.string.diag_end_to_end),
                value = if (endToEnd == null) unmeasured else "$endToEnd ms",
                icon = Icons.Rounded.Speed,
                tint = tone,
            )
            Hairline(alpha = 0.6f)
            StatTile(
                label = stringResource(R.string.diag_local_proxy),
                value = TunnelConfig.SOCKS_HOST + ":" + TunnelConfig.SOCKS_PORT,
                icon = Icons.Rounded.Dns,
                tint = accents.brand,
                footnote = layer,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (!busy) {
                    running = true
                    testError = false
                    scope.launch {
                        try {
                            Diagnostics.run()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            testError = true
                        } finally {
                            running = false
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(if (busy) R.string.pill_working else R.string.diag_run))
        }

        if (!alwaysExpanded) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.diag_title))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
        }

        if (alwaysExpanded || expanded) {
            Spacer(Modifier.height(40.dp))
            ControlSection(
                title = stringResource(R.string.diag_pipeline),
                subtitle = stringResource(R.string.diag_pipeline_note),
                icon = Icons.Rounded.AccountTree,
            ) {
                if (checks.isEmpty()) {
                    Hint(stringResource(R.string.diag_idle))
                } else {
                    CheckPipeline(
                        checks = checks,
                        busy = busy,
                        onRetest = { id ->
                            if (!busy) {
                                retesting = id
                                testError = false
                                scope.launch {
                                    try {
                                        Diagnostics.runCheck(id)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        testError = true
                                    } finally {
                                        retesting = null
                                    }
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
            ControlSection(
                title = stringResource(R.string.diag_console),
                subtitle = stringResource(R.string.diag_console_note),
                icon = Icons.Rounded.Terminal,
            ) {
                CyberTerminal(
                    lines = lines,
                    maxHeight = consoleMaxHeight,
                    clearEnabled = !busy,
                    onExport = {
                        scope.launch {
                            val text = withContext(Dispatchers.Default) {
                                DiagnosticsLog.exportSanitizedText()
                            }
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, R.string.diag_exported, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClear = {
                        scope.launch { withContext(Dispatchers.IO) { DiagnosticsLog.clear() } }
                    },
                )
            }
        }
    }
}
