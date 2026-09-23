package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NoAccounts
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.AmbientBackground
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.AetherAccents
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOutExpo
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-run welcome (v1.5.0 redesign).
 *
 * Four short beats that tell a story instead of listing features: hello, what
 * Smart mode does for you, what to do when the road is blocked (chain), and
 * what Android is about to ask and why. The last beat exists so the VPN
 * consent dialog and the notification prompt that MainActivity raises right
 * after this screen are questions the user can actually answer.
 *
 * Contracts kept from the previous version:
 *  - finishing never starts a tunnel; it only calls [onFinished], and at most once;
 *  - every page scrolls, so 200% font scale and landscape still fit;
 *  - on short windows the illustrations are dropped, never the copy;
 *  - with system "Remove animations" nothing moves (see [LocalReducedMotion]).
 *
 * Performance: only the settled page animates its hero, and every animated
 * value is read in the draw phase, so a running loop redraws one Canvas and
 * never recomposes the screen.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val last = WelcomePages.lastIndex
    val pager = rememberPagerState(pageCount = { WelcomePages.size })
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val accents = LocalAetherAccents.current

    // A double tap on the last page must not finish twice.
    var finished by rememberSaveable { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onFinished()
        }
    }
    val go: (Int) -> Unit = { target ->
        val page = target.coerceIn(0, last)
        scope.launch {
            if (reduced) {
                pager.scrollToPage(page)
            } else {
                pager.animateScrollToPage(page, animationSpec = tween(AetherDur.Slow, easing = AetherEaseOutExpo))
            }
        }
    }

    val current = pager.currentPage
    BackHandler(enabled = current > 0) { go(current - 1) }

    val tone = rememberWelcomeTone(welcomePageAt(current), accents)
    val quick = aetherDuration(AetherDur.Quick)
    val base = aetherDuration(AetherDur.Base)
    val ctaRes = welcomeCopy(welcomePageAt(current)).cta
    val onLastPage = isLastWelcomePage(current)

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground(accent = tone.color, active = true)
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxHeight < 560.dp
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    WelcomeTopBar(tone = tone.color, showSkip = welcomeShowsSkip(current), onSkip = finish)
                    WelcomeProgress(
                        pager = pager,
                        tone = tone.color,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth(),
                    ) { index ->
                        WelcomePageContent(
                            page = welcomePageAt(index),
                            tone = tone,
                            compact = compact,
                            animate = !reduced && pager.settledPage == index,
                            pageOffset = { ((pager.currentPage - index) + pager.currentPageOffsetFraction).absoluteValue },
                        )
                    }
                    Row(
                        Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = welcomeShowsBack(current),
                            enter = fadeIn(tween(quick)) + expandHorizontally(tween(base)),
                            exit = fadeOut(tween(quick)) + shrinkHorizontally(tween(base)),
                        ) {
                            Row {
                                FilledTonalIconButton(
                                    onClick = { go(pager.currentPage - 1) },
                                    modifier = Modifier.size(56.dp),
                                    shape = MaterialTheme.shapes.large,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = tone.wash,
                                        contentColor = tone.color,
                                    ),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                                }
                                Spacer(Modifier.width(12.dp))
                            }
                        }
                        Button(
                            // settledPage, not currentPage: mid-swipe the pager can already
                            // report the last page while the user is still looking at the one before.
                            onClick = { if (isLastWelcomePage(pager.settledPage)) finish() else go(pager.currentPage + 1) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(containerColor = tone.color, contentColor = tone.on),
                        ) {
                            AnimatedContent(
                                targetState = ctaRes,
                                transitionSpec = { fadeIn(tween(quick)) togetherWith fadeOut(tween(quick)) },
                                label = "welcome-cta",
                            ) { res ->
                                Text(stringResource(res), style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (onLastPage) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ tone --

@Immutable
private data class WelcomeTone(val color: Color, val on: Color, val wash: Color)

/**
 * Brand for the story, protected (the "you are safe" colour) for the last
 * beat only, so the Start button already speaks the colour of a live tunnel.
 * Working/failed tones are never used here: they mean something on Home.
 */
@Composable
private fun rememberWelcomeTone(page: WelcomePage, a: AetherAccents): WelcomeTone {
    val target = when (page) {
        WelcomePage.PRIVACY -> WelcomeTone(a.protected, a.onProtected, a.protectedWash)
        else -> WelcomeTone(a.brand, a.onBrand, a.brandWash)
    }
    val spec = tween<Color>(aetherDuration(AetherDur.Slow))
    val color by animateColorAsState(target.color, spec, label = "welcome-tone")
    val on by animateColorAsState(target.on, spec, label = "welcome-on")
    val wash by animateColorAsState(target.wash, spec, label = "welcome-wash")
    return WelcomeTone(color, on, wash)
}

// ------------------------------------------------------------------ copy --

private class WelcomeCopy(
    val eyebrow: Int,
    val title: Int,
    val body: Int,
    val cta: Int,
    val points: List<Pair<ImageVector, Int>>,
)

private fun welcomeCopy(page: WelcomePage): WelcomeCopy = when (page) {
    WelcomePage.HELLO -> WelcomeCopy(
        R.string.welcome_hello_eyebrow, R.string.welcome_hello_title, R.string.welcome_hello_body, R.string.welcome_hello_cta,
        listOf(
            Icons.Rounded.Bolt to R.string.welcome_hello_point_1,
            Icons.Rounded.NoAccounts to R.string.welcome_hello_point_2,
            Icons.Rounded.Translate to R.string.welcome_hello_point_3,
        ),
    )
    WelcomePage.SMART -> WelcomeCopy(
        R.string.welcome_smart_eyebrow, R.string.welcome_smart_title, R.string.welcome_smart_body, R.string.welcome_smart_cta,
        listOf(
            Icons.Rounded.AutoAwesome to R.string.welcome_smart_point_1,
            Icons.Rounded.Shield to R.string.welcome_smart_point_2,
            Icons.Rounded.Tune to R.string.welcome_smart_point_3,
        ),
    )
    WelcomePage.CHAIN -> WelcomeCopy(
        R.string.welcome_chain_eyebrow, R.string.welcome_chain_title, R.string.welcome_chain_body, R.string.welcome_chain_cta,
        listOf(
            Icons.Rounded.Layers to R.string.welcome_chain_point_1,
            Icons.Rounded.Timeline to R.string.welcome_chain_point_2,
            Icons.Rounded.Speed to R.string.welcome_chain_point_3,
        ),
    )
    WelcomePage.PRIVACY -> WelcomeCopy(
        R.string.welcome_privacy_eyebrow, R.string.welcome_privacy_title, R.string.welcome_privacy_body, R.string.welcome_privacy_cta,
        listOf(
            Icons.Rounded.PhoneAndroid to R.string.welcome_privacy_point_1,
            Icons.Rounded.VpnKey to R.string.welcome_privacy_point_2,
            Icons.Rounded.NotificationsActive to R.string.welcome_privacy_point_3,
        ),
    )
}

// ------------------------------------------------------------- chrome --

@Composable
private fun WelcomeTopBar(tone: Color, showSkip: Boolean, onSkip: () -> Unit) {
    Row(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        LanguageToggle(accent = tone)
        // The slot stays when Skip goes away, so the language toggle does not
        // jump sideways on the last page.
        Box(Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
            if (showSkip) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.welcome_skip)) }
            }
        }
    }
}

