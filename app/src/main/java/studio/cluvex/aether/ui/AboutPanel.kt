package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R

/** The repository this build comes from. That is the whole page. */
private const val REPO_URL = "https://github.com/MrMatin0/Aether"
private const val REPO_LABEL = "github.com/MrMatin0/Aether"

/**
 * ABOUT.
 *
 * WHAT IS NOT HERE ANY MORE: the build card with the app and core versions, the
 * upstream project and Telegram rows, the android-port credit, and the two
 * expandable feature lists. One row is left, and it is the one a reader can act
 * on - the repository, where the source, the releases and the issue tracker all
 * are.
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        LinkRow(R.drawable.ic_github, REPO_LABEL, REPO_URL)
    }
}

@Composable
private fun LinkRow(iconRes: Int, label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
            if (runCatching { uriHandler.openUri(url) }.isFailure) {
                Toast.makeText(context, R.string.ux_open_link_failed, Toast.LENGTH_SHORT).show()
            }
        }.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
