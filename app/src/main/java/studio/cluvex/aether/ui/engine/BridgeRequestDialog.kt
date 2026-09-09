package studio.cluvex.aether.ui.engine

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.LtrOutlinedTextField

/**
 * The captcha step of a personal bridge request.
 *
 * ### Why this dialog does not insist on the captcha
 *
 * The captcha is a formality now, and pretending otherwise would cost users
 * bridges. When the Tor Project retired BridgeDB in October 2024, moat's
 * `/fetch` started returning a STATIC image and `/check` stopped verifying the
 * answer at all (see the tor-relays announcement, and
 * tpo/applications/tor-browser#42086: removing the request is a to-do on their
 * side). The endpoints still exist and still hand out bridges, so this app still
 * walks the documented flow - but it must not turn an unreadable image into a
 * dead end on a device where the JPEG did not decode, or on a network that
 * truncated it.
 *
 * So: the image is shown when it can be, the field is optional, and the button
 * is never disabled. If moat ever starts checking again, a wrong answer comes
 * back as a 419 and the page says exactly that.
 */
@Composable
internal fun BridgeRequestDialog(
    challenge: BridgeChallenge,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var solution by rememberSaveable(challenge.token) { mutableStateOf("") }

    // Decoded once per challenge. A base64 blob from a censored network is
    // exactly the kind of input that arrives half-transferred, so every step of
    // this is allowed to fail without taking the dialog with it.
    val image = remember(challenge.imageBase64) {
        runCatching {
            // Some deployments answer with a data URL rather than bare base64.
            // Base64 itself contains no comma, so this is safe either way.
            val payload = challenge.imageBase64.substringAfterLast(',').trim()
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bridges_captcha_title)) },
        text = {
            Column {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.bridges_captcha_image),
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.bridges_captcha_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.bridges_captcha_no_image),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                LtrOutlinedTextField(
                    value = solution,
                    onValueChange = { solution = it },
                    inputTransform = { it.take(32) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bridges_captcha_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.bridges_captcha_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(solution.trim()) }) {
                Text(stringResource(R.string.bridges_captcha_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
