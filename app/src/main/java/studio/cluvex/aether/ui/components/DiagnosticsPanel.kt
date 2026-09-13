package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.DiagnosticsLog

/**
 * The diagnostics destination: the core log, and nothing else.
 *
 * WHAT THIS USED TO BE: a [TelemetryShield] hero with an integrity arc and
 * three stat tiles, a full-width self-test button, a [CheckPipeline] of four
 * ordered checks with per-node retest, and the log inspector underneath. Four
 * ways of saying "is the tunnel up" on a screen a user only opens when they
 * already know it is not, and three of them were a verdict about a measurement
 * rather than the engine's own words.
 *
 * So this is an ASSEMBLY of exactly one component now: [CyberTerminal], the log
 * inspector, which owns its filters, its search, its follow lock, its sanitised
 * export and its clear confirmation. Everything this panel still does is hand it
 * the lines and the two side effects that have to touch the clipboard and the
 * log file.
 *
 * WHAT WENT WITH THE HERO: the panel-wide verdict (failed > working > passed) and
 * the busy flag, because nothing left on screen is coloured by them - the console
 * colours by severity, per line. `alwaysExpanded` went too: it existed to open a
 * disclosure toggle that no longer has anything to hide.
 *
 * [consoleMaxHeight] is unchanged and still a CEILING rather than a height: a
 * short log renders short.
 */
@Composable
fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    consoleMaxHeight: Dp = 300.dp,
) {
    val lines by DiagnosticsLog.lines.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))

        ControlSection(
            title = stringResource(R.string.diag_console),
            subtitle = stringResource(R.string.diag_console_note),
            icon = Icons.Rounded.Terminal,
        ) {
            CyberTerminal(
                lines = lines,
                maxHeight = consoleMaxHeight,
                clearEnabled = true,
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
