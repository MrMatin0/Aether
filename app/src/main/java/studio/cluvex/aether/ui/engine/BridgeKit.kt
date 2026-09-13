package studio.cluvex.aether.ui.engine

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.model.BridgeTransport
import studio.cluvex.aether.ui.bridgeTransportDescription
import studio.cluvex.aether.ui.bridgeTransportIcon
import studio.cluvex.aether.ui.bridgeTransportLabel
import studio.cluvex.aether.ui.components.ActionPill
import studio.cluvex.aether.ui.components.IconBadge
import studio.cluvex.aether.ui.theme.AetherConsoleLine
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherNumeral
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

/**
 * The parts the Tor bridges page is assembled from.
 *
 * ### Why these are not the generic settings widgets
 *
 * Everything in [studio.cluvex.aether.ui.components.SettingsKit] is built for a
 * page of VALUES: a row, a switch, a field, a notice. Bridges is a page of
 * WORK - choose a source, choose a type, ask a server on the internet, wait,
 * answer a captcha, keep some of the answers - and the generic widgets gave
 * every one of those steps the same weight. The result was five identical
 * headings, nine always-expanded option cards and four notice bars in a row,
 * which is a page nobody reads to the bottom, and the bottom was where the only
 * line that matters was printed.
 *
 * So the primitives here encode the two things that page needed and could not
 * express:
 *
 *  - **Rank.** [BridgeStatusStrip] and [BridgeStepHeader] make the summary and
 *    the sequence louder than the options, so the page can be skimmed by
 *    someone who already knows what they want.
 *  - **Progressive disclosure.** [BridgeChoiceRow] shows the full description
 *    only for the option that is selected, and [SelectableBridgeRow] keeps the
 *    400-byte bridge line behind one tap. Both of those facts still matter; they
 *    just do not all matter at once.
 */

/** Page-local spacing. Steps need more air between them than fields do. */
internal object BridgeSpacing {
    /** Between one numbered step and the next. */
    val Step: Dp = 28.dp

    /** Between two option rows in the same group. */
    val Gap: Dp = 4.dp

    /** Between two things on one line: chips, pills, tags. */
    val Chip: Dp = 8.dp
}

/**
 * One short fact on a tinted ground.
 *
 * Two per strip and never more: a third one wraps at large font scales, and a
 * status line that wraps is no longer a status line.
 */
internal data class BridgeTag(
    val text: String,
    val tone: Color,
    val icon: ImageVector? = null,
)

@Composable
internal fun BridgeTagLabel(tag: BridgeTag, modifier: Modifier = Modifier) {
    val icon = tag.icon
    Surface(
        modifier = modifier,
        color = tag.tone.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(12.dp), tag.tone)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = tag.text,
                style = AetherMetaLabel,
                color = tag.tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "Am I set up?", at the TOP of the page.
 *
 * This is the one thing the old page got backwards. The count of bridges tor
 * will be given was the LAST heading, eight screens down, so the two questions
 * a returning user has - is this on, and will it be used - could only be
 * answered by scrolling past every option. Now they are the first row, and the
 * tone carries the answer before the words do.
 */
@Composable
internal fun BridgeStatusStrip(
    title: String,
    note: String,
    tone: Color,
    icon: ImageVector,
    tags: List<BridgeTag>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = lerp(MaterialTheme.colorScheme.surface, tone, 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(icon = icon, tint = tone, size = 40.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(BridgeSpacing.Chip)) {
                    tags.forEach { BridgeTagLabel(it) }
                }
            }
        }
    }
}

/**
 * A numbered step.
 *
 * The page used to be five [studio.cluvex.aether.ui.components.SectionRule]
 * headings of identical size and colour, which told the reader that picking a
 * source, picking a type and reading a privacy note were three equal things.
 * They are not: they are one sequence, and a sequence with numbers can be
 * re-entered halfway without being re-read from the top.
 *
 * [done] turns the number into a tick, and is only passed by the last step - the
 * one that can actually be finished.
 */
