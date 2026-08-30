package studio.cluvex.aether.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents

private const val PAGES = 3

/**
 * First-run onboarding.
 *
 * The artwork is drawn, not typed. It used to be three emoji (shield, rocket,
 * padlock), which render differently on every OEM font, cannot be tinted, and
 * read as a placeholder. Each page now draws a figure in the same visual
 * language as the connect orb, so onboarding and the app look like one product,
 * and page one is deliberately a smaller echo of the orb the user is about to
 * meet.
 *
 * The language switch is in the header here too. This is the ONE screen a new
 * user cannot navigate away from, and it used to be locked to the phone's
 * language — so the person most likely to need Persian copy had to read three
 * English pages before reaching anything that could switch it.
 *
 * The backdrop is the same [AmbientBackground] as the app, in the brand colour:
 * onboarding is the first impression of the product's room, and it used to be a
 * flat slab.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val accents = LocalAetherAccents.current
    val brand = accents.brand

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(accent = brand, active = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.onboarding_step,
                        pagerState.currentPage + 1,
                        PAGES,
                    ),
                    style = AetherMetaLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                LanguageToggle(accent = brand)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onFinished) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PageArt(page = page, accent = brand, highlight = accents.protected)
                    }
                    Spacer(Modifier.height(40.dp))
                    Text(
                        text = stringResource(
                            when (page) {
                                0 -> R.string.onboarding_title_1
                                1 -> R.string.onboarding_title_2
                                else -> R.string.onboarding_title_3
                            },
                        ),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            when (page) {
                                0 -> R.string.onboarding_body_1
                                1 -> R.string.onboarding_body_2
                                else -> R.string.onboarding_body_3
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(PAGES) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (active) brand else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            ActionPill(
                label = stringResource(
                    if (pagerState.currentPage < PAGES - 1) {
                        R.string.onboarding_next
                    } else {
                        R.string.onboarding_start
                    },
                ),
                onClick = {
                    if (pagerState.currentPage < PAGES - 1) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        onFinished()
                    }
                },
                filled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * Page artwork: an aperture (the app's own motif), a signal profile with one
 * chosen peak, and a field of nodes with a single one lit.
 */
@Composable
private fun PageArt(page: Int, accent: Color, highlight: Color) {
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.size(196.dp)) {
        val radius = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (page) {
            0 -> {
                // A quieter echo of the connect orb: concentric rings plus one
                // arc that has already been "earned".
                repeat(4) { ring ->
                    drawCircle(
                        color = accent.copy(alpha = 0.10f + ring * 0.06f),
                        radius = radius * (0.34f + ring * 0.22f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                val r = radius * 0.9f
                drawArc(
                    color = highlight,
                    startAngle = -90f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
                drawCircle(color = highlight, radius = radius * 0.13f)
            }
            1 -> {
                val heights = listOf(0.30f, 0.55f, 0.95f, 0.45f, 0.68f)
                val gap = size.width / (heights.size + 1)
                heights.forEachIndexed { index, factor ->
                    val x = gap * (index + 1)
                    val isPick = index == 2
                    drawLine(
                        color = if (isPick) highlight else muted,
                        start = Offset(x, size.height * 0.9f),
                        end = Offset(x, size.height * (0.9f - 0.75f * factor)),
                        strokeWidth = if (isPick) 11.dp.toPx() else 6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                val cols = 6
                val spacing = size.width / (cols + 1)
                for (row in 1..cols) {
                    for (col in 1..cols) {
                        val lit = row == 3 && col == 4
                        drawCircle(
                            color = if (lit) highlight else muted,
                            radius = if (lit) 6.dp.toPx() else 2.5.dp.toPx(),
                            center = Offset(spacing * col, spacing * row),
                        )
                    }
                }
                drawCircle(
                    color = highlight.copy(alpha = 0.18f),
                    radius = 20.dp.toPx(),
                    center = Offset(spacing * 4, spacing * 3),
                )
            }
        }
    }
}
