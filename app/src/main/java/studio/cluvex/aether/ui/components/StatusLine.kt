package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import studio.cluvex.aether.ui.theme.AetherDur

/**
 * The one line that explains the state.
 *
 * WHAT CHANGED: this used to be StatusLine — a display-size state word plus a
 * subtitle. The word now lives inside the connect orb, at the optical centre of
 * the screen, so keeping a second copy of it 20dp underneath was pure
 * duplication: two elements, same fact, competing for the same glance.
 *
 * What is left is the part the orb cannot say: WHY, and WHAT NEXT. Centred under
 * the orb, in body copy, cross-faded so a changing explanation does not make the
 * layout jump.
 */
@Composable
fun StatusHint(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            fadeIn(tween(AetherDur.Base)) togetherWith fadeOut(tween(AetherDur.Quick))
        },
        label = "hint",
        modifier = modifier.fillMaxWidth(),
    ) { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
