package studio.cluvex.aether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CheckFact
import studio.cluvex.aether.core.CheckState
import studio.cluvex.aether.core.ComponentCheck
import studio.cluvex.aether.ui.theme.AetherConsoleLine
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseInOut
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * The self-test as a connected pipeline instead of four unrelated rows.
 *
 * WHY A PIPELINE AND NOT A LIST: the four checks are ORDERED and each one is
 * only meaningful given the ones before it. "tcp_via_proxy FAIL" on its own is
 * ambiguous; "port PASS -> handshake PASS -> tcp FAIL" is a diagnosis. A list
 * renders those as peers and throws the sequence away, which is exactly the
 * information a reader came here for.
 *
 * THREE THINGS EACH NODE ADDS over the old row:
 *
 *  1. A LATENCY BADGE. "Failed" and "failed after the full 4 s timeout" are
 *     different faults - the first is a refusal, the second is a black hole -
 *     and the numbers were only ever visible by reading the console.
 *  2. A DRILL-DOWN. Which endpoint, which target, where DNS was resolved, which
 *     exit IP answered. All of it was already measured and none of it was shown.
 *  3. A PER-NODE RE-TEST. Once a reader knows which node is broken, re-running
 *     the whole pipeline to retest it costs up to 90 s of cold-start grace for
 *     information they already have.
 *
 * The RUNNING pulse is the only animation, it exists only while a probe is
 * actually in flight, and it is dropped entirely on a reduced-motion device.
 */
@Composable
internal fun CheckPipeline(
    checks: List<ComponentCheck>,
    busy: Boolean,
    onRetest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        checks.forEachIndexed { index, check ->
            if (index > 0) Connector(reached = check.state != CheckState.PENDING)
            PipelineNode(
                index = index + 1,
                check = check,
                busy = busy,
                open = openId == check.id,
                onToggle = { openId = if (openId == check.id) null else check.id },
                onRetest = { onRetest(check.id) },
            )
        }
    }
}

/**
 * The rail between two nodes. Direction-aware by construction: it is laid out
 * in a Row, so the gutter lands on the correct side in Persian without a single
 * mirrored dimension.
 */
@Composable
private fun Connector(reached: Boolean) {
    val accents = LocalAetherAccents.current
    val color = if (reached) accents.brand.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(19.dp))
        Box(Modifier.width(2.dp).height(22.dp).background(color))
    }
}

@Composable
private fun PipelineNode(
    index: Int,
    check: ComponentCheck,
    busy: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onRetest: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val tone = when (check.state) {
        CheckState.PASS -> accents.protected
        CheckState.FAIL -> accents.failed
        CheckState.RUNNING -> accents.working
        CheckState.PENDING -> accents.neutral
    }
    val running = check.state == CheckState.RUNNING
    val motion = running && !LocalReducedMotion.current
    val pulse = if (motion) {
        val transition = rememberInfiniteTransition(label = "node-pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(AetherDur.Loop, easing = AetherEaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "node-alpha",
        ).value
    } else {
        1f
    }
    val expandLabel = stringResource(if (open) R.string.ux_collapsed else R.string.ux_expanded)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onToggle)
            .padding(vertical = 12.dp, horizontal = 4.dp)
            .animateContentSize(tween(aetherDuration(AetherDur.Quick), easing = AetherEaseOut)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NodeMarker(index, check.state, tone, pulse)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    check.label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = TextDirection.ContentOrLtr,
                    ),
                )
                if (check.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        check.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDirection = TextDirection.ContentOrLtr,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        when (check.state) {
                            CheckState.PASS -> R.string.phase_ready
                            CheckState.FAIL -> R.string.pill_failed
                            CheckState.RUNNING -> R.string.pill_working
                            CheckState.PENDING -> R.string.phase_verify
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (check.latencyMs != null) {
                LatencyBadge(check.latencyMs, tone)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 52.dp, top = 12.dp, end = 4.dp)) {
                if (check.facts.isEmpty()) {
                    Hint(stringResource(R.string.diag_no_facts))
                } else {
                    check.facts.forEach { FactRow(it) }
                }
                Spacer(Modifier.height(12.dp))
                ActionPill(
                    label = stringResource(R.string.diag_retest_one),
                    onClick = onRetest,
                    icon = Icons.Rounded.Refresh,
                    enabled = !busy,
                    tint = accents.brand,
                )
            }
        }
    }
}

/** The node itself: a ring that breathes while probing, a verdict when done. */
@Composable
private fun NodeMarker(index: Int, state: CheckState, tone: Color, pulse: Float) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(tone.copy(alpha = 0.16f * pulse), radius)
            drawCircle(
                color = tone,
                radius = radius - 1.5.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
        }
        when (state) {
            CheckState.PASS -> Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tone)
            CheckState.FAIL -> Icon(Icons.Rounded.Close, null, Modifier.size(20.dp), tone)
            CheckState.RUNNING -> Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp), tone)
            CheckState.PENDING -> Text(
                index.toString().padStart(2, '0'),
                style = AetherMetaLabel.copy(textDirection = TextDirection.Ltr),
                color = tone,
            )
        }
    }
}

@Composable
private fun LatencyBadge(latencyMs: Long, tone: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = lerp(MaterialTheme.colorScheme.surface, tone, 0.12f),
    ) {
        Text(
            "$latencyMs ms",
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = AetherMetaLabel.copy(textDirection = TextDirection.Ltr),
            color = tone,
            maxLines = 1,
        )
    }
}

/**
 * One measured value. The label is prose and follows the locale; the value is a
 * technical literal and is pinned LTR, because an IP with a port reversed by
 * bidi reordering is not a value anybody can paste into a bug report.
 */
@Composable
private fun FactRow(fact: CheckFact) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            fact.label,
            Modifier.weight(0.42f),
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            fact.value,
            Modifier.weight(0.58f),
            style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
        )
    }
}
