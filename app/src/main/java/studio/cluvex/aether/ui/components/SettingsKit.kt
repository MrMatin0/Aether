package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.*

private val InsidePanel = staticCompositionLocalOf { false }

/** Semantic wash, precomposited: no unpredictable translucent surface stacks. */
@Composable
private fun toneWash(tone: Color): Color {
    val a = LocalAetherAccents.current
    return when (tone) {
        a.protected -> a.protectedWash
        a.working -> a.workingWash
        a.failed -> a.failedWash
        a.brand -> a.brandWash
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/** One panel boundary. A nested caller becomes plain content, not a card in a card. */
@Composable
fun AetherCard(
    modifier: Modifier = Modifier, padding: Dp = 20.dp, tone: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (InsidePanel.current) {
        Column(modifier.fillMaxWidth().padding(vertical = 12.dp), content = content)
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = tone?.let { toneWash(it) } ?: LocalAetherAccents.current.card,
            border = BorderStroke(1.dp, LocalAetherAccents.current.cardBorder),
        ) {
            CompositionLocalProvider(InsidePanel provides true) {
                Column(Modifier.padding(padding), content = content)
            }
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp, contentDescription: String? = null) {
    Box(modifier.size(size).clip(MaterialTheme.shapes.small)
        .background(lerp(MaterialTheme.colorScheme.surface, tint, 0.09f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun CardHeader(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(22.dp), tint); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 12.dp).semantics { heading() })
}

@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NavRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.small)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 18.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(24.dp), tint); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatTile(
    label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface, mono: Boolean = true, footnote: String? = null,
    onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.clip(MaterialTheme.shapes.small).heightIn(min = 48.dp)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint); Spacer(Modifier.width(8.dp)) }
            Text(label, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f),
                style = if (mono) AetherNumeral.copy(textDirection = TextDirection.Ltr) else MaterialTheme.typography.titleMedium)
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
        if (!footnote.isNullOrBlank()) Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 1f) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
}

@Composable
fun SectionRule(label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, topSpace: Int = 32) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = accent,
        modifier = modifier.fillMaxWidth().padding(top = topSpace.dp, bottom = 16.dp).semantics { heading() })
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp))
}

/** A single labelled switch node, with the whole row as its touch target. */
@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f))
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.share_copy)
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = 56.dp)
        .clickable(onClickLabel = copyLabel, role = Role.Button) {
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = AetherMetaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(value, style = AetherNumeral.copy(textDirection = TextDirection.Ltr))
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ActionPill(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, filled: Boolean = false, tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (filled) tint else Color.Transparent,
        contentColor = (if (filled) contentColorForTone(tint) else tint).copy(alpha = if (enabled) 1f else 0.45f),
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
fun contentColorForTone(tone: Color): Color {
    val a = LocalAetherAccents.current
    return when (tone) {
        a.protected -> a.onProtected
        a.working -> a.onWorking
        a.failed -> a.onFailed
        a.brand -> a.onBrand
        else -> if (tone.luminanceForContent() > 0.4f) PaperHigh else Paper10
    }
}

// Linear-light luminance avoids assigning light text to a bright custom action.
private fun Color.luminanceForContent(): Float {
    fun linear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier, tone: Color = LocalAetherAccents.current.working, icon: ImageVector = Icons.Rounded.Lock) {
    Surface(modifier = modifier.fillMaxWidth(), color = toneWash(tone), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tone)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