@Composable
internal fun BridgeStepHeader(
    step: Int,
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    done: Boolean = false,
) {
    val accents = LocalAetherAccents.current
    val tone = if (done) accents.protected else accents.brand
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                color = tone.copy(alpha = 0.16f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tone)
                    } else {
                        Text(step.toString(), style = AetherMetaLabel, color = tone)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One option in a group, with its detail only when it is the chosen one.
 *
 * The four bridge sources each need two lines of "and this is how it fails" to
 * be pickable at all, and four of those stacked is most of a phone screen spent
 * on three options the user has already rejected. So an unselected row carries
 * a one-line [summary] and the selected row carries the full one. The tick space
 * is reserved either way, so choosing a row cannot reflow its label.
 */
@Composable
internal fun BridgeChoiceRow(
    title: String,
    summary: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = BridgeSpacing.Gap)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) accents.brand else accents.cardBorder),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                tint = if (selected) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), accents.brand)
                }
            }
        }
    }
}

/**
 * The five transports, two to a line.
 *
 * Full-width cards with a description each cost five screens to say what a grid
 * of chips says in one, and the description of a transport is only needed for
 * the one being considered - so it is printed once, under the grid, for the
 * selection.
 *
 * The two facts that decide the choice stay ON the chip, because they are the
 * reason the old page could not be a plain dropdown: how many built-in bridges
 * of that type are known, and - for a build that did not ship the binary - which
 * binary is missing. Unavailable chips are dimmed AND unselectable, which is the
 * rule this page exists to enforce: tor treats a bridge line with no matching
 * plugin as a fatal config error and exits during startup.
 */
