package studio.cluvex.aether.ui.engine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherMetaLabel
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

internal typealias ProfileEdit = (ConnectionProfile.() -> ConnectionProfile) -> Unit

internal object EngineSpacing {
    val Card: Dp = 16.dp
    val Header: Dp = 16.dp
    val SwitchHeader: Dp = 6.dp
    val Field: Dp = 12.dp
    val Inline: Dp = 8.dp
    val Divider: Dp = 8.dp
    /** Between two cards on the same page. */
    val Group: Dp = 16.dp

    /** Default card padding: fields and pickers. */
    val CardPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    /** A card that is mostly switch rows, which carry their own 10dp. */
    val DensePadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    /** A card that is a list of selectable rows with their own padding. */
    val ListPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
}

internal object EngineLimits {
    const val ValidateSecs = 3600
    const val ReconnectSecs = 600
}

/**
 * A plain grouping container. It used to repeat the page title, an icon and a
 * long subtitle at the top of every card, right under a top bar that already
 * said the same thing. It renders none of that now; the parameters stay so
 * existing callers keep compiling unchanged.
 *
 * New code uses [EngineGroup], which can carry a short label above the card.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun EngineSection(
    title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier,
    tint: Color = LocalAetherAccents.current.brand, tone: Color? = null,
    headerGap: Dp = EngineSpacing.Header, content: @Composable ColumnScope.() -> Unit,
) {
    AetherCard(modifier = modifier, contentPadding = EngineSpacing.CardPadding, tone = tone, content = content)
}

/**
 * One flat card on a settings page, with an optional quiet [label] above it
 * (and an optional (?) [info] next to that label).
 */
@Composable
internal fun EngineGroup(
    modifier: Modifier = Modifier,
    label: String? = null,
    info: String? = null,
    tone: Color? = null,
    contentPadding: PaddingValues = EngineSpacing.CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) EngineGroupLabel(label, info)
        AetherCard(contentPadding = contentPadding, tone = tone, content = content)
    }
}

/** The subtle header above a card: small caps-ish meta label, never a title. */
@Composable
internal fun EngineGroupLabel(text: String, info: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = AetherMetaLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!info.isNullOrBlank()) InfoTip(info)
    }
}

/** Two inputs side by side, each half the row (MTU + keepalive, timer pairs...). */
@Composable
internal fun FieldPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EngineSpacing.Inline),
        verticalAlignment = Alignment.Top,
    ) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

@Composable
internal fun EngineDivider() { Hairline(alpha = 0.5f) }

@Composable
internal fun DependentBlock(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (LocalReducedMotion.current) {
        if (visible) Column(content = content)
    } else {
        AnimatedVisibility(visible = visible) { Column(content = content) }
    }
}

/**
 * A technical text input. Always LTR (IPs, ports, SNI, CIDRs). [info] puts a
 * (?) in the trailing slot instead of a permanent supporting paragraph; the
 * popover itself keeps the UI's own direction so Persian copy reads RTL.
 */
@Composable
internal fun ProfileTextField(
    value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean,
    modifier: Modifier = Modifier, placeholder: String? = null, helpText: String? = null,
    singleLine: Boolean = true, masked: Boolean = false, info: String? = null,
) {
    val direction = LocalLayoutDirection.current
    LtrOutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
        label = { Text(label) },
        placeholder = if (placeholder == null) null else { { Text(placeholder) } },
        supportingText = if (helpText == null) null else { { Text(helpText) } },
        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Ascii,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (info.isNullOrBlank()) null else {
            { CompositionLocalProvider(LocalLayoutDirection provides direction) { InfoTip(info.orEmpty()) } }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Saturating parse: a long paste must clamp, not overflow to zero or lose its tail. */
internal fun boundedSecondsText(raw: String, max: Int): String {
    require(max > 0)
    val digits = normalizeTechnicalText(raw).filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    var result = 0L
    for (digit in digits) {
        result = (result * 10 + (digit - '0')).coerceAtMost(max.toLong())
    }
    return if (result == 0L) "" else result.toString()
}

@Composable
internal fun SecondsField(
    value: Int, onValueChange: (Int) -> Unit, label: String, placeholder: String,
    max: Int, enabled: Boolean, modifier: Modifier = Modifier,
) {
    LtrOutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { onValueChange(it.toIntOrNull() ?: 0) },
        inputTransform = { boundedSecondsText(it, max) },
        enabled = enabled, singleLine = true, keyboardType = KeyboardType.Number,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
    )
}
