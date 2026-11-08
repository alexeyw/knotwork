package app.knotwork.android.presentation.ui.models

import app.knotwork.android.domain.usecases.BenchmarkReport
import app.knotwork.android.presentation.common.ByteUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PerformanceFormattingTest {

    @Test
    fun `ttft renders milliseconds below one second and seconds above`() {
        assertEquals("420 ms", PerformanceFormatting.ttft(420, PerformanceUnits.ENGLISH))
        assertEquals("999 ms", PerformanceFormatting.ttft(999, PerformanceUnits.ENGLISH))
        assertEquals("1.2 s", PerformanceFormatting.ttft(1_200, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `decode renders one decimal place`() {
        assertEquals("12.4 tok/s", PerformanceFormatting.decode(12.44f, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `total renders seconds with one decimal`() {
        assertEquals("2.6 s", PerformanceFormatting.totalSeconds(2_550, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `memory renders GB above one gigabyte and MB below`() {
        assertEquals(
            "1.8 GB",
            PerformanceFormatting.memory((1.8 * 1024 * 1024 * 1024).toLong(), PerformanceUnits.ENGLISH),
        )
        assertEquals("640 MB", PerformanceFormatting.memory(640L * 1024 * 1024, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `memory is null when no reading is available`() {
        assertNull(PerformanceFormatting.memory(0, PerformanceUnits.ENGLISH))
        assertNull(PerformanceFormatting.memory(-1, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `memory never renders 1024 MB just below the gigabyte boundary`() {
        // ~1023.9 MiB: would round to "1024 MB" under a naive %.0f; must read as GB instead.
        val justUnderOneGb = (1023.9 * 1024 * 1024).toLong()
        assertEquals("1.0 GB", PerformanceFormatting.memory(justUnderOneGb, PerformanceUnits.ENGLISH))
    }

    @Test
    fun `given Russian units when formatted then the card writes Russian units and a decimal comma`() {
        val russian = PerformanceUnits(
            milliseconds = "%1\$s мс",
            seconds = "%1\$s с",
            tokensPerSecond = "%1\$s ток/с",
            sizes = ByteUnits("%1\$s Б", "%1\$s КБ", "%1\$s МБ", "%1\$s ГБ", Locale.forLanguageTag("ru")),
            locale = Locale.forLanguageTag("ru"),
            sampleCaption = { "$it" },
        )

        assertEquals("1,2 с", PerformanceFormatting.ttft(1_200, russian))
        assertEquals("12,4 ток/с", PerformanceFormatting.decode(12.44f, russian))
        assertEquals("1,8 ГБ", PerformanceFormatting.memory((1.8 * 1024 * 1024 * 1024).toLong(), russian))
    }

    @Test
    fun `given the app in Russian when the benchmark is shared then the text stays English`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru"))
        try {
            val text = PerformanceFormatting.shareText(BenchmarkReport("m", 1_200, 12.44f, 2_550, 640L * 1024 * 1024))

            assertTrue(text, text.contains("TTFT         1.2 s"))
            assertTrue(text, text.contains("Peak memory  640 MB (native heap, approx.)"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `share text carries the model name the four figures and the approx note`() {
        val report = BenchmarkReport(
            modelName = "gemma-4-E2B-it",
            ttftMs = 390,
            decodeTokensPerSec = 13.1f,
            totalMs = 2_600,
            peakNativeHeapBytes = (1.8 * 1024 * 1024 * 1024).toLong(),
        )

        val text = PerformanceFormatting.shareText(report)

        assertTrue(text.contains("gemma-4-E2B-it · benchmark"))
        assertTrue(text.contains("TTFT         390 ms"))
        assertTrue(text.contains("Decode       13.1 tok/s"))
        assertTrue(text.contains("Total        2.6 s"))
        assertTrue(text.contains("Peak memory  1.8 GB (native heap, approx.)"))
        assertTrue(text.contains("1 warm-up + 1 measured run"))
    }

    @Test
    fun `share text marks memory unavailable when no reading was taken`() {
        val report = BenchmarkReport("m", 100, 5f, 1_000, 0)
        assertTrue(PerformanceFormatting.shareText(report).contains("Peak memory  unavailable"))
    }
}
