package app.knotwork.android.presentation.ui.models

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.usecases.BenchmarkReport
import app.knotwork.android.presentation.common.ByteUnits
import app.knotwork.android.presentation.common.rememberByteUnits
import java.util.Locale

/**
 * How the Performance card writes its figures in one language: the units of a
 * duration and of throughput, the size units, and the sample caption.
 *
 * The card reads in the interface language; the benchmark text shared with
 * other people stays in English ([ENGLISH]), as every export does.
 *
 * @property milliseconds Format of a duration in ms; `%1$s` is the number.
 * @property seconds Format of a duration in seconds.
 * @property tokensPerSecond Format of decode throughput.
 * @property sizes Size units for peak memory.
 * @property locale Writes the numbers.
 * @property sampleCaption The rolling-window caption for a run count.
 */
internal data class PerformanceUnits(
    val milliseconds: String,
    val seconds: String,
    val tokensPerSecond: String,
    val sizes: ByteUnits,
    val locale: Locale,
    val sampleCaption: (Int) -> String,
) {
    /** The units of the shared benchmark text. */
    companion object {
        /** English units and numerals — the shared benchmark text is an export. */
        val ENGLISH: PerformanceUnits = PerformanceUnits(
            milliseconds = "%1\$s ms",
            seconds = "%1\$s s",
            tokensPerSecond = "%1\$s tok/s",
            sizes = ByteUnits.ENGLISH,
            locale = Locale.US,
            sampleCaption = { count -> "$count" },
        )
    }
}

/**
 * Display formatting for the model Performance card and the benchmark share
 * payload. Kept app-side (out of the catalog) so the design layer stays free of
 * unit/number-format logic, and pulled into a single file so the card readouts
 * and the shared text never drift.
 *
 * The card's figures use the units of the interface language; the shared text
 * uses [PerformanceUnits.ENGLISH].
 */
internal object PerformanceFormatting {

    private val MS_PER_SECOND = TimeAndIdConstants.MS_PER_SECOND.toDouble()
    private const val SECOND_THRESHOLD_MS = 1000L

    /**
     * Formats time-to-first-token: milliseconds below one second (`"420 ms"`),
     * seconds with one decimal at or above (`"1.2 s"`).
     */
    fun ttft(ms: Long, units: PerformanceUnits): String = if (ms < SECOND_THRESHOLD_MS) {
        String.format(units.locale, units.milliseconds, ms.toString())
    } else {
        String.format(units.locale, units.seconds, String.format(units.locale, "%.1f", ms / MS_PER_SECOND))
    }

    /** Formats decode throughput, one decimal place: `"12.4 tok/s"`. */
    fun decode(tokensPerSec: Float, units: PerformanceUnits): String =
        String.format(units.locale, units.tokensPerSecond, String.format(units.locale, "%.1f", tokensPerSec))

    /** Formats a total inference time in seconds, one decimal place: `"2.6 s"`. */
    fun totalSeconds(ms: Long, units: PerformanceUnits): String =
        String.format(units.locale, units.seconds, String.format(units.locale, "%.1f", ms / MS_PER_SECOND))

    /**
     * Formats a peak-memory byte count as `"1.8 GB"` at or above 1 GB, else
     * `"640 MB"`. Returns `null` when no reading is available (`bytes <= 0`), so
     * the card renders its "unavailable" dash.
     */
    fun memory(bytes: Long, units: PerformanceUnits): String? =
        if (bytes <= 0L) null else gigabyteOrMegabyteLabel(bytes, units.sizes)

    /**
     * Builds the plain-text benchmark summary shared via the system share sheet.
     * Mirrors the designer's payload: model name, the four figures, and a
     * one-line method/approximation note. English, whatever the interface
     * language: it leaves the app for someone else to read.
     *
     * @param report The measured benchmark figures.
     * @return The multi-line shareable text.
     */
    fun shareText(report: BenchmarkReport): String {
        val units = PerformanceUnits.ENGLISH
        val peak = memory(report.peakNativeHeapBytes, units)
            ?.let { "$it (native heap, approx.)" }
            ?: "unavailable"
        return buildString {
            appendLine("${report.modelName} · benchmark")
            appendLine()
            appendLine("TTFT         ${ttft(report.ttftMs, units)}")
            appendLine("Decode       ${decode(report.decodeTokensPerSec, units)}")
            appendLine("Total        ${totalSeconds(report.totalMs, units)}")
            appendLine("Peak memory  $peak")
            appendLine()
            append("Measured on-device · 1 warm-up + 1 measured run.")
        }
    }
}

/**
 * The [PerformanceUnits] of the current interface language, for the card.
 *
 * @return The units, resolved through string resources.
 */
@Composable
internal fun rememberPerformanceUnits(): PerformanceUnits = PerformanceUnits(
    milliseconds = stringResource(R.string.models_performance_unit_ms),
    seconds = stringResource(R.string.models_performance_unit_seconds),
    tokensPerSecond = stringResource(R.string.models_performance_unit_tokens_per_second),
    sizes = rememberByteUnits(),
    locale = LocalConfiguration.current.locales[0],
    sampleCaption = SampleCaption(LocalResources.current),
)

/**
 * Words the rolling-window caption ("avg · last 8 runs") with the language's
 * plural rules. A data class, not a lambda, so [PerformanceUnits] built again on
 * a recomposition compares equal and the screen's memoised projection holds.
 *
 * @property resources Resources in the interface language.
 */
internal data class SampleCaption(private val resources: Resources) : (Int) -> String {
    /**
     * @param count Runs in the averaged window.
     * @return The caption.
     */
    override fun invoke(count: Int): String =
        resources.getQuantityString(R.plurals.models_performance_sample_caption, count, count)
}
