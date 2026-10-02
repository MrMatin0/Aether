package studio.cluvex.aether.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import studio.cluvex.aether.ui.components.ButtonMode
import studio.cluvex.aether.ui.components.connectedCoreColors
import studio.cluvex.aether.ui.components.orbPulseEnabled
import studio.cluvex.aether.ui.theme.DarkAccents
import studio.cluvex.aether.ui.theme.LightAccents

class AuroraRegressionTest {
    private fun contrast(a: Color, b: Color): Float =
        (max(a.luminance(), b.luminance()) + 0.05f) /
            (min(a.luminance(), b.luminance()) + 0.05f)

    @Test fun radialClockRunsOnlyWhenItsRingIsVisible() {
        ButtonMode.entries.forEach { mode ->
            listOf(false, true).forEach { enabled ->
                listOf(false, true).forEach { reduced ->
                    assertEquals(
                        mode == ButtonMode.BUSY && enabled && !reduced,
                        orbPulseEnabled(mode, enabled, reduced),
                        "$mode enabled=$enabled reduced=$reduced",
                    )
                }
            }
        }
    }

    @Test fun connectedFillAndClockKeepNormalTextContrastInBothThemes() {
        listOf(LightAccents, DarkAccents).forEach { accents ->
            val stops = connectedCoreColors(accents.protected, accents.dark)
            stops.zipWithNext().forEach { (start, end) ->
                (0..20).forEach { step ->
                    val fill = lerp(start, end, step / 20f)
                    val highlighted = if (accents.dark) {
                        Color.White.copy(alpha = 0.18f).compositeOver(fill)
                    } else {
                        fill
                    }
                    listOf(fill, highlighted).forEach { surface ->
                        val clock = accents.onProtected.copy(alpha = 0.16f).compositeOver(surface)
                        listOf(surface, clock).forEach { background ->
                            val ratio = contrast(accents.onProtected, background)
                            assertTrue(ratio >= 4.5f, "dark=${accents.dark}: contrast=$ratio")
                        }
                    }
                }
            }
        }
    }
}
