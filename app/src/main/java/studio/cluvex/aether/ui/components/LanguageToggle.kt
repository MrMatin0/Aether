package studio.cluvex.aether.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppLanguage
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.findActivity
import studio.cluvex.aether.ui.theme.AetherDur
import studio.cluvex.aether.ui.theme.AetherEaseOut

/**
 * The EN / fa pill in the header.
 *
 * It sits on the home screen on purpose. A language switch buried three
 * scrolls into Settings is useless to the exact person who needs it: someone
 * who cannot read the label on the section that contains it.
 */
@Composable
fun LanguageToggle(accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Read straight from the source of truth: switching recreates the activity
    // (or the platform does it for us), so this recomposes with the new value.
    val current = AppLocale.effective(context)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageChip(
            text = "EN",
            description = stringResource(R.string.language_pick_en),
            selected = current == AppLanguage.ENGLISH,
            accent = accent,
            onClick = { switchLanguage(context, AppLanguage.ENGLISH) },
        )
        LanguageChip(
            text = "\u0641\u0627",
            description = stringResource(R.string.language_pick_fa),
            selected = current == AppLanguage.PERSIAN,
            accent = accent,
            onClick = { switchLanguage(context, AppLanguage.PERSIAN) },
        )
    }
}

/**
 * One chip.
 *
 * The tap target is 44x40dp rather than the text's own ~30x26dp. Two characters
 * make a beautiful chip and a miserable button, and this particular button is
 * the one someone reaches for precisely because they cannot read the rest of
 * the screen — missing it twice is the worst possible first experience.
 *
 * selectable() with Role.RadioButton also fixes the announcement: the two chips
 * are one either/or choice, and the accent tint that says which one is live is
 * invisible to a screen reader.
 */
@Composable
private fun LanguageChip(
    text: String,
    description: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
        label = "langbg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(AetherDur.Quick, easing = AetherEaseOut),
        label = "langfg",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = 44.dp, minHeight = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * Persists the choice and repaints. Shared by the header pill and the Settings
 * selector so there is exactly one switching path.
 */
internal fun switchLanguage(context: Context, language: AppLanguage) {
    if (AppLocale.stored(context) == language) return
    AppLocale.apply(context, language)
    // API 33+: the platform recreates the app itself. Below that, we do.
    if (!AppLocale.systemHandlesSwitch) context.findActivity()?.recreate()
}
