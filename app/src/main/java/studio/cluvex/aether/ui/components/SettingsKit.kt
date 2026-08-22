package studio.cluvex.aether.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.AetherNumeral

/*
 * The shared building blocks for every settings-like surface.
 *
 * Before this file, each panel (advanced, share, about) rolled its own layout:
 * different card alpha, different divider colour, different label style,
 * different vertical rhythm. Stacked in one scroll view they read as four
 * unrelated screens. These primitives are deliberately flat — rules and
 * spacing instead of nested cards — because the settings surface is a long
 * document, and a document wants typography and whitespace, not 40 boxes.
 *
 * 1.4.3 AUDIT NOTE — why fixes land HERE and not in the panels: every settings
 * panel in the app (advanced, presets, automation, history, share, about,
 * diagnostics) is built out of these five primitives. A touch target that is
 * 3dp too small, or a row that announces itself twice to TalkBack, is
 * therefore not one bug in one panel — it is the same bug repeated on every
 * screen. Fixing the primitive fixes all of them at once, and keeps the diff
 * reviewable.
 */

/** A 1px rule. The main structural device in place of card borders. */
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)),
    )
}

/** Section heading: a small accent label followed by a rule to the margin. */
@Composable
fun SectionRule(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    topSpace: Int = 34,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpace.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AetherMetaLabel,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = modifier.padding(top = 8.dp),
    )
}

/**
 * A toggle row. The whole row is the hit target, not just the 40dp switch —
 * missing a switch on a moving bus was the single most annoying thing about the
 * old settings list.
 *
 * 1.4.3 ACCESSIBILITY FIX: the row used to be `clickable` while the [Switch]
 * kept its own `onCheckedChange`, so the same setting produced TWO semantics
 * nodes. TalkBack announced an unlabelled button and then a separate switch
 * with no title, and swipe navigation stopped on both. `toggleable` with
 * [Role.Switch] merges the title, the description and the on/off state into
 * one node ("Block ads, switch, on, double tap to toggle") and the Switch is
 * handed a null callback so it renders as pure state instead of a second
 * control. It is also why the row can no longer report "on" while the switch
 * draws "off": there is exactly one source of truth for the gesture now.
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
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 14.dp),
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
            // Null on purpose: the ROW owns the gesture and the semantics.
            onCheckedChange = null,
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
 *
 * 1.4.3: the copy affordance was a bare `clickable` plus a decorative icon, so
 * a screen reader was told the row was tappable but never what tapping DID.
 * The action now carries the same "Copy" label the icon uses, and the icon
 * itself drops to decorative so the label is not read out twice.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.share_copy)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = copyLabel, role = Role.Button) {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
            }
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = AetherMetaLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            // Decorative: the click label above already says "Copy".
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Secondary action. [filled] promotes it to the loudest thing in its section.
 *
 * 1.4.3 TOUCH TARGET FIX: 13dp of vertical padding around a 19sp line measured
 * out at ~45dp tall, i.e. under the 48dp minimum every accessibility guideline
 * (and Compose's own `minimumInteractiveComponentSize`) asks for. These pills
 * are the run/copy/clear controls in Diagnostics and the primary button in
 * onboarding, so they were the most-tapped and least-hittable things in the
 * app. `defaultMinSize` raises the floor without stretching a pill that is
 * already taller.
 *
 * The label is also clipped with an ellipsis now: in the Persian locale several
 * of these labels are noticeably longer than their English source, and a
 * `maxLines = 1` Text with no overflow strategy hard-cuts mid-glyph.
 */
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
    val content = if (filled) MaterialTheme.colorScheme.onPrimary else tint
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (filled) tint else Color.Transparent,
        contentColor = content.copy(alpha = if (enabled) 1f else 0.4f),
        border = if (filled) null else BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.45f else 0.2f)),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 13.dp),
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
            )
        }
    }
}

/**
 * An inline explanation of why the UI is in the state it is in. Uses a tinted
 * background rather than a coloured edge stripe.
 *
 * 1.4.3: marked as a polite live region. This bar appears when the engine
 * settings lock themselves mid-session, i.e. it explains why the control the
 * user just reached for stopped responding. Silently inserting that into the
 * tree told a screen-reader user nothing at all; a live region announces it at
 * the moment it appears, without stealing focus.
 */
@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.secondary,
    icon: ImageVector = Icons.Rounded.Lock,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