/** Segments fill continuously with the swipe; the drawing reads pager state in the draw phase only. */
@Composable
private fun WelcomeProgress(pager: PagerState, tone: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val count = pager.pageCount
    val stepText = stringResource(R.string.welcome_step, pager.currentPage + 1, count)
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = stepText
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f).height(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { index ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(track).drawBehind {
                        val fill = welcomeSegmentFill(pager.currentPage + pager.currentPageOffsetFraction, index)
                        val w = size.width * fill
                        val x = if (rtl) size.width - w else 0f
                        drawRect(tone, Offset(x, 0f), Size(w, size.height))
                    },
                )
            }
        }
        Text(stepText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --------------------------------------------------------------- page --

@Composable
private fun WelcomePageContent(
    page: WelcomePage,
    tone: WelcomeTone,
    compact: Boolean,
    animate: Boolean,
    pageOffset: () -> Float,
) {
    val copy = welcomeCopy(page)
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - 0.65f * pageOffset().coerceIn(0f, 1f) }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        if (!compact) {
            val t = when (page) {
                WelcomePage.HELLO -> rememberWelcomeLoop(animate, AetherDur.Halo, still = 0.12f)
                WelcomePage.SMART -> rememberWelcomeLoop(animate, AetherDur.Loop, still = 0.55f)
                WelcomePage.CHAIN -> rememberWelcomeLoop(animate, AetherDur.Loop, still = 0.99f)
                WelcomePage.PRIVACY -> rememberWelcomeLoop(animate, AetherDur.Loop, still = 0.3f)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(208.dp)
                    .graphicsLayer {
                        val s = 1f - 0.12f * pageOffset().coerceIn(0f, 1f)
                        scaleX = s
                        scaleY = s
                    }
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    WelcomePage.HELLO -> OrbHero(tone, t)
                    WelcomePage.SMART -> RouteChoiceHero(tone, t)
                    WelcomePage.CHAIN -> ChainHero(tone, t)
                    WelcomePage.PRIVACY -> PrivacyHero(tone, t)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Surface(color = tone.wash, contentColor = tone.color, shape = MaterialTheme.shapes.small) {
            Text(
                stringResource(copy.eyebrow),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(copy.title),
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(copy.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            copy.points.forEach { (icon, text) -> WelcomePoint(icon, stringResource(text), tone) }
        }
        if (page == WelcomePage.PRIVACY) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.passage_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun WelcomePoint(icon: ImageVector, text: String, tone: WelcomeTone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(tone.wash),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tone.color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// --------------------------------------------------------------- heroes --

/** A 0..1 loop while [animate], otherwise a fixed, representative frame. */
@Composable
private fun rememberWelcomeLoop(animate: Boolean, periodMs: Int, still: Float): State<Float> {
    if (!animate) return remember(still) { mutableFloatStateOf(still) }
    val transition = rememberInfiniteTransition(label = "welcome-loop")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "welcome-t",
    )
}

/** Page 1: the connect orb from Home, with satellites in orbit. */
@Composable
private fun OrbHero(tone: WelcomeTone, t: State<Float>) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val backdrop = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension * 0.2f
        drawCircle(
            Brush.radialGradient(listOf(tone.color.copy(alpha = 0.32f), Color.Transparent), center = c, radius = r * 2.6f),
            radius = r * 2.6f,
            center = c,
        )
        drawCircle(ring, radius = r * 1.6f, center = c, style = Stroke(1.dp.toPx()))
        drawCircle(
            ring,
            radius = r * 2.2f,
            center = c,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))),
        )
        drawCircle(tone.color, radius = r, center = c)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.4f),
                radius = r,
            ),
            radius = r,
            center = c,
        )
        // Power glyph
        val g = r * 0.42f
        val stroke = 3.dp.toPx()
        drawArc(
            tone.on,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(c.x - g, c.y - g),
            size = Size(g * 2f, g * 2f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(tone.on, Offset(c.x, c.y - g * 1.25f), Offset(c.x, c.y - g * 0.2f), stroke, StrokeCap.Round)
        // Whole-number speeds only, so the loop restart is seamless.
        val time = t.value
        listOf(Triple(1.6f, 0f, 1f), Triple(2.2f, 0.35f, -1f), Triple(1.6f, 0.5f, 1f)).forEach { (k, phase, speed) ->
            val angle = ((time * speed + phase) * 2.0 * PI).toFloat()
            val p = Offset(c.x + cos(angle) * r * k, c.y + sin(angle) * r * k)
            drawCircle(backdrop, radius = 7.dp.toPx(), center = p)
            drawCircle(tone.color, radius = 4.5.dp.toPx(), center = p)
        }
    }
}

