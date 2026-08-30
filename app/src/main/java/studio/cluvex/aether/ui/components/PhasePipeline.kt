package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherMetaLabel

/**
 * The connect pipeline.
 *
 * ROOT CAUSE THIS ADDRESSES (usability, not a crash): a Thorough or Ironclad
 * scan can legitimately run for several minutes before the engine even opens the
 * local proxy. A screen that says "Connecting…" for that whole time is
 * indistinguishable from a frozen app, so people force-quit mid-scan and
 * conclude the tunnel "never works".
 *
 * WHAT CHANGED IN THIS PASS: it used to be four anonymous 3dp bars. A bar can
 * say "this part is done" but it cannot be referred to — and the single most
 * useful sentence in a bug report about this app is "it stops at step 3". So the
 * stages are now numbered nodes on a rail: completed nodes carry a check,
 * the current node is a ring with a breathing centre, pending nodes are hollow.
 *
 * The pulse is an [Animatable] read inside a draw lambda (draw-only
 * invalidation) and is stopped whenever [active] is false.
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

    val rail = MaterialTheme.colorScheme.outlineVariant
    val pending = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val done = index < step
            val current = index == step
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rail stubs. Invisible at the ends so the row does not look
                    // like it continues off the screen.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(
                                if (index == 0) {
                                    Color.Transparent
                                } else if (done || current) {
                                    accent.copy(alpha = 0.75f)
                                } else {
                                    rail
                                },
                            ),
                    )
                    PhaseNode(
                        index = index,
                        done = done,
                        current = current,
                        active = active,
                        accent = accent,
                        pending = pending,
                        rail = rail,
                        pulse = pulse,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(
                                if (index == labels.lastIndex) {
                                    Color.Transparent
                                } else if (done) {
                                    accent.copy(alpha = 0.75f)
                                } else {
                                    rail
                                },
                            ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = label,
                    style = AetherMetaLabel,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (done || current) accent else pending,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PhaseNode(
    index: Int,
    done: Boolean,
    current: Boolean,
    active: Boolean,
    accent: Color,
    pending: Color,
    rail: Color,
    pulse: Animatable<Float, *>,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp),
    ) {
        when {
            done -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = contentColorForTone(accent),
                    modifier = Modifier.size(13.dp),
                )
            }
            current -> {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        color = accent,
                        radius = r - 1.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    // Breathing centre: the node is where the work is, so the
                    // motion belongs here and nowhere else on the rail.
                    val t = pulse.value
                    val breathe = if (active) 0.34f + 0.22f * kotlin.math.sin(t * 6.2832f) else 0.34f
                    drawCircle(color = accent, radius = r * breathe)
                }
            }
            else -> {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCircle(
                        color = rail,
                        radius = size.minDimension / 2f - 1.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = pending,
                )
            }
        }
    }
}
