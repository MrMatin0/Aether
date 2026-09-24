package studio.cluvex.aether.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.log.LogSanitizer

/**
 * The diagnostics destination: the core log, full height.
 *
 * This is an assembly of [CyberTerminal] plus the side effects that have to
 * touch the clipboard, the share sheet and the log file.
 *
 * [consoleMaxHeight] null (the default) means FILL: the caller must give this
 * panel a bounded height, as the diagnostics tab does. A caller that still
 * lives inside a scrolling column passes a height instead.
 *
 * [focusErrors] is a one-shot request from "See the error" on the connection
 * tab; [onFocusConsumed] clears it once the console has applied it.
 */
@Composable
fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    consoleMaxHeight: Dp? = null,
    focusErrors: Boolean = false,
    onFocusConsumed: () -> Unit = {},
) {
    val lines by DiagnosticsLog.lines.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.diag_share_title)

    Column(if (consoleMaxHeight == null) modifier.fillMaxSize() else modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.diag_console_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        CyberTerminal(
            lines = lines,
            modifier = if (consoleMaxHeight == null) Modifier.weight(1f) else Modifier.height(consoleMaxHeight),
            onCopy = { maskIps ->
                scope.launch {
                    val text = withContext(Dispatchers.Default) { DiagnosticsLog.exportSanitizedText(maskIps) }
                    copySensitive(context, text, R.string.diag_exported)
                }
            },
            onShare = { maskIps ->
                scope.launch {
                    val text = withContext(Dispatchers.IO) { buildShareDocument(maskIps) }
                    shareText(context, text, shareTitle)
                }
            },
            onCopyLine = { line ->
                copySensitive(context, LogSanitizer.sanitize(line.format()), R.string.diag_line_copied)
            },
            onClear = {
                scope.launch { withContext(Dispatchers.IO) { DiagnosticsLog.clear() } }
            },
            focusErrors = focusErrors,
            onFocusConsumed = onFocusConsumed,
        )
    }
}

/**
 * A share goes through an Intent extra, and an Intent is a Binder transaction
 * with a ~1 MB ceiling shared by everything in flight. UTF-16 in a Parcel is
 * two bytes a char, so this stays well clear of it. The TAIL is kept.
 */
private const val MAX_SHARE_CHARS = 200_000

private const val SHARE_SUBJECT = "Aether+ diagnostics log"

/** Header a bug report always needs and nobody remembers to type. */
private fun buildShareDocument(maskIps: Boolean): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
    val header = buildString {
        appendLine(SHARE_SUBJECT)
        appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}) · engine ${BuildConfig.CORE_VERSION}")
        appendLine("android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("exported $stamp")
        appendLine(
            if (maskIps) {
                "sanitized: credentials removed, public IPv4 masked"
            } else {
                "sanitized: credentials removed"
            },
        )
        appendLine()
    }
    val body = DiagnosticsLog.exportForShare(maskIps)
    val budget = (MAX_SHARE_CHARS - header.length).coerceAtLeast(0)
    val clipped = if (body.length > budget) {
        "[… ${body.length - budget} earlier characters omitted …]" + System.lineSeparator() + body.takeLast(budget)
    } else {
        body
    }
    return header + clipped
}

/**
 * Plain text through the system share sheet. Deliberately NOT a file: that
 * would need a FileProvider, and this app exports no content provider at all
 * (see the manifest).
 */
private fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, SHARE_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/**
 * Copies [text] flagged as SENSITIVE, so Android 13+ does not show it in the
 * clipboard preview or offer it to keyboards' clipboard history as plain
 * content. Android 13+ also confirms a copy on its own, so the toast is only
 * shown below that, where it is the only feedback.
 */
private fun copySensitive(context: Context, text: String, toast: Int) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(SHARE_SUBJECT, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    runCatching { manager.setPrimaryClip(clip) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

/** ClipDescription.EXTRA_IS_SENSITIVE, spelled out so minSdk 26 compiles cleanly. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
