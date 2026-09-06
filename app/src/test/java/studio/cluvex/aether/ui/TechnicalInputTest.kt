package studio.cluvex.aether.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import studio.cluvex.aether.ui.components.normalizeTechnicalText
import studio.cluvex.aether.ui.components.normalizeTechnicalValue
import studio.cluvex.aether.ui.engine.boundedSecondsText

class TechnicalInputTest {
    @Test fun normalizesPersianAndArabicDigitsAndSeparators() {
        assertEquals("192.168.1.1:443,10", normalizeTechnicalText("\u200f۱۹۲٫۱۶۸٫۱٫۱:٤٤٣،۱۰\u200e"))
    }
    @Test fun remapsReversedSelectionAndComposition() {
        val result = normalizeTechnicalValue(TextFieldValue("\u200f۱۲\u200e۳", TextRange(5, 1), TextRange(1, 5)))
        assertEquals("123", result.text)
        assertEquals(TextRange(3, 0), result.selection)
        assertEquals(TextRange(0, 3), result.composition)
    }
    @Test fun removesEmptyComposingRegion() {
        val result = normalizeTechnicalValue(TextFieldValue("\u200f1", TextRange(2), TextRange(0, 1)))
        assertNull(result.composition)
    }
    @Test fun leavesAsciiEditAndCompositionUnchanged() {
        val value = TextFieldValue("127.0.0.1", TextRange(3), TextRange(0, 3))
        assertEquals(value, normalizeTechnicalValue(value))
    }
    @Test fun secondsClampWithoutTruncationOrIntegerOverflow() {
        assertEquals("600", boundedSecondsText("9999", 600))
        assertEquals("3600", boundedSecondsText("12005", 3600))
        assertEquals("600", boundedSecondsText("9".repeat(1000), 600))
        assertEquals("120", boundedSecondsText("۰۰۱۲۰", 600))
        assertEquals("", boundedSecondsText("000", 600))
        assertEquals("", boundedSecondsText("", 600))
        assertEquals("", boundedSecondsText("abc", 600))
    }
}