/** Page 2: three possible routes, two blocked, Smart mode takes the one that works. */
@Composable
private fun RouteChoiceHero(tone: WelcomeTone, t: State<Float>) {
    val dim = MaterialTheme.colorScheme.outline
    val backdrop = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measure = remember { PathMeasure() }
    Canvas(Modifier.fillMaxSize()) {
        val cy = size.height / 2f
        val inset = 32.dp.toPx()
        val left = Offset(inset, cy)
        val right = Offset(size.width - inset, cy)
        val bend = size.height * 0.3f
        fun route(dy: Float) = Path().apply {
            moveTo(left.x, left.y)
            quadraticTo(size.width / 2f, cy + dy * 2f, right.x, right.y)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 7.dp.toPx()))
        val x = 5.dp.toPx()
        listOf(-bend, 0f).forEach { dy ->
            drawPath(route(dy), dim, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            val m = Offset(size.width / 2f, cy + dy)
            drawCircle(backdrop, radius = 10.dp.toPx(), center = m)
            drawLine(dim, m + Offset(-x, -x), m + Offset(x, x), 2.dp.toPx(), StrokeCap.Round)
            drawLine(dim, m + Offset(-x, x), m + Offset(x, -x), 2.dp.toPx(), StrokeCap.Round)
        }
        val chosen = route(bend)
        drawPath(chosen, tone.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        measure.setPath(chosen, false)
        // Traffic flows from "you" to the internet, whichever side "you" is on.
        val progress = if (rtl) 1f - t.value else t.value
        val dot = measure.getPosition(measure.length * progress)
        drawCircle(tone.color.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = dot)
        drawCircle(tone.color, radius = 5.dp.toPx(), center = dot)
        val you = if (rtl) right else left
        val net = if (rtl) left else right
        drawCircle(backdrop, radius = 12.dp.toPx(), center = you)
        drawCircle(ink, radius = 12.dp.toPx(), center = you, style = Stroke(2.dp.toPx()))
        drawCircle(ink, radius = 4.dp.toPx(), center = you)
        drawCircle(tone.color, radius = 12.dp.toPx(), center = net)
        drawCircle(tone.on, radius = 4.dp.toPx(), center = net)
    }
}

/** Page 3: you, up to three hops, the internet; the hops light up one by one. */
@Composable
private fun ChainHero(tone: WelcomeTone, t: State<Float>) {
    val dim = MaterialTheme.colorScheme.outlineVariant
    val backdrop = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val names = listOf(
        stringResource(R.string.chain_aether),
        stringResource(R.string.chain_psiphon),
        stringResource(R.string.chain_tor),
    )
    // Forced LTR and reversed in RTL, so the label reads in the same visual
    // order as the nodes drawn above it.
    val label = (if (rtl) names.reversed() else names).joinToString("  ·  ")
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val cy = size.height / 2f
            val inset = 28.dp.toPx()
            val span = size.width - inset * 2f
            val xs = List(5) { i ->
                val px = inset + span * i / 4f
                if (rtl) size.width - px else px
            }
            // One beat per node, then one beat with the whole path lit.
            val lit = (t.value * 6f).toInt().coerceIn(0, 5)
            for (i in 0 until 4) {
                val on = i + 1 < lit
                drawLine(
                    if (on) tone.color else dim,
                    Offset(xs[i], cy),
                    Offset(xs[i + 1], cy),
                    (if (on) 3.dp else 1.5.dp).toPx(),
                    StrokeCap.Round,
                )
            }
            xs.forEachIndexed { i, px ->
                val c = Offset(px, cy)
                val on = i < lit
                val hop = i in 1..3
                val r = (if (hop) 16.dp else 7.dp).toPx()
                drawCircle(backdrop, radius = r + 4.dp.toPx(), center = c)
                if (hop) {
                    drawCircle(if (on) tone.color else dim, radius = r, center = c, style = Stroke(2.dp.toPx()))
                    drawCircle(if (on) tone.color else dim, radius = r * 0.4f, center = c)
                } else {
                    drawCircle(if (on) tone.color else ink, radius = r, center = c)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Page 4: a calm shield with a slow ripple. */
@Composable
private fun PrivacyHero(tone: WelcomeTone, t: State<Float>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.2f
            for (k in 0..1) {
                val p = (t.value + k * 0.5f) % 1f
                drawCircle(
                    tone.color.copy(alpha = 0.35f * (1f - p)),
                    radius = r * (1f + p * 1.3f),
                    center = c,
                    style = Stroke(2.dp.toPx()),
                )
            }
            drawCircle(tone.color, radius = r, center = c)
        }
        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = tone.on, modifier = Modifier.size(44.dp))
    }
}
