package studio.cluvex.aether.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/** Own the active edit locally so delayed persistence cannot move the cursor.
 * Input constraints run BEFORE the local value is displayed or published.
 * Labels keep the surrounding locale while technical values remain LTR.
 */
@Composable
fun LtrOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    inputTransform: (String) -> String = { it },
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val direction = LocalLayoutDirection.current
    val external = inputTransform(normalizeTechnicalText(value))
    var local by remember { mutableStateOf(TextFieldValue(external)) }
    // No snapshot writes during composition. Disabled fields also accept resets.
    SideEffect {
        if ((!focused || !enabled) && local.text != external) {
            local = TextFieldValue(external, TextRange(external.length))
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = local,
            onValueChange = { raw ->
                val normalized = normalizeTechnicalValue(raw)
                val accepted = inputTransform(normalized.text)
                val next = if (accepted == normalized.text) normalized else TextFieldValue(
                    accepted,
                    TextRange(normalized.selection.start.coerceIn(0, accepted.length),
                        normalized.selection.end.coerceIn(0, accepted.length)),
                )
                local = next
                if (next.text != value) onValueChange(next.text)
            },
            modifier = modifier,
            enabled = enabled,
            singleLine = singleLine,
            label = if (label == null) null else {
                { CompositionLocalProvider(LocalLayoutDirection provides direction) { label() } }
            },
            placeholder = placeholder,
            supportingText = if (supportingText == null) null else {
                { CompositionLocalProvider(LocalLayoutDirection provides direction) { supportingText() } }
            },
            interactionSource = interaction,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
        )
    }
}

private fun normalizeTechnicalChar(c: Char): Char = when (c) {
    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
    '\u066B' -> '.'
    '\u060C' -> ','
    else -> c
}

private fun isBidiControl(c: Char): Boolean =
    c == '\u200E' || c == '\u200F' || c == '\u061C' ||
        c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

internal fun normalizeTechnicalText(text: String): String = buildString(text.length) {
    for (c in text) if (!isBidiControl(c)) append(normalizeTechnicalChar(c))
}

/** Remap both selection endpoints AND the IME composing range after stripping. */
internal fun normalizeTechnicalValue(value: TextFieldValue): TextFieldValue {
    val text = normalizeTechnicalText(value.text)
    if (text == value.text) return value
    fun offset(index: Int): Int = value.text.take(index).count { !isBidiControl(it) }
    fun range(source: TextRange) = TextRange(offset(source.start), offset(source.end))
    val composition = value.composition?.let(::range)?.takeUnless { it.collapsed }
    return TextFieldValue(text, range(value.selection), composition)
}
