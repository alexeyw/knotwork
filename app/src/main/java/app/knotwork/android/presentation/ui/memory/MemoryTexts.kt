package app.knotwork.android.presentation.ui.memory

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.presentation.common.AgeUnits
import app.knotwork.android.presentation.common.ByteUnits
import app.knotwork.android.presentation.common.rememberAgeUnits
import app.knotwork.android.presentation.common.rememberByteUnits
import java.util.Locale

/**
 * The words the Memory screen's projection writes into its rows, headers and
 * detail sheet, in the interface language.
 *
 * The projection ([toViewState]) is a pure function so it stays unit-testable;
 * it takes its words from here instead of writing English into code.
 *
 * @property segmentAuto Breakdown label of auto-extracted entries.
 * @property segmentCompaction Breakdown label of compaction summaries.
 * @property segmentManual Breakdown label of entries saved by hand.
 * @property compactedAgo Header line after a compaction; `%1$s` is the age.
 * @property compactedJustNow Header line within a minute of a compaction.
 * @property sectionPinned Section title of pinned entries.
 * @property sectionToday Section title of today's entries.
 * @property sectionThisWeek Section title of this week's entries.
 * @property sectionEarlier Section title of older entries.
 * @property learnedFromChat Detail line naming the chat an entry came from; `%1$s` is its name.
 * @property usedInRuns Detail line counting the runs that received the entry, for a count.
 * @property lastUsedAgo When the entry was last received; `%1$s` is the age.
 * @property lastUsedJustNow When the entry was received within the last minute.
 * @property sourceAuto Detail label of an auto-extracted entry.
 * @property sourceManual Detail label of an entry saved by hand.
 * @property sourceCompaction Detail label of a compaction summary.
 * @property sourceUnknown Detail label of an entry of unknown origin.
 * @property tokens Detail token estimate; `%1$d` is the count.
 * @property secondsApprox Compaction estimate of the run time; `%1$d` is seconds.
 * @property ages Short-age units for the rows.
 * @property sizes Size units for the header and the compaction estimate.
 * @property locale Writes the relevance score's decimal separator.
 */
data class MemoryTexts(
    val segmentAuto: String,
    val segmentCompaction: String,
    val segmentManual: String,
    val compactedAgo: String,
    val compactedJustNow: String,
    val sectionPinned: String,
    val sectionToday: String,
    val sectionThisWeek: String,
    val sectionEarlier: String,
    val learnedFromChat: String,
    val usedInRuns: (Int) -> String,
    val lastUsedAgo: String,
    val lastUsedJustNow: String,
    val sourceAuto: String,
    val sourceManual: String,
    val sourceCompaction: String,
    val sourceUnknown: String,
    val tokens: String,
    val secondsApprox: String,
    val ages: AgeUnits,
    val sizes: ByteUnits,
    val locale: Locale,
)

/**
 * The [MemoryTexts] of the current interface language.
 *
 * @return The screen's words, resolved through string resources.
 */
@Composable
fun rememberMemoryTexts(): MemoryTexts {
    val locale = LocalConfiguration.current.locales[0]
    return MemoryTexts(
        segmentAuto = stringResource(R.string.memory_segment_auto),
        segmentCompaction = stringResource(R.string.memory_segment_compaction),
        segmentManual = stringResource(R.string.memory_segment_manual),
        compactedAgo = stringResource(R.string.memory_header_compacted_ago),
        compactedJustNow = stringResource(R.string.memory_header_compacted_just_now),
        sectionPinned = stringResource(R.string.memory_section_pinned),
        sectionToday = stringResource(R.string.memory_section_today),
        sectionThisWeek = stringResource(R.string.memory_section_this_week),
        sectionEarlier = stringResource(R.string.memory_section_earlier),
        learnedFromChat = stringResource(R.string.memory_detail_learned_from_chat),
        usedInRuns = RunCounter(LocalResources.current),
        lastUsedAgo = stringResource(R.string.memory_detail_last_used_ago),
        lastUsedJustNow = stringResource(R.string.memory_detail_last_used_just_now),
        sourceAuto = stringResource(R.string.memory_source_auto),
        sourceManual = stringResource(R.string.memory_source_manual),
        sourceCompaction = stringResource(R.string.memory_source_compaction),
        sourceUnknown = stringResource(R.string.memory_source_unknown),
        tokens = stringResource(R.string.memory_detail_tokens),
        secondsApprox = stringResource(R.string.memory_compact_seconds_approx),
        ages = rememberAgeUnits(),
        sizes = rememberByteUnits(),
        locale = locale,
    )
}

/**
 * Words a run count with the language's plural rules — one count per entry, so
 * it cannot be resolved once in composition.
 *
 * A data class, not a lambda: two counters over the same resources are equal,
 * so [MemoryTexts] built again on a recomposition compares equal and the screen's
 * memoised projection is not rebuilt on every keystroke. A fresh lambda would
 * break that equality each time.
 *
 * @property resources Resources in the interface language.
 */
internal data class RunCounter(private val resources: Resources) : (Int) -> String {
    /**
     * @param count Runs that received the entry.
     * @return The count's line.
     */
    override fun invoke(count: Int): String = resources.getQuantityString(R.plurals.memory_used_in_runs, count, count)
}
