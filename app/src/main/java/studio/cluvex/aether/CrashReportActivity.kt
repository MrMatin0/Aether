package studio.cluvex.aether

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.theme.AetherConsoleLine
import studio.cluvex.aether.ui.theme.AetherTheme
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import java.io.File

/**
 * Shows the fatal JVM exception saved by [AetherApp]'s crash handler on the
 * previous run. Kept as a separate Activity (launched on the next cold start) so
 * the report is reachable even when the crash broke the home UI's state.
 *
 * WHAT CHANGED: it was raw Material components — a headline, a translucent
 * surfaceVariant box and two default buttons — on an Aether background, which
 * made the app look like it had fallen back to a system error screen at exactly
 * the moment the user most needs to trust it. It now uses the same card, the same
 * console typography and the same action pills as the rest of the app, and the
 * stack trace is pinned LTR because a Java stack trace is not prose.
 */
class CrashReportActivity : ComponentActivity() {

    // Same layering as every other entry point: the crash screen must speak the
    // language the user picked in-app, not the system locale.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashFile = File(filesDir, AetherApp.CRASH_FILE)
        val details = runCatching { crashFile.readText() }.getOrDefault("")

        setContent {
            AetherTheme {
                val accents = LocalAetherAccents.current
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(20.dp),
                    ) {
                        AetherCard(tone = accents.failed) {
                            CardHeader(
                                title = stringResource(R.string.crash_title),
                                subtitle = stringResource(R.string.crash_subtitle),
                                icon = Icons.Rounded.Warning,
                                tint = accents.failed,
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        AetherCard(
                            modifier = Modifier.weight(1f),
                            padding = 14.dp,
                        ) {
                            Text(
                                text = details.ifBlank { "\u2014" },
                                style = AetherConsoleLine.copy(
                                    textDirection = TextDirection.Ltr,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        val clipboard = LocalClipboardManager.current
                        var copied by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionPill(
                                label = stringResource(
                                    if (copied) R.string.crash_copied else R.string.crash_copy,
                                ),
                                icon = if (copied) {
                                    Icons.Rounded.Check
                                } else {
                                    Icons.Rounded.ContentCopy
                                },
                                onClick = {
                                    clipboard.setText(AnnotatedString(details))
                                    copied = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                            ActionPill(
                                label = stringResource(R.string.crash_dismiss),
                                icon = Icons.Rounded.Close,
                                filled = true,
                                onClick = {
                                    runCatching { crashFile.delete() }
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
