package studio.cluvex.aether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut
import studio.cluvex.aether.ui.theme.AetherMetaLabel

private const val PAGES = 3

/** The design size of the page figure. A ceiling, not a fixed value. */
private val ART_MAX = 190.dp

/**
 * First-run onboarding.
 *
 * The old version used three emoji as its artwork (shield, rocket, padlock),
 * which renders differently on every OEM font, cannot be tinted, and reads as a
 * placeholder. Each page now draws its own figure in the same visual language
 * as the connect ring, so onboarding and the app look like the same product.
 * A step counter and a segmented track replace the three identical dots.
 *
 * 1.3.0: the language pill is in the header here too. This is the ONE screen a
 * new user cannot navigate away from, and it used to be locked to the phone's
 * language — so the person most likely to need Persian copy had to read three
 * English pages before reaching anything that could switch it.
 *
 * 1.4.3 RESPONSIVENESS FIX — the worst layout bug in the app, because it was on
 * the first screen anyone ever sees and there was no way past it. A page was a
 * non-scrolling, vertically centred column of a fixed 190dp figure, a 40dp gap,
 * a 26sp headline and a body paragraph. That needs roughly 420dp of height
 * BEFORE the header, the progress track and the 56dp button. In landscape, on a
 * short device, in split-screen, or simply at a large system font scale, the
 * body copy and sometimes the headline were pushed out of the viewport and
 * clipped with nothing to scroll — so the explanation of what the app does was
 * unreadable and the "Next" button was the only thing left on screen.
 *
 * The fix keeps the centred composition on tall screens and degrades to a
 * scroll on short ones: the page content is wrapped in a scroll container whose
 * inner column is at least one viewport tall, so `Arrangement.Center` still
 * centres when there is room and the content simply scrolls when there is not.
 * The figure is also sized from the real constraints instead of a constant, so
 * it shrinks with the viewport rather than eating it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 12.dp),
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                LanguageToggle(accent = accent)
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

            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                // Measured once per configuration, then shared by every page so
                // the figure does not resize as you swipe.
                val viewport = maxHeight
                val artSize = minOf(ART_MAX, viewport * 0.34f, maxWidth * 0.62f)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // At least one viewport tall, so Arrangement.Center is
                        // still a real centring on a tall screen; taller than
                        // that on a short one, which is what makes it scroll
                        // instead of clip.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewport),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PageArt(page = page, accent = accent, size = artSize)
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
                                style = MaterialTheme.typography.headlineLarge,
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
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(PAGES) { index ->
                    val active = pagerState.currentPage == index
                    // The track used to snap between two colours the instant the
                    // page settled, which is the only hard cut left in a UI that
                    // tweens every other state change.
                    val tint by animateColorAsState(
                        targetValue = if (active) {
                            accent
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
                        label = "track",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(tint),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

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
                    .heightIn(min = 56.dp),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Page artwork. Drawn, not typed: an aperture (the app's own motif), a signal
 * profile, and a field of dots with a single one lit.
 *
 * [size] is passed in rather than hardcoded so the figure can shrink on short
 * viewports instead of pushing the copy off the screen.
 */
@Composable
private fun PageArt(page: Int, accent: Color, size: Dp) {
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        when (page) {
            0 -> {
                repeat(4) { ring ->
                    drawCircle(
                        color = accent.copy(alpha = 0.10f + ring * 0.07f),
                        radius = radius * (0.34f + ring * 0.22f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                drawCircle(color = accent, radius = radius * 0.13f)
            }
            1 -> {
                val heights = listOf(0.30f, 0.55f, 0.95f, 0.45f, 0.68f)
                val gap = this.size.width / (heights.size + 1)
                heights.forEachIndexed { index, factor ->
                    val x = gap * (index + 1)
                    val isPick = index == 2
                    drawLine(
                        color = if (isPick) accent else muted,
                        start = Offset(x, this.size.height * 0.9f),
                        end = Offset(x, this.size.height * (0.9f - 0.75f * factor)),
                        strokeWidth = if (isPick) 10.dp.toPx() else 6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                val cols = 6
                val spacing = this.size.width / (cols + 1)
                for (row in 1..cols) {
                    for (col in 1..cols) {
                        val lit = row == 3 && col == 4
                        drawCircle(
                            color = if (lit) accent else muted,
                            radius = if (lit) 6.dp.toPx() else 2.5.dp.toPx(),
                            center = Offset(spacing * col, spacing * row),
                        )
                    }
                }
                drawCircle(
                    color = accent.copy(alpha = 0.18f),
                    radius = 18.dp.toPx(),
                    center = Offset(spacing * 4, spacing * 3),
                )
            }
        }
        // Keep the centre reference used by page 0 meaningful for all pages.
        if (page == 0) {
            drawCircle(color = accent.copy(alpha = 0.06f), radius = radius * 0.13f, center = Offset(cx, cy))
        }
    }
}
