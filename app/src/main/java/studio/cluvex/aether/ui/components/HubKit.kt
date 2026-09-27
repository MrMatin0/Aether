@file:OptIn(ExperimentalLayoutApi::class)

package studio.cluvex.aether.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.*

/*
 * Settings hub kit. Tonal surfaces + 1dp outlineVariant hairlines instead of
 * blur: RenderEffect blur is API 31+ only, costs a full offscreen pass per
 * frame while scrolling, and a frosted panel over a flat background is just a
 * grey panel. Tone does the job glass was supposed to do.
 */

internal object HubShapes {
    val Card = RoundedCornerShape(24.dp)
    val ListRow = RoundedCornerShape(16.dp)
}

/** Every hub animation goes through here: exponential ease-out, zero under reduced motion. */
@Composable
@ReadOnlyComposable
internal fun <T> hubTween(millis: Int = AetherDur.Quick, easing: Easing = AetherEaseOut): TweenSpec<T> =
    tween(aetherDuration(millis), easing = easing)

// -------------------------------------------------------------------- lock --

/**
 * The tunnel lock, explained once. Rows only carry a 14dp glyph, so a
 * connected user sees one badge instead of a banner on every screen.
 */
@Composable
fun LockBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    if (compact) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = stringResource(R.string.hub_locked_row),
            modifier = modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        return
    }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.hub_lock_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ search --

@Composable
fun HubSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        hubTween(),
        label = "search-ring",
    )
    val a11yLabel = stringResource(R.string.hub_search_label)
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = { onQueryChange(it.take(48)) },
        singleLine = true,
        interactionSource = interaction,
        placeholder = {
            Text(stringResource(R.string.hub_search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(hubTween()), exit = fadeOut(hubTween(AetherDur.Snap))) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.hub_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ring, CircleShape)
            .semantics { contentDescription = a11yLabel },
    )
}

/**
 * Highlights match ranges. For Arabic-script text the range is widened to the
 * whole word first: styling half of a joined Persian word splits the text run
 * and can break letter joining on some Android versions.
 */
@Composable
fun HighlightedText(
    text: String,
    ranges: List<IntRange>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val hl = MaterialTheme.colorScheme.primaryContainer
    val onHl = MaterialTheme.colorScheme.onPrimaryContainer
    val annotated = remember(text, ranges, hl, onHl) {
        buildAnnotatedString {
            append(text)
            ranges.forEach { raw ->
                val r = widenForJoiningScripts(text, raw)
                val start = r.first.coerceIn(0, text.length)
                val end = (r.last + 1).coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(background = hl, color = onHl, fontWeight = FontWeight.SemiBold), start, end)
            }
        }
    }
    Text(annotated, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun isArabicScript(c: Char): Boolean =
    c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

internal fun widenForJoiningScripts(text: String, range: IntRange): IntRange {
    if (range.none { it in text.indices && isArabicScript(text[it]) }) return range
    var s = range.first.coerceIn(0, text.lastIndex.coerceAtLeast(0))
    var e = range.last.coerceIn(s, text.lastIndex.coerceAtLeast(0))
    // ZWNJ is not a letter, so widening stops exactly where joining already breaks.
    while (s > 0 && text[s - 1].isLetter()) s--
    while (e < text.lastIndex && text[e + 1].isLetter()) e++
    return s..e
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    val a = LocalAetherAccents.current
    Surface(modifier, shape = CircleShape, color = a.brandWash, contentColor = a.brand) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------- sections --

@Composable
fun HubSectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/**
 * A collapsible group. Collapsed, the header carries the group's live values,
 * so most users read their config without opening anything. Rows inside are
 * plain rows on the same surface: never a card inside a card.
 */
@Composable
fun SectionCard(
    title: String,
    summary: String?,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val a = LocalAetherAccents.current
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, hubTween(AetherDur.Base, AetherEaseOutExpo), label = "chevron")
    val container by animateColorAsState(
        if (expanded) scheme.surfaceContainer else scheme.surfaceContainerLow,
        hubTween(AetherDur.Base),
        label = "section-bg",
    )
    val toggleLabel = stringResource(if (expanded) R.string.hub_collapse else R.string.hub_expand)
    Surface(
        modifier.fillMaxWidth(),
        shape = HubShapes.Card,
        color = container,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                    .semantics { heading() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(a.brandWash, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), a.brand)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    AnimatedVisibility(
                        visible = !expanded && summary != null,
                        enter = fadeIn(hubTween()) + expandVertically(hubTween(AetherDur.Base, AetherEaseOutExpo)),
                        exit = fadeOut(hubTween(AetherDur.Snap)) + shrinkVertically(hubTween()),
                    ) {
                        Text(
                            summary.orEmpty(),
                            style = AetherMetaLabel,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.ExpandMore,
                    null,
                    Modifier.graphicsLayer { rotationZ = rotation },
                    scheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(hubTween(AetherDur.Base, AetherEaseOutExpo), expandFrom = Alignment.Top) +
                    fadeIn(hubTween(AetherDur.Base)),
                exit = shrinkVertically(hubTween(AetherDur.Quick)) + fadeOut(hubTween(AetherDur.Snap)),
            ) {
                Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = scheme.outlineVariant.copy(alpha = 0.6f))
                    content()
                }
            }
        }
    }
}

/**
 * One destination. The second line is the LIVE value when there is one, so
 * the page only needs opening to change something, not to find out what it
 * says. No chevron: the whole row is the target.
 */
@Composable
fun HubRow(
    icon: ImageVector,
    title: String,
    value: String?,
    note: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    destructive: Boolean = false,
    eyebrow: String? = null,
    highlights: List<IntRange> = emptyList(),
    tags: List<String> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (destructive) scheme.error else scheme.onSurface
    Row(
        modifier.fillMaxWidth()
            .clip(HubShapes.ListRow)
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), if (destructive) scheme.error else scheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
            }
            HighlightedText(title, highlights, MaterialTheme.typography.titleMedium, ink, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            if (value != null) {
                Text(value, style = AetherMetaLabel, color = scheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { TagChip(it) }
                }
            }
        }
        if (locked) {
            Spacer(Modifier.width(12.dp))
            LockBadge(Modifier.padding(top = 4.dp), compact = true)
        }
    }
}
