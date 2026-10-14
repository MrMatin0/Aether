package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.*

private val InsidePanel = staticCompositionLocalOf { false }
@Composable
private fun toneWash(tone: Color): Color {
    val a = LocalAetherAccents.current
    return when (tone) { a.protected -> a.protectedWash; a.working -> a.workingWash; a.failed -> a.failedWash; a.brand -> a.brandWash; else -> MaterialTheme.colorScheme.surfaceVariant }
}

/**
 * Legacy callers share the same boundary; nested panels flatten automatically.
 * 16dp by default: the settings pages are flat grouped cards now, not posters.
 */
@Composable
fun AetherCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, tone: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    AetherCard(modifier = modifier, contentPadding = PaddingValues(padding), tone = tone, content = content)
}

/** Same card with asymmetric padding, e.g. a dense list of switch rows. */
@Composable
fun AetherCard(modifier: Modifier = Modifier, contentPadding: PaddingValues, tone: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    if (InsidePanel.current) Column(modifier.fillMaxWidth().padding(vertical = 8.dp), content = content)
    else Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = tone?.let { toneWash(it) } ?: LocalAetherAccents.current.card,
        border = BorderStroke(1.dp, LocalAetherAccents.current.cardBorder)) {
        CompositionLocalProvider(InsidePanel provides true) { Column(Modifier.padding(contentPadding), content = content) }
    }
}
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp, contentDescription: String? = null) {
    Box(modifier.size(size).clip(MaterialTheme.shapes.medium).background(lerp(MaterialTheme.colorScheme.surface, tint, 0.08f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (icon != null) { Icon(icon, null, Modifier.padding(top = 2.dp).size(24.dp), tint); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AetherMetaLabel, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 16.dp).semantics { heading() })
}
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }; Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
@Composable
fun NavRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IconBadge(icon, if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (!subtitle.isNullOrBlank()) { Spacer(Modifier.height(2.dp)); Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface, mono: Boolean = true, footnote: String? = null,
    onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Column(modifier.clip(MaterialTheme.shapes.small).heightIn(min = 48.dp)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint); Spacer(Modifier.width(8.dp)) }
            Text(label, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), style = if (mono) AetherNumeral.copy(textDirection = TextDirection.Ltr) else MaterialTheme.typography.titleMedium)
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
        if (!footnote.isNullOrBlank()) Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 1f) { HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)) }
@Composable
fun SectionRule(label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, topSpace: Int = 32) {
    Text(label, style = MaterialTheme.typography.titleLarge, color = accent,
        modifier = modifier.fillMaxWidth().padding(top = topSpace.dp, bottom = 20.dp).semantics { heading() })
}

/**
 * The label above a picker or field. With [info], a (?) button sits next to it
 * and opens the explanation on demand instead of a permanent paragraph below.
 */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, info: String? = null) {
    if (info.isNullOrBlank()) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = modifier.padding(bottom = 8.dp))
        return
    }
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(2.dp))
        InfoTip(info)
    }
}

/** Kept for inline validation (accepted counts, errors). Explanations go in [InfoTip]. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 8.dp)) }

/**
 * A small (?) that shows [text] in a popover on tap. Deliberately a
 * DropdownMenu and not a TooltipBox: stable API, dismisses on outside tap and
 * back, and works the same in RTL. 28dp so it never inflates a 52dp row.
 */
@Composable
fun InfoTip(text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_info)
    Box(modifier) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = label) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, label, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * One toggle. 52dp minimum, 10dp vertical padding. [description] is optional
 * and capped at two short lines; anything longer belongs in [info], which
 * shows a (?) next to the title.
 */
@Composable
fun SwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    info: String? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MaterialTheme.shapes.small)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (!info.isNullOrBlank()) { Spacer(Modifier.width(2.dp)); InfoTip(info) }
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.share_copy)
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = 52.dp)
        .clickable(onClickLabel = copyLabel, role = Role.Button) {
            clipboard.setText(AnnotatedString(value)); Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
        }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp)); Text(value, style = AetherNumeral.copy(textDirection = TextDirection.Ltr))
        }
        Spacer(Modifier.width(12.dp)); Icon(Icons.Rounded.ContentCopy, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
    }
}
@Composable
fun ActionPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, filled: Boolean = false, tint: Color = MaterialTheme.colorScheme.primary) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
        color = if (filled) tint else Color.Transparent,
        contentColor = (if (filled) contentColorForTone(tint) else tint).copy(alpha = if (enabled) 1f else 0.45f),
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
}
@Composable
fun contentColorForTone(tone: Color): Color {
    val a = LocalAetherAccents.current
    return when (tone) { a.protected -> a.onProtected; a.working -> a.onWorking; a.failed -> a.onFailed; a.brand -> a.onBrand; else -> if (tone.luminanceForContent() > 0.4f) PaperHigh else Paper10 }
}
private fun Color.luminanceForContent(): Float {
    fun linear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

/** Reserved for things that are actually wrong or missing; not for explanations. */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier, tone: Color = LocalAetherAccents.current.working, icon: ImageVector = Icons.Rounded.Lock) {
    Surface(modifier.fillMaxWidth(), color = toneWash(tone), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), tone)
            Spacer(Modifier.width(10.dp)); Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
