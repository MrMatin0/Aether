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
import studio.cluvex.aether.ui.theme.LocalReducedMotion

/** Full error and status text remains readable at every font scale. */
@Composable
fun StatusHint(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Center) {
    if (LocalReducedMotion.current) {
        HintText(text, modifier, align)
    } else {
        AnimatedContent(targetState = text,
            transitionSpec = { fadeIn(tween(AetherDur.Base)) togetherWith fadeOut(tween(AetherDur.Quick)) },
            label = "hint", modifier = modifier.fillMaxWidth()) { value -> HintText(value, Modifier, align) }
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier, align: TextAlign) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align, modifier = modifier.fillMaxWidth())
}
