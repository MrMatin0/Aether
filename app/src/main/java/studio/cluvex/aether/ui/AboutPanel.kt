package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion
import studio.cluvex.aether.ui.theme.aetherDuration

/** This build's own repository: source, releases and the issue tracker. */
private const val REPO_URL = "https://github.com/MrMatin0/Aether"
private const val REPO_LABEL = "github.com/MrMatin0/Aether"

/** The upstream project whose Aether core powers every connection. */
private const val CORE_URL = "https://github.com/CluvexStudio/Aether"
private const val CORE_LABEL = "github.com/CluvexStudio/Aether"

/** The developer's Telegram. */
private const val TELEGRAM_URL = "https://t.me/MTinF"
private const val TELEGRAM_LABEL = "@MTinF"

/** Telegram's brand blue. Used for the glyph only, never for text (contrast). */
private val TelegramBlue = Color(0xFF2AABEE)

/**
 * ABOUT.
 *
 * One scrolling story, top to bottom:
 *  1. a hero with the app identity, build/core versions and a short summary;
 *  2. credit to Cluvex Studio for the Aether core;
 *  3. the developer: Telegram and this fork's repository;
 *  4. thanks to the people who helped shape the app;
 *  5. a quiet footer.
 *
 * Hosted inside [SettingsPageBody], which already scrolls and pads, so this
 * panel must not add its own scroll container. All motion collapses to static
 * when the system "remove animations" setting is on ([LocalReducedMotion]).
 */
@Composable
fun AboutPanel(modifier: Modifier = Modifier) {
    val open = rememberLinkOpener()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Reveal(0) { HeroCard() }

        Reveal(1) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel(Icons.Rounded.Memory, stringResource(R.string.aboutpage_section_core))
                CoreCreditCard(onClick = { open(CORE_URL) })
            }
        }

        Reveal(2) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel(Icons.Rounded.Person, stringResource(R.string.aboutpage_section_dev))
                AboutCard {
                    LinkRow(
                        icon = painterResource(R.drawable.ic_telegram),
                        iconTint = TelegramBlue,
                        iconWash = TelegramBlue.copy(alpha = 0.14f),
                        title = stringResource(R.string.aboutpage_telegram_title),
                        value = TELEGRAM_LABEL,
                        onClick = { open(TELEGRAM_URL) },
                    )
                    RowDivider()
                    val accents = LocalAetherAccents.current
                    LinkRow(
                        icon = painterResource(R.drawable.ic_github),
                        iconTint = MaterialTheme.colorScheme.onSurface,
                        iconWash = accents.brandWash,
                        title = stringResource(R.string.aboutpage_repo_title),
                        value = REPO_LABEL,
                        onClick = { open(REPO_URL) },
                    )
                }
            }
        }

        Reveal(3) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel(Icons.Rounded.Favorite, stringResource(R.string.aboutpage_section_thanks))
                ThanksCard()
            }
        }

        Reveal(4) {
            Text(
                stringResource(R.string.aboutpage_footer),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ hero ----

@Composable
private fun HeroCard() {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accents.brandWash, accents.card, accents.protectedWash)))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(accents.brand.copy(alpha = 0.55f), accents.cardBorder, accents.protected.copy(alpha = 0.45f)),
                ),
                shape,
            )
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AuroraOrb()
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.titleSmall,
            color = accents.brand,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        VersionPill()
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.aboutpage_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            FeatureChip(stringResource(R.string.aboutpage_chip_smart), accents.brand, Modifier.weight(1f, fill = false))
            FeatureChip(stringResource(R.string.aboutpage_chip_dpi), accents.protected, Modifier.weight(1f, fill = false))
            FeatureChip(stringResource(R.string.aboutpage_chip_private), accents.working, Modifier.weight(1f, fill = false))
        }
    }
}