@Composable
internal fun BridgeTransportPicker(
    selected: BridgeTransport,
    runnable: (BridgeTransport) -> Boolean,
    countOf: (BridgeTransport) -> Int,
    enabled: Boolean,
    onSelect: (BridgeTransport) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        BridgeTransport.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = BridgeSpacing.Chip),
                horizontalArrangement = Arrangement.spacedBy(BridgeSpacing.Chip),
            ) {
                pair.forEach { type ->
                    TransportChip(
                        type = type,
                        active = type == selected,
                        runnable = runnable(type),
                        count = countOf(type),
                        enabled = enabled,
                        onSelect = { onSelect(type) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // An odd count must not stretch the last chip to full width:
                // a chip twice the size of its neighbours reads as selected.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = bridgeTransportDescription(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransportChip(
    type: BridgeTransport,
    active: Boolean,
    runnable: Boolean,
    count: Int,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    val alpha = if (runnable) 1f else 0.45f
    val meta = when {
        !runnable -> stringResource(R.string.bridges_needs, type.plugin?.id ?: "")
        count > 0 -> stringResource(R.string.bridges_builtin_count, count)
        else -> null
    }
    Surface(
        modifier = modifier
            .heightIn(min = 64.dp)
            .selectable(
                selected = active,
                enabled = enabled && runnable,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (active) accents.brandWash else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) accents.brand else accents.cardBorder),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = bridgeTransportIcon(type),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = (if (active) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = alpha),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = bridgeTransportLabel(type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    Text(
                        text = meta,
                        style = AetherMetaLabel,
                        color = when {
                            !runnable -> accents.failed
                            active -> accents.brand
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One bridge: readable first, complete on demand.
 *
 * ### Why the full line is no longer printed on every row
 *
 * A snowflake line is around 400 bytes of ICE servers and a broker URL, and the
 * old row printed all of it under every entry, ellipsised at two lines. Ten
 * built-in bridges was therefore a wall of hex in which the ONE thing that tells
 * two bridges apart - the host and the fingerprint - was the part that got
 * clipped.
 *
 * So the row leads with `obfs4 host:port` and a shortened fingerprint, and the
 * verbatim line lives one tap away. That panel is also where Copy and Forget
 * went, which is what took three tap targets out of a 56dp row.
 */
@Composable
private fun BridgeRowShell(
    label: String,
    detail: String?,
    line: String,
    enabled: Boolean,
    onCopy: () -> Unit,
    onRemove: (() -> Unit)?,
    removeLabel: String,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    val accents = LocalAetherAccents.current
    var open by remember(line) { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (open) R.string.bridges_hide_line else R.string.bridges_show_line,
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = AetherNumeral.copy(textDirection = TextDirection.Ltr),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = AetherMetaLabel.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { open = !open }) {
                Icon(
                    imageVector = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = toggleLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DependentBlock(visible = open) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = line,
                    modifier = Modifier.padding(12.dp),
                    style = AetherConsoleLine.copy(textDirection = TextDirection.Ltr),
                )
            }
            Spacer(Modifier.height(BridgeSpacing.Chip))
            Row(horizontalArrangement = Arrangement.spacedBy(BridgeSpacing.Chip)) {
                ActionPill(
                    label = stringResource(R.string.bridges_copy_one),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.ContentCopy,
                )
                if (onRemove != null) {
                    ActionPill(
                        label = removeLabel,
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Delete,
                        enabled = enabled,
                        tint = accents.failed,
                    )
                }
            }
            Spacer(Modifier.height(BridgeSpacing.Chip))
        }
    }
}

/**
 * A bridge that can be picked.
 *
 * Per-line selection rather than "use this transport" is deliberate and stays:
 * bridges die one at a time, and "the first one works, the other two do not" is
 * a thing the user can only act on if they can turn one off.
 */
@Composable
internal fun SelectableBridgeRow(
    label: String,
    detail: String?,
    line: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "",
) {
    BridgeRowShell(
        label = label,
        detail = detail,
        line = line,
        enabled = enabled,
        onCopy = onCopy,
        onRemove = onRemove,
        removeLabel = removeLabel,
        modifier = modifier,
        rowModifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A bridge tor is actually going to be given.
 *
 * No checkbox: this list is the CONSEQUENCE of the picking above, and a second
 * set of checkboxes for the same lines was the old page asking the same question
 * twice. The leading glyph carries the only new fact here, which is whether this
 * build can run it.
 */
@Composable
internal fun ActiveBridgeRow(
    label: String,
    detail: String?,
    line: String,
    runnable: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    removeLabel: String,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAetherAccents.current
    BridgeRowShell(
        label = label,
        detail = detail,
        line = line,
        enabled = enabled,
        onCopy = onCopy,
        onRemove = onRemove,
        removeLabel = removeLabel,
        modifier = modifier,
        rowModifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Icon(
            imageVector = if (runnable) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (runnable) accents.protected else accents.failed,
        )
    }
}

/** Nothing here yet, said quietly and with the reason. */
@Composable
internal fun BridgeEmptyState(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A request is in flight.
 *
 * The old page said so by retitling the button to "Working...", which is the one
 * place it could not be read: the finger that just tapped it is on top of it.
 * And a bridge request on a censored network is not a spinner-length wait - it
 * is a TCP connect to an address the network is probably dropping, so the wait
 * is named rather than merely indicated.
 *
 * The bar itself is skipped under reduced motion; the words carry it.
 */
@Composable
internal fun BridgeBusyBar(visible: Boolean) {
    val accents = LocalAetherAccents.current
    DependentBlock(visible = visible) {
        Spacer(Modifier.height(EngineSpacing.Inline))
        Text(
            text = stringResource(R.string.bridges_working),
            style = AetherMetaLabel,
            color = accents.brand,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.bridges_busy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!LocalReducedMotion.current) {
            Spacer(Modifier.height(EngineSpacing.Inline))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small),
                color = accents.brand,
                trackColor = accents.brand.copy(alpha = 0.16f),
            )
        }
    }
}

/**
 * What the last request did, and a way to put it away.
 *
 * Dismissible because it is an EVENT, not a state: a notice bar that cannot be
 * closed becomes part of the page, and "3 bridges received" is then still there
 * an hour later next to a selection the user has since emptied.
 */
@Composable
internal fun BridgeOutcomeCard(
    text: String,
    tone: Color,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = lerp(MaterialTheme.colorScheme.surface, tone, 0.12f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tone)
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.bridges_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
