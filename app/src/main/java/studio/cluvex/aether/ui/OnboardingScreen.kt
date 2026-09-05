package studio.cluvex.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.LanguageToggle
import studio.cluvex.aether.ui.theme.LocalAetherAccents
import studio.cluvex.aether.ui.theme.LocalReducedMotion

/** Scrollable teaching pages. Completion never requests permission or connects. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val accents = LocalAetherAccents.current
    val go: (Int) -> Unit = { target ->
        scope.launch {
            if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target)
        }
    }
    BackHandler(pager.currentPage > 0) { go(pager.currentPage - 1) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                LanguageToggle(accent = accents.brand)
                TextButton(onClick = onFinished) { Text(stringResource(R.string.onboarding_skip)) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth()) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
                    Text(stringResource(R.string.onboarding_step, page + 1, 3),
                        style = MaterialTheme.typography.labelLarge, color = accents.brand)
                    Spacer(Modifier.height(32.dp))
                    val line = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                        val y = size.height / 2f
                        val x = listOf(size.width * 0.12f, size.width * 0.50f, size.width * 0.88f)
                        drawLine(line, Offset(x.first(), y), Offset(x.last(), y), 2.dp.toPx(), StrokeCap.Round)
                        x.forEachIndexed { index, cx ->
                            drawCircle(if (index == page) accents.brandWash else line, 20.dp.toPx(), Offset(cx, y))
                            drawCircle(if (index == page) accents.brand else line, 20.dp.toPx(), Offset(cx, y), style = Stroke(2.dp.toPx()))
                            if (index == page) drawCircle(accents.brand, 5.dp.toPx(), Offset(cx, y))
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(when (page) {
                        0 -> R.string.onboarding_title_1
                        1 -> R.string.onboarding_title_2
                        else -> R.string.onboarding_title_3
                    }), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(when (page) {
                        0 -> R.string.onboarding_body_1
                        1 -> R.string.onboarding_body_2
                        else -> R.string.onboarding_body_3
                    }), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (page == 2) {
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.passage_privacy), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(24.dp)) {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
                    repeat(3) { index ->
                        val description = stringResource(R.string.onboarding_step, index + 1, 3)
                        Box(Modifier.size(48.dp).selectable(selected = pager.currentPage == index,
                            enabled = !pager.isScrollInProgress, role = Role.Tab, onClick = { go(index) })
                            .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                            Text("${index + 1}", color = if (pager.currentPage == index) accents.brand else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { if (pager.currentPage == 2) onFinished() else go(pager.currentPage + 1) },
                    enabled = !pager.isScrollInProgress, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(if (pager.currentPage == 2) R.string.onboarding_start else R.string.onboarding_next))
                }
            }
        }
    }
}
