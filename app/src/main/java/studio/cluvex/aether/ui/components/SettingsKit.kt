package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.AetherRadius
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/*
 * The shared building blocks for every surface in the app.
 *
 * WHAT CHANGED AND WHY
 *
 * The previous kit was intentionally container-less: hairlines, whitespace and
 * type, on the argument that a settings screen is a long document and documents
 * want typography, not forty boxes. That argument holds for ONE long list. It
 * broke everywhere else:
 *
 *   - Nothing was grouped. On the home screen, "latency" and "session uptime"
 *     were separated by exactly the same 1px rule that separated them from the
 *     traffic meter, so the eye had no way to tell which facts belonged
 *     together.
 *   - Rules cannot carry state. A locked section, a failed check and a normal
 *     row all looked the same until you read the text.
 *   - It was structurally dark-only. A 1px light rule at 55% alpha is invisible
 *     on white, so the whole visual structure of the app disappeared the moment
 *     a light theme existed.
 *
 * So the primitives now come in two families: CONTAINERS ([AetherCard],
 * [StatTile], [NavRow]) that group and can carry tone, and CONTENT ([Hairline],
 * [FieldLabel], [Hint], [SwitchRow], [ValueRow]) that lives inside them. The old
 * names and signatures are all preserved, so every panel kept compiling while
 * it was restyled.
 */

/**
 * The default container. Everything that belongs together goes in one of these.
 *
 * The shadow is applied only in light mode on purpose: a drop shadow on a
 * near-black surface is invisible work for the GPU, and on paper it is the only
 * thing that separates a white card from a white-ish page.
 */
@Composable
fun AetherCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    tone: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (accents.dark) Modifier else Modifier.shadow(5.dp, shape, clip = false))
            .clip(shape)
            .background(tone?.copy(alpha = if (accents.dark) 0.10f else 0.07f) ?: accents.card)
            .border(1.dp, tone?.copy(alpha = 0.30f) ?: accents.cardBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** A round icon chip. The one way an icon is ever tinted in this app. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    contentDescription: String? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Header inside an [AetherCard]: badge, title, optional subtitle, optional trailing. */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, tint = tint)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A group label that sits ABOVE cards, in the page margin. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AetherMetaLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/**
 * The title block of a full-page destination, with a back affordance.
 *
 * The chevron/arrow is auto-mirrored: in the fa locale "back" points the other
 * way, and a hand-drawn left arrow there is actively misleading.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * One entry in a hub list: badge, title, one-line explanation, chevron.
 *
 * This is what replaced the Settings mega-scroll. The chevron is mirrored by
 * hand rather than using an auto-mirrored asset, because ChevronRight has no
 * auto-mirrored twin and a right-pointing chevron in an RTL layout reads as
 * "go back".
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, tint = tint, size = 36.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
                    .copy(alpha = if (enabled) 1f else 0.45f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.45f),
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = if (rtl) -1f else 1f },
        )
    }
}

/**
 * One instrument reading: caption, value, optional footnote.
 *
 * Tappable when [onClick] is given — which is how the latency tile replaced the
 * separate "Test" button that used to sit beside it. One target, one meaning.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = true,
    footnote: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Field)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (accents.dark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                // Technical values stay pinned LTR: that BiDi fix is load-bearing
                // in the Persian locale, where 104.28.197.15 otherwise renders
                // reordered.
                style = if (mono) {
                    AetherNumeral.copy(textDirection = TextDirection.Ltr)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (!footnote.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A 1px rule. Still the right tool for separating rows INSIDE one card. */
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)),
    )
}

/** Section heading inside a long form: a small accent label plus a rule. */
@Composable
fun SectionRule(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    topSpace: Int = 30,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpace.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = AetherMetaLabel, color = accent)
        Spacer(Modifier.width(12.dp))
        Hairline(modifier = Modifier.weight(1f), alpha = 0.7f)
    }
}

/** Label above a control. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/** Explanatory copy under a control. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = modifier.padding(top = 8.dp),
    )
}

/**
 * A toggle row. The whole row is the hit target, not just the 40dp switch —
 * missing a switch on a moving bus was the single most annoying thing about the
 * old settings list.
 */
@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.45f),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * A fixed technical value with a one-tap copy affordance. Always LTR: `ip:port`
 * must not be reordered by the BiDi algorithm in the Persian locale.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val accents = LocalAetherAccents.current
    val shape = RoundedCornerShape(AetherRadius.Field)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(
                if (accents.dark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.share_copy),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Secondary action. [filled] promotes it to the loudest thing in its section. */
@Composable
fun ActionPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(AetherRadius.Field),
        color = if (filled) tint else Color.Transparent,
        contentColor = (if (filled) contentColorForTone(tint) else tint)
            .copy(alpha = if (enabled) 1f else 0.4f),
        border = if (filled) {
            null
        } else {
            BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.42f else 0.18f))
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The readable colour on top of a solid state fill.
 *
 * The state hues differ between light and dark (a mint that is legible on ink
 * is illegible on paper), and so does what sits on top of them, so this must
 * come from the palette rather than being a hardcoded near-black.
 */
@Composable
fun contentColorForTone(tone: Color): Color {
    val accents = LocalAetherAccents.current
    return when (tone) {
        accents.protected -> accents.onProtected
        accents.working -> accents.onWorking
        accents.failed -> accents.onFailed
        accents.brand -> accents.onBrand
        else -> if (accents.dark) MaterialTheme.colorScheme.surface else Color.White
    }
}

/**
 * An inline explanation of why the UI is in the state it is in. Uses a tinted
 * fill and a matching hairline rather than a coloured edge stripe.
 */
@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = LocalAetherAccents.current.working,
    icon: ImageVector = Icons.Rounded.Lock,
) {
    val shape = RoundedCornerShape(AetherRadius.Field)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = 0.11f))
            .border(1.dp, tone.copy(alpha = 0.24f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
