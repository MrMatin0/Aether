package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.ChalkFaint

/**
 * The connect pipeline.
 *
 * ROOT CAUSE THIS FIXES (usability, not a crash): a Thorough or Ironclad scan
 * can legitimately run for several minutes before the engine even opens the
 * local proxy. The old screen showed one word — "Connecting…" — for that whole
 * time, which is indistinguishable from a frozen app, so people force-quit
 * mid-scan and concluded the tunnel "never works".
 *
 * Now the four real stages the controller already goes through are visible:
 * engine launch, tunnel up, end-to-end verification, ready. Completed stages
 * hold the accent, the current stage carries a travelling highlight, and
 * pending stages stay as hairlines.
 *
 * The highlight is an [Animatable] read inside a draw lambda (draw-only
 * invalidation) and it is stopped whenever [active] is false.
 */
@Composable
fun PhasePipeline(
    step: Int,
    labels: List<String>,
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
        } else {
            pulse.snapTo(0f)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val done = index < step
            val current = index == step
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                done -> accent.copy(alpha = 0.8f)
                                current -> accent.copy(alpha = 0.22f)
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                        )
                        .then(
                            if (current && active) {
                                Modifier.drawBehind {
                                    val progress = pulse.value
                                    val band = size.width * 0.5f
                                    val startX = -band + (size.width + band) * progress
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                accent,
                                                Color.Transparent,
                                            ),
                                            startX = startX,
                                            endX = startX + band,
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    style = AetherMetaLabel,
                    color = if (done || current) accent else ChalkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