/** A slowly turning aurora ring around the shield. Static under reduced motion. */
@Composable
private fun AuroraOrb() {
    val accents = LocalAetherAccents.current
    val reduced = LocalReducedMotion.current
    val spin: State<Float>
    val breathe: State<Float>
    if (reduced) {
        spin = remember { mutableFloatStateOf(0f) }
        breathe = remember { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "aboutOrb")
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
            label = "aboutOrbSpin",
        )
        breathe = transition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "aboutOrbBreathe",
        )
    }
    val ringColors = listOf(accents.brand, accents.protected, accents.working, accents.brand)
    Box(Modifier.size(112.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            // Values are read here, in the draw phase, so the animation only
            // redraws this canvas and never recomposes the page.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accents.brand.copy(alpha = 0.38f * breathe.value), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            rotate(spin.value) {
                drawCircle(
                    brush = Brush.sweepGradient(ringColors, center = center),
                    radius = radius - 12.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            rotate(-spin.value * 0.6f) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, accents.protected.copy(alpha = 0.6f), Color.Transparent),
                        center = center,
                    ),
                    radius = radius - 4.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(accents.brand, accents.protected))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(30.dp), tint = accents.onBrand)
        }
    }
}

@Composable
private fun VersionPill() {
    val accents = LocalAetherAccents.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(accents.brand.copy(alpha = 0.12f))
            .border(1.dp, accents.brand.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accents.protected))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.hub_state_version, BuildConfig.VERSION_NAME, BuildConfig.CORE_VERSION),
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FeatureChip(label: String, tone: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = tone)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- credits ---

@Composable
private fun CoreCreditCard(onClick: () -> Unit) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accents.brandWash, accents.card)))
            .border(1.dp, Brush.horizontalGradient(listOf(accents.brand.copy(alpha = 0.6f), accents.cardBorder)), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(painterResource(R.drawable.ic_github), MaterialTheme.colorScheme.onSurface, accents.card)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.aboutpage_core_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.aboutpage_core_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    CORE_LABEL,
                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ThanksCard() {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accents.workingWash, accents.card, accents.brandWash)))
            .border(1.dp, accents.cardBorder, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Favorite, null, Modifier.padding(top = 2.dp).size(18.dp), tint = accents.failed)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.aboutpage_thanks_body),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PersonChip(
                initial = stringResource(R.string.aboutpage_taraneh_initial),
                name = stringResource(R.string.aboutpage_taraneh),
                gradient = listOf(accents.failed, accents.working),
                onGradient = accents.onWorking,
                modifier = Modifier.weight(1f),
            )
            PersonChip(
                initial = stringResource(R.string.aboutpage_sina_initial),
                name = stringResource(R.string.aboutpage_sina),
                gradient = listOf(accents.brand, accents.protected),
                onGradient = accents.onBrand,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PersonChip(
    initial: String,
    name: String,
    gradient: List<Color>,
    onGradient: Color,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(accents.card)
            .border(1.dp, accents.cardBorder, shape)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(gradient))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onGradient,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.aboutpage_contributor),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// --------------------------------------------------------------- building ---

@Composable
private fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val accents = LocalAetherAccents.current
    Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = accents.brand)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accents.card)
            .border(1.dp, accents.cardBorder, shape),
    ) { content() }
}

@Composable
private fun RowDivider() {
    val accents = LocalAetherAccents.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 74.dp, end = 16.dp)
            .height(1.dp)
            .background(accents.cardBorder),
    )
}

@Composable
private fun IconTile(icon: Painter, tint: Color, wash: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(wash),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
private fun LinkRow(
    icon: Painter,
    iconTint: Color,
    iconWash: Color,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, iconTint, iconWash)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            rememberVectorPainter(Icons.Rounded.OpenInNew),
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Staggered fade-and-rise on first show. Instant under reduced motion. */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = aetherDuration(460),
            delayMillis = if (reduced) 0 else 70 * index,
            easing = FastOutSlowInEasing,
        ),
        label = "aboutReveal",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 18.dp.toPx()
            },
    ) { content() }
}

@Composable
private fun rememberLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    return remember(uriHandler, context) {
        { url: String ->
            if (runCatching { uriHandler.openUri(url) }.isFailure) {
                Toast.makeText(context, R.string.ux_open_link_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
