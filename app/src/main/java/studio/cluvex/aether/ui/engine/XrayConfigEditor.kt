package studio.cluvex.aether.ui.engine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.XrayLink
import studio.cluvex.aether.core.XrayOutbound
import studio.cluvex.aether.data.XrayConfigStore
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.Hint
import studio.cluvex.aether.ui.components.NoticeBar
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/** How long the Xray field waits after the last change before saving it. */
private const val XRAY_SAVE_DEBOUNCE_MS = 400L

/** Tallest the Xray field grows before it scrolls inside itself. */
private val XRAY_FIELD_MAX_HEIGHT = 240.dp

/**
 * Outlives the screen the editor is on (the Chain page, or the Xray sheet on
 * the connection tab), so a paste made right before leaving it is still saved.
 * SupervisorJob: one failed write must not cancel the next one.
 */
private val XrayFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The Xray hop's one input: the user's own server config. Shown under the
 * selected Xray mode on the Chain page, and in the Xray sheet of the
 * connection tab's core selector. ONE editor for both, so the save path, the
 * validation and the wording can never drift apart.
 *
 * ### Why this is not a profile field
 *
 * A share link IS a credential (see [XrayConfigStore]). The profile is
 * exported, travels in the service Intent and sits in DataStore, so the config
 * is read from and written to its own on-device store here, off the main
 * thread, and never goes through [ProfileEdit]. The session reads the same
 * store when the Xray hop starts (studio.cluvex.aether.vpn.session.ChainStack).
 *
 * ### Saving
 *
 * Debounced: a paste is one change, typing is many. Whatever is still pending
 * when the editor leaves the screen is flushed on [XrayFlushScope], so backing
 * out right after a paste does not lose it. A write that failed is said so,
 * instead of looking saved and failing at the next connect. [onSavedChange]
 * hears what is on disk now ([XrayLink.parse] of it, null when nothing usable
 * is saved), which is what the connection tab needs to enable "via Xray".
 *
 * ### Plain text
 *
 * Not masked: a normal keyboard, the config readable on screen, several lines
 * so a JSON or subscription paste can be checked before connecting. Bounded in
 * height and scrolls inside itself. What the paste was read as is shown
 * underneath: one compact line ([XrayOutbound.summary]), or with
 * [detailedSummary] a small card with protocol, server and security. Only an
 * unparseable or unsaved config earns a NoticeBar.
 *
 * [importActions] adds Paste and Import file. Both only fill the field; the
 * usual debounced save takes it from there.
 *
 * Locked like every other field while a session is up or on its way: the
 * running Xray already read its config, so an edit could only look applied.
 */
