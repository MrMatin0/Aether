package studio.cluvex.aether.ui.engine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import studio.cluvex.aether.ui.components.AetherCard
import studio.cluvex.aether.ui.components.CardHeader
import studio.cluvex.aether.ui.components.Hairline
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.theme.LocalAetherAccents

/*
 * The shared vocabulary of the engine settings page.
 *
 * WHY THIS FILE EXISTS: AdvancedPanel was one 730-line composable holding ten
 * cards and about forty controls. It compiled and it worked, but:
 *
 *   - Every card repeated the same four lines of AetherCard + CardHeader +
 *     Spacer boilerplate, and each one had picked its own gap value by hand, so
 *     the vertical rhythm drifted between sections.
 *   - `AnimatedVisibility(visible = x) { Column { ... } }` appeared eight times
 *     verbatim, and three MORE dependent blocks used a bare `if` instead, so
 *     some conditional controls animated in and others popped.
 *   - The two "seconds" fields carried a copy-pasted parser that disagreed with
 *     the bounds the engine actually enforces (see [SecondsField]).
 *   - Every text field spelled out label/placeholder/supportingText slots by
 *     hand, which is how one of them ended up as the only technical input in
 *     the app with no help text.
 *
 * So the repeated shapes live here once, the individual sections live one per
 * file, and AdvancedPanel is back to being a table of contents.
 */

/**
 * Applies one change to the CURRENT profile.
 *
 * Sections receive this instead of `profile` + `onProfileChange`, together with
 * only the individual fields they render. That is not just tidiness: a
 * keystroke in one text field allocates a whole new [ConnectionProfile], and
 * when every card took the whole profile, every card recomposed. Narrow values
 * plus one stable callback means only the card that owns the field redraws.
 */
internal typealias ProfileEdit = (ConnectionProfile.() -> ConnectionProfile) -> Unit

/**
 * The vertical rhythm of the page, in one place.
 *
 * These were literals scattered across the old file (16, 18, 12, 14, 6 dp), so
 * "the space above a field label" was a different number in three cards.
 */
internal object EngineSpacing {
    /** Between two cards. */
    val Card: Dp = 16.dp

    /** Between a card header and the first field-style control under it. */
    val Header: Dp = 16.dp

    /** Between a card header and a stack of switch rows (they carry their own padding). */
    val SwitchHeader: Dp = 6.dp

    /** Between two unrelated controls in the same card. */
    val Field: Dp = 18.dp

    /** Between a control and the extra input it just revealed. */
    val Inline: Dp = 12.dp

    /** Before a hairline that follows field-style content. */
    val Divider: Dp = 14.dp
}

/**
 * The bounds the engine actually honours for the two tuning windows.
 *
 * ConnectionProfile clamps both on the way out (`coerceIn(1, 3600)` and
 * `coerceIn(1, 600)` in toArgs/toEnv), but the input fields did not, so a user
 * could type 9999 into either one and the number on screen was not the number
 * the tunnel used. Mirrored here so the field and the engine agree; the right
 * long-term home is the profile's own companion.
 */
internal object EngineLimits {
    const val ValidateSecs = 3600
    const val ReconnectSecs = 600
}

/** A titled card. The unit a screenshot gets cropped to when someone asks for help. */
@Composable
internal fun EngineSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalAetherAccents.current.brand,
    tone: Color? = null,
    headerGap: Dp = EngineSpacing.Header,
    content: @Composable ColumnScope.() -> Unit,
) {
    AetherCard(modifier = modifier, tone = tone) {
        CardHeader(title = title, subtitle = subtitle, icon = icon, tint = tint)
        Spacer(Modifier.height(headerGap))
        content()
    }
}

/** The rule between two switch rows inside one card. */
@Composable
internal fun EngineDivider() {
    Hairline(alpha = 0.5f)
}

/**
 * Controls that only exist while [visible].
 *
 * Always animated, never popped: the old file animated the fragment and split
 * blocks but revealed the manual endpoint, manual range and Zero Trust fields
 * with a bare `if`, so choosing "Manual peer" made the layout jump while
 * choosing "Split tunnel" slid open.
 */
@Composable
internal fun DependentBlock(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible = visible) {
        Column(content = content)
    }
}

/**
 * A technical text input.
 *
 * Always an [LtrOutlinedTextField] and never a bare OutlinedTextField, so the
 * BiDi / async-echo fix in LtrInput.kt cannot be bypassed by a new field
 * added later.
 */
@Composable
internal fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helpText: String? = null,
    singleLine: Boolean = true,
    masked: Boolean = false,
) {
    LtrOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else {
            null
        },
        supportingText = if (helpText != null) {
            { Text(helpText) }
        } else {
            null
        },
        visualTransformation = if (masked) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A whole-seconds field whose accepted range is the range the ENGINE accepts.
 *
 * ROOT CAUSE THIS FIXES: both call sites used to run the same hand-inlined
 * parser, `it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0`, and it was
 * wrong in three ways.
 *
 *  1. It let you type 9999 into fields the profile clamps to 3600
 *     (validate-secs) and 600 (reconnect-secs) on its way to the engine, so the
 *     number on screen was NOT the number the tunnel used, with nothing saying
 *     so. Same literal `take(4)` on both fields, even though one of them tops
 *     out at three digits.
 *  2. `take(4)` truncates from the FRONT, so a fifth digit silently rewrote the
 *     value instead of being refused: 1200 + "5" became 1200, not 12005.
 *  3. It offered the default Ascii keyboard for a digits-only field.
 *
 * Now [max] comes from the caller, is the same bound the profile enforces, the
 * length limit follows from it, out-of-range input is coerced instead of
 * chopped, and the keyboard is numeric. 0 still means "engine default" and
 * still renders as an empty field.
 */
@Composable
internal fun SecondsField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    placeholder: String,
    max: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxDigits = max.toString().length
    LtrOutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(maxDigits)
            onValueChange((digits.toIntOrNull() ?: 0).coerceIn(0, max))
        },
        enabled = enabled,
        singleLine = true,
        keyboardType = KeyboardType.Number,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
    )
}
