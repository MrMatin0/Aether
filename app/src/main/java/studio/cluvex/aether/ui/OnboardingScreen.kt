package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents

private const val PAGES = 3

/** The size the page artwork is drawn at when there is room for it. */
private val ART_MAX = 196.dp

/** The smallest it may shrink to before it stops reading as a figure. */
private val ART_MIN = 104.dp

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
 *
 * WHAT THIS PASS FIXES
 *
 *   1. BACK THREW YOU OUT OF FIRST RUN. There was no BackHandler, so the system
 *      back gesture on page 2 or 3 went straight to the Activity and finished it.
 *      A three-page pager is the most obviously back-navigable thing in any app;
 *      being unable to return to the page you just read, on the one screen with
 *      no other way out, is the kind of thing people read as "this app crashed".
 *      Back is a page back now, and only leaves from page one.
 *   2. THE COPY FELL OFF SHORT SCREENS. The artwork was a hard 196dp square with
 *      a fixed 40dp gap under it, inside a centred column with NO scroll
 *      container. On a short viewport — a small phone, a phone in split screen,
 *      or any phone at a large system font scale — the body paragraph simply ran
 *      past the bottom edge with no way to reach it, and the paragraph is the
 *      only thing on the page that explains anything. Both the figure and the
 *      gap are now proportional to the height the pager actually got, so the
 *      text is always the part that keeps its space.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val accents = LocalAetherAccents.current
    val brand = accents.brand

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

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
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Proportional, not fixed: the words are the payload here, so
                    // the decoration is what gives up space when there is less of
                    // it. See the class comment.
                    val art = (maxHeight * 0.30f).coerceIn(ART_MIN, ART_MAX)
                    val gap = (maxHeight * 0.06f).coerceIn(16.dp, 40.dp)
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PageArt(
                                page = page,
                                accent = brand,
                                highlight = accents.protected,
                                size = art,
                            )
                        }
                        Spacer(Modifier.height(gap))
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
 *
 * Every dimension inside is a fraction of [size], so the figures stay themselves
 * at any diameter the pager hands them.
 */
@Composable
private fun PageArt(page: Int, accent: Color, highlight: Color, size: Dp) {
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
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
                val gap = this.size.width / (heights.size + 1)
                heights.forEachIndexed { index, factor ->
                    val x = gap * (index + 1)
                    val isPick = index == 2
                    drawLine(
                        color = if (isPick) highlight else muted,
                        start = Offset(x, this.size.height * 0.9f),
                        end = Offset(x, this.size.height * (0.9f - 0.75f * factor)),
                        strokeWidth = if (isPick) 11.dp.toPx() else 6.dp.toPx(),
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
