package studio.cluvex.aether.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import studio.cluvex.aether.R
import studio.cluvex.aether.data.ChannelPromoState
import studio.cluvex.aether.ui.theme.AetherAccents
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOutExpo
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/**
 * Decides, once per app open, whether the Telegram channel popup shows, and
 * shows it on top of Home.
 *
 * The decision is frozen at the first read of [promo] for this activity
 * instance (and survives rotation): acknowledging the popup writes the store,
 * and a live re-evaluation would otherwise flip the very dialog that is being
 * closed into its "second time" variant for a frame.
 *
 * [onSettled] runs once the popup is out of the way, or straight away when it
 * is not shown this time. MainActivity hangs the notification permission
 * prompt on it, so a system dialog never lands on top of the popup.
 */
@Composable
internal fun ChannelPromoHost(
    promo: ChannelPromoState?,
    onAcknowledged: (optOut: Boolean) -> Unit,
    onSettled: () -> Unit,
) {
    // Still reading the store: decide nothing yet.
    if (promo == null) return
    val seenAtOpen = rememberSaveable { promo.timesSeen }
    val optedOutAtOpen = rememberSaveable { promo.optedOut }
    val rules = remember(seenAtOpen, optedOutAtOpen) { channelPromoRules(seenAtOpen, optedOutAtOpen) }
    var closed by rememberSaveable { mutableStateOf(false) }
    val visible = rules.show && !closed

    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(visible) { if (!visible) settled() }

    if (visible) {
        ChannelPromoDialog(
            lockSeconds = rules.lockSeconds,
            offersOptOut = rules.offersOptOut,
            onConfirm = { optOut ->
                closed = true
                onAcknowledged(optOut)
            },
        )
    }
}

/**
 * The popup itself. Deliberately not scrollable: everything fits on one card,
 * and on short windows (or large font scales) the hero is dropped, never the
 * copy or the buttons.
 *
 * The first time ([offersOptOut] false) it is mandatory: back and taps outside
 * do nothing, and OK unlocks after [lockSeconds]. Later on, back counts as OK.
 */
@Composable
private fun ChannelPromoDialog(
    lockSeconds: Int,
    offersOptOut: Boolean,
    onConfirm: (optOut: Boolean) -> Unit,
) {
    // Saveable, so a rotation does not restart the countdown.
    var remaining by rememberSaveable { mutableStateOf(lockSeconds.coerceAtLeast(0)) }
    var optOut by rememberSaveable { mutableStateOf(false) }
    // A double tap on OK must not acknowledge twice.
    var confirmed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
    }

    val confirm: () -> Unit = {
        if (channelPromoCanConfirm(remaining) && !confirmed) {
            confirmed = true
            onConfirm(optOut && offersOptOut)
        }
    }

    Dialog(
        onDismissRequest = { if (offersOptOut) confirm() },
        properties = DialogProperties(
            dismissOnBackPress = offersOptOut,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        ChannelPromoCard(
            remaining = remaining,
            lockSeconds = lockSeconds,
            offersOptOut = offersOptOut,
            optOut = optOut,
            onOptOutChange = { optOut = it },
            onConfirm = confirm,
        )
    }
}

@Composable
private fun ChannelPromoCard(
    remaining: Int,
    lockSeconds: Int,
    offersOptOut: Boolean,
    optOut: Boolean,
    onOptOutChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    val accents = LocalAetherAccents.current
    val reduced = LocalReducedMotion.current
    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < 600 || config.fontScale > 1.3f
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Arrives like the rest of the app: fast, then a long soft landing.
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) appear.animateTo(1f, tween(AetherDur.Slow, easing = AetherEaseOutExpo))
    }
    val wash = accents.brandWash

    Surface(
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val v = appear.value
                alpha = v
                scaleX = 0.92f + 0.08f * v
                scaleY = 0.92f + 0.08f * v
                translationY = (1f - v) * 24.dp.toPx()
            },
        shape = RoundedCornerShape(28.dp),
        color = accents.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accents.cardBorder),
        shadowElevation = 16.dp,
    ) {
        Column(
            Modifier
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(wash, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.6f,
                        ),
                    )
                }
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!compact) {
                PromoHero(accents = accents, animate = !reduced)
                Spacer(Modifier.height(16.dp))
            }
            Surface(
                color = accents.brand.copy(alpha = 0.14f),
                contentColor = accents.brand,
                shape = CircleShape,
            ) {
                Text(
                    stringResource(R.string.channel_promo_eyebrow),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.channel_promo_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.channel_promo_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            ChannelLinkChip(accents = accents, clipboard = clipboard)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { openChannel(context, clipboard) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = accents.brand.copy(alpha = 0.14f),
                    contentColor = accents.brand,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.channel_promo_join), style = MaterialTheme.typography.labelLarge)
            }
            if (offersOptOut) {
                Spacer(Modifier.height(8.dp))
                ChannelPromoOptOut(checked = optOut, accents = accents, onChange = onOptOutChange)
            }
            Spacer(Modifier.height(16.dp))
            ChannelPromoConfirm(
                remaining = remaining,
                lockSeconds = lockSeconds,
                accents = accents,
                onConfirm = onConfirm,
            )
        }
    }
}

