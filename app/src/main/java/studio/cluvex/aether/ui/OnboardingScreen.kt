package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.components.SignalDiagram
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

/** Scrollable at large font sizes; finishing onboarding never starts a tunnel. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val a = LocalAetherAccents.current
    val go: (Int) -> Unit = { target -> scope.launch {
        if (reduced) pager.scrollToPage(target.coerceIn(0, 2)) else pager.animateScrollToPage(target.coerceIn(0, 2))
    } }
    BackHandler(pager.currentPage > 0) { go(pager.currentPage - 1) }
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxHeight < 480.dp
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp)); LanguageToggle(accent = a.brand)
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth()) { page ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.onboarding_step, page + 1, 3), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(if (compact) 20.dp else 40.dp))
                        Text(stringResource(when (page) { 0 -> R.string.onboarding_title_1; 1 -> R.string.onboarding_title_2; else -> R.string.onboarding_title_3 }),
                            style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(when (page) { 0 -> R.string.onboarding_body_1; 1 -> R.string.onboarding_body_2; else -> R.string.ux_privacy_local }),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!compact) SignalDiagram(a.brand, verified = false, modifier = Modifier.padding(vertical = 24.dp))
                        if (page == 2) {
                            Spacer(Modifier.height(24.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(20.dp)); Text(stringResource(R.string.passage_privacy), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    if (!compact) Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
                        repeat(3) { index ->
                            val description = stringResource(R.string.onboarding_step, index + 1, 3)
                            val selected = pager.currentPage == index
                            Box(Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                                .selectable(selected, enabled = !pager.isScrollInProgress, role = Role.Tab, onClick = { go(index) })
                                .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                                HorizontalDivider(Modifier.width(if (selected) 32.dp else 16.dp), thickness = if (selected) 3.dp else 1.dp,
                                    color = if (selected) a.brand else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onFinished, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_skip)) }
                        Button(onClick = { if (pager.currentPage == 2) onFinished() else go(pager.currentPage + 1) }, enabled = !pager.isScrollInProgress,
                            modifier = Modifier.weight(2f).heightIn(min = 56.dp), shape = MaterialTheme.shapes.large) {
                            Text(stringResource(if (pager.currentPage == 2) R.string.onboarding_start else R.string.onboarding_next))
                        }
                    }
                }
            }
        }
    }
}
