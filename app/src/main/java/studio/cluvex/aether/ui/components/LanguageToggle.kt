package studio.cluvex.aether.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Real 48dp targets, visible press/focus feedback, and a single radio group. */
@Composable
fun LanguageToggle(accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = AppLocale.effective(context)
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(AppLanguage.ENGLISH, AppLanguage.PERSIAN).forEach { language ->
            val selected = current == language
            val description = stringResource(if (language == AppLanguage.ENGLISH) R.string.language_pick_en else R.string.language_pick_fa)
            Box(Modifier.clip(MaterialTheme.shapes.small)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .selectable(selected = selected, role = Role.RadioButton,
                    onClick = { switchLanguage(context, language) })
                .semantics { contentDescription = description }
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(if (language == AppLanguage.ENGLISH) "EN" else "فا",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun switchLanguage(context: Context, language: AppLanguage) {
    if (AppLocale.stored(context) == language) return
    AppLocale.apply(context, language)
    if (!AppLocale.systemHandlesSwitch) context.findActivity()?.recreate()
}