/** A paper plane in the brand orb, with a slow ripple. Still under "Remove animations". */
@Composable
private fun PromoHero(accents: AetherAccents, animate: Boolean) {
    val t: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "promo-pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(AetherDur.Loop, easing = LinearEasing), RepeatMode.Restart),
            label = "promo-t",
        )
    } else {
        remember { mutableFloatStateOf(0.35f) }
    }
    val brand = accents.brand
    Box(Modifier.size(112.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.22f
            drawCircle(
                Brush.radialGradient(listOf(brand.copy(alpha = 0.30f), Color.Transparent), center = c, radius = r * 2.2f),
                radius = r * 2.2f,
                center = c,
            )
            for (k in 0..1) {
                val p = (t.value + k * 0.5f) % 1f
                drawCircle(
                    brand.copy(alpha = 0.45f * (1f - p)),
                    radius = r * (1f + p * 0.95f),
                    center = c,
                    style = Stroke(2.dp.toPx()),
                )
            }
            drawCircle(brand, radius = r, center = c)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(c.x - r * 0.35f, c.y - r * 0.4f),
                    radius = r,
                ),
                radius = r,
                center = c,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.Send,
            contentDescription = null,
            tint = accents.onBrand,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The link, copyable with one tap. Always laid out LTR: it is a URL, and the
 * copy action reads naturally after it in both languages.
 */
@Composable
private fun ChannelLinkChip(accents: AetherAccents, clipboard: ClipboardManager) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800L)
            copied = false
        }
    }
    val quick = aetherDuration(AetherDur.Quick)
    val copyLabel = stringResource(R.string.channel_promo_copy)
    val shape = RoundedCornerShape(16.dp)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(accents.brand.copy(alpha = 0.08f))
                .border(1.dp, accents.cardBorder, shape)
                .clickable(onClickLabel = copyLabel, role = Role.Button) {
                    clipboard.setText(AnnotatedString(TELEGRAM_CHANNEL_URL))
                    copied = true
                }
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                TELEGRAM_CHANNEL_LABEL,
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                color = accents.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = copied,
                transitionSpec = { fadeIn(tween(quick)) togetherWith fadeOut(tween(quick)) },
                label = "promo-copy",
            ) { done ->
                val tint = if (done) accents.protected else accents.brand
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Icon(
                        if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (done) R.string.channel_promo_copied else R.string.channel_promo_copy),
                        style = MaterialTheme.typography.labelLarge,
                        color = tint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelPromoOptOut(checked: Boolean, accents: AetherAccents, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = accents.brand,
                checkmarkColor = accents.onBrand,
                uncheckedColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.channel_promo_dont_show),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** OK, behind a draining ring while the first-time lock runs. */
@Composable
private fun ChannelPromoConfirm(
    remaining: Int,
    lockSeconds: Int,
    accents: AetherAccents,
    onConfirm: () -> Unit,
) {
    val unlocked = channelPromoCanConfirm(remaining)
    val fraction by animateFloatAsState(
        targetValue = channelPromoLockFraction(remaining, lockSeconds),
        animationSpec = tween(aetherDuration(1_000), easing = LinearEasing),
        label = "promo-lock",
    )
    Button(
        onClick = onConfirm,
        enabled = unlocked,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = accents.brand,
            contentColor = accents.onBrand,
            disabledContainerColor = accents.brand.copy(alpha = 0.16f),
            disabledContentColor = accents.brand,
        ),
    ) {
        if (unlocked) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.channel_promo_ok), style = MaterialTheme.typography.titleMedium)
        } else {
            val ring = accents.brand
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    ring.copy(alpha = 0.25f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(stroke),
                )
                drawArc(
                    ring,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.channel_promo_ok_wait, remaining),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Opens the channel in Telegram (or a browser). Where nothing can open it, the
 * link is copied instead so the tap is never a dead end.
 */
private fun openChannel(context: Context, clipboard: ClipboardManager) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        clipboard.setText(AnnotatedString(TELEGRAM_CHANNEL_URL))
        Toast.makeText(context, R.string.channel_promo_open_failed, Toast.LENGTH_LONG).show()
    }
}