@Composable
internal fun XrayConfigEditor(
    binaryBundled: Boolean,
    alone: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    importActions: Boolean = false,
    detailedSummary: Boolean = false,
    onSavedChange: (XrayOutbound?) -> Unit = {},
) {
    val accents = LocalAetherAccents.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val store = remember(context) { XrayConfigStore(context.applicationContext) }
    // remember, NOT rememberSaveable: the saved-state bundle is no place for a
    // credential, and the on-device store is the copy that survives anyway.
    var text by remember(store) { mutableStateOf("") }
    var loaded by remember(store) { mutableStateOf(false) }
    var saved by remember(store) { mutableStateOf("") }
    var saveFailed by remember(store) { mutableStateOf(false) }
    var importFailed by remember(store) { mutableStateOf(false) }
    val reportSaved by rememberUpdatedState(onSavedChange)

    LaunchedEffect(store) {
        val stored = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrDefault("") }
        text = stored
        saved = stored.trim()
        loaded = true
    }
    LaunchedEffect(store, text, loaded) {
        if (!loaded || text.trim() == saved) return@LaunchedEffect
        delay(XRAY_SAVE_DEBOUNCE_MS)
        val pending = text
        val ok = withContext(Dispatchers.IO) { runCatching { store.write(pending) }.getOrDefault(false) }
        if (ok) saved = pending.trim()
        saveFailed = !ok
    }
    LaunchedEffect(saved, loaded) {
        if (loaded) reportSaved(XrayLink.parse(saved))
    }
    DisposableEffect(store) {
        onDispose {
            val pending = text
            if (loaded && pending.trim() != saved) {
                XrayFlushScope.launch { runCatching { store.write(pending) } }
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { readCapped(it, XrayLink.MAX_LENGTH) }
                }.getOrNull()
            }
            importFailed = read == null
            if (read != null && loaded) text = read
        }
    }

    val parsed = remember(text) { XrayLink.parse(text) }
    val info = listOf(
        stringResource(R.string.xray_config_help),
        stringResource(if (alone) R.string.xray_alone_note else R.string.xray_chain_note),
        stringResource(R.string.xray_config_stored),
    ).joinToString("\n\n")
    Column(modifier.fillMaxWidth()) {
        if (!binaryBundled) {
            NoticeBar(
                text = stringResource(R.string.xray_missing),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
            Spacer(Modifier.height(EngineSpacing.Field))
        }

        ProfileTextField(
            value = text,
            onValueChange = { value -> if (loaded) text = value.take(XrayLink.MAX_LENGTH) },
            label = stringResource(R.string.xray_config_label),
            placeholder = stringResource(R.string.xray_config_hint),
            info = info,
            singleLine = false,
            masked = false,
            enabled = enabled && loaded,
            modifier = Modifier.heightIn(max = XRAY_FIELD_MAX_HEIGHT),
        )

        if (importActions) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionPill(
                    label = stringResource(R.string.cs_xray_paste),
                    onClick = {
                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                        if (pasted.isEmpty()) {
                            Toast.makeText(context, R.string.bridges_paste_none, Toast.LENGTH_SHORT).show()
                        } else {
                            importFailed = false
                            text = pasted.take(XrayLink.MAX_LENGTH)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ContentPaste,
                    enabled = enabled && loaded,
                )
                ActionPill(
                    label = stringResource(R.string.cs_xray_import),
                    onClick = { importer.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.FileOpen,
                    enabled = enabled && loaded,
                )
            }
            if (importFailed) {
                Hint(stringResource(R.string.cs_xray_import_failed))
            }
        }

        if (loaded && text.isNotBlank()) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            when {
                parsed == null -> NoticeBar(
                    text = stringResource(R.string.xray_config_invalid),
                    tone = accents.failed,
                    icon = Icons.Rounded.Warning,
                )
                detailedSummary -> XraySummaryCard(parsed)
                else -> Text(
                    text = stringResource(R.string.xray_config_ok, xrayReadout(parsed)),
                    style = MaterialTheme.typography.labelMedium,
                    color = accents.protected,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (saveFailed) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            NoticeBar(
                text = stringResource(R.string.xray_config_not_saved),
                tone = accents.failed,
                icon = Icons.Rounded.Warning,
            )
        }
    }
}

/**
 * What the paste was read as, in three rows: protocol, server, security (with
 * the transport). Every value is LTR-isolated: addresses, ports and the dots
 * between them must not be reordered by a Persian UI. Carries no secret, like
 * [XrayOutbound.summary] it is built from.
 */
@Composable
private fun XraySummaryCard(outbound: XrayOutbound) {
    val accents = LocalAetherAccents.current
    val none = stringResource(R.string.cs_xray_security_none)
    val security = outbound.security.trim().lowercase(Locale.ROOT)
        .let { if (it.isEmpty() || it == "none") none else it.uppercase(Locale.ROOT) }
    val transport = outbound.network.trim()
    val server = when {
        outbound.address.isEmpty() -> ""
        outbound.address.contains(':') -> "[${outbound.address}]:${outbound.port}"
        else -> "${outbound.address}:${outbound.port}"
    }
    val remark = outbound.remark.trim()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AetherRadius.Card),
        color = accents.protected.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accents.protected.copy(alpha = 0.30f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.cs_xray_summary_title),
                style = AetherMetaLabel,
                color = accents.protected,
            )
            if (remark.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    isolateLtr(remark),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            XraySummaryRow(
                stringResource(R.string.cs_xray_protocol),
                outbound.summary.substringBefore(" \u00B7 "),
            )
            if (server.isNotEmpty()) {
                XraySummaryRow(stringResource(R.string.cs_xray_server), server)
            }
            XraySummaryRow(
                stringResource(R.string.cs_xray_security),
                if (transport.isEmpty()) security else "$security \u00B7 $transport",
            )
        }
    }
}

@Composable
private fun XraySummaryRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(88.dp),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            isolateLtr(value),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** U+2066 LEFT-TO-RIGHT ISOLATE ... U+2069: keeps a technical value in reading order. */
private fun isolateLtr(text: String): String = "\u2066$text\u2069"

/**
 * What the paste was read as: its own name (when it has one) and the
 * secret-free summary, isolated LTR so a Persian UI does not reorder the
 * address and the dots.
 */
private fun xrayReadout(outbound: XrayOutbound): String {
    val remark = outbound.remark.trim()
    val text = if (remark.isEmpty() || remark == outbound.summary) {
        outbound.summary
    } else {
        "$remark \u00B7 ${outbound.summary}"
    }
    return isolateLtr(text)
}

/**
 * Reads an imported file as text, giving up (null) as soon as it is longer
 * than [max] characters: a config is never that long, and a wrong pick in the
 * file chooser (a video, an APK) must not be loaded into memory whole.
 */
private fun readCapped(stream: InputStream, max: Int): String? {
    val reader = stream.bufferedReader()
    val out = StringBuilder()
    val buffer = CharArray(4096)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        out.append(buffer, 0, count)
        if (out.length > max) return null
    }
    return out.toString()
}
