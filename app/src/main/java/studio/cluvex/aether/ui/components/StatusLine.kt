package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut

/**
 * The state headline.
 *
 * Left-aligned and set in the display scale on purpose. The old version was a
 * centred 19sp line that carried the single most important fact in the app
 * ("am I protected?") at the same visual weight as the helper text under it.
 * Now the state word is the loudest thing on the screen after the ring, and
 * the explanation sits under it in body copy where it belongs.
 */
@Composable
fun StatusLine(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (
                    slideInVertically(tween(AetherDur.Base, easing = AetherEaseOut)) { it / 3 } +
                        fadeIn(tween(AetherDur.Base))
                    ) togetherWith (
                    slideOutVertically(tween(AetherDur.Quick, easing = AetherEaseOut)) { -it / 3 } +
                        fadeOut(tween(AetherDur.Quick))
                    )
            },
            label = "title",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = subtitle,
            transitionSpec = { fadeIn(tween(AetherDur.Base)) togetherWith fadeOut(tween(AetherDur.Quick)) },
            label = "subtitle",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
    }
}
