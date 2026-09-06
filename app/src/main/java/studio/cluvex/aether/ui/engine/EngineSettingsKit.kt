package studio.cluvex.aether.ui.engine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

internal typealias ProfileEdit = (ConnectionProfile.() -> ConnectionProfile) -> Unit

internal object EngineSpacing {
    val Card: Dp = 16.dp
    val Header: Dp = 16.dp
    val SwitchHeader: Dp = 6.dp
    val Field: Dp = 18.dp
    val Inline: Dp = 12.dp
    val Divider: Dp = 14.dp
}

internal object EngineLimits {
    const val ValidateSecs = 3600
    const val ReconnectSecs = 600
}

@Composable
internal fun EngineSection(
    title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier,
    tint: Color = LocalAetherAccents.current.brand, tone: Color? = null,
    headerGap: Dp = EngineSpacing.Header, content: @Composable ColumnScope.() -> Unit,
) {
    AetherCard(modifier = modifier, tone = tone) {
        CardHeader(title = title, subtitle = subtitle, icon = icon, tint = tint)
        Spacer(Modifier.height(headerGap))
        content()
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

@Composable
internal fun ProfileTextField(
    value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean,
    modifier: Modifier = Modifier, placeholder: String? = null, helpText: String? = null,
    singleLine: Boolean = true, masked: Boolean = false,
) {
    LtrOutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
        label = { Text(label) },
        placeholder = if (placeholder == null) null else { { Text(placeholder) } },
        supportingText = if (helpText == null) null else { { Text(helpText) } },
        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Ascii,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
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
