package app.knotwork.design.screens.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for the redesigned `MemoryContent`
 * stats header + category chips + sort/date dropdowns + grouped
 * list, the semantic-search variant, the detail bottom sheet (read + edit),
 * the Compact dialog, plus the Empty and Error states — in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MemoryContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun memory_empty_light() = snapshot("empty", dark = false) {
        MemoryContent(state = MemoryPreview.empty())
    }

    @Test
    fun memory_empty_dark() = snapshot("empty", dark = true) {
        MemoryContent(state = MemoryPreview.empty())
    }

    @Test
    fun memory_populated_light() = snapshot("populated", dark = false) {
        MemoryContent(state = MemoryPreview.populated())
    }

    @Test
    fun memory_populated_dark() = snapshot("populated", dark = true) {
        MemoryContent(state = MemoryPreview.populated())
    }

    @Test
    fun memory_searching_light() = snapshot("searching", dark = false) {
        MemoryContent(state = MemoryPreview.searching())
    }

    @Test
    fun memory_searching_dark() = snapshot("searching", dark = true) {
        MemoryContent(state = MemoryPreview.searching())
    }

    @Test
    fun memory_no_matches_light() = snapshot("no_matches", dark = false) {
        MemoryContent(state = MemoryPreview.noMatches())
    }

    @Test
    fun memory_no_matches_dark() = snapshot("no_matches", dark = true) {
        MemoryContent(state = MemoryPreview.noMatches())
    }

    @Test
    fun memory_entry_expanded_light() = snapshot("entry_expanded", dark = false) {
        MemoryContent(state = MemoryPreview.entryExpanded())
    }

    @Test
    fun memory_entry_expanded_dark() = snapshot("entry_expanded", dark = true) {
        MemoryContent(state = MemoryPreview.entryExpanded())
    }

    @Test
    fun memory_editing_light() = snapshot("editing", dark = false) {
        MemoryContent(state = MemoryPreview.editing())
    }

    @Test
    fun memory_editing_dark() = snapshot("editing", dark = true) {
        MemoryContent(state = MemoryPreview.editing())
    }

    @Test
    fun memory_compact_dialog_light() = snapshot("compact_dialog", dark = false) {
        MemoryContent(state = MemoryPreview.compactDialog())
    }

    @Test
    fun memory_compact_dialog_dark() = snapshot("compact_dialog", dark = true) {
        MemoryContent(state = MemoryPreview.compactDialog())
    }

    @Test
    fun memory_error_light() = snapshot("error", dark = false) {
        MemoryContent(state = MemoryPreview.error())
    }

    @Test
    fun memory_error_dark() = snapshot("error", dark = true) {
        MemoryContent(state = MemoryPreview.error())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/memory_${name}_$themeTag.png",
        )
    }
}

/** Internal preview fixtures backing the memory snapshot + a11y suites. */
internal object MemoryPreview {

    private fun header() = MemoryStatsHeader(
        totalLabel = "1248",
        sizeLabel = "14.2 MB",
        lastCompactedLabel = "compacted 3 d ago",
        segments = listOf(
            MemoryBreakdownSegment(MemorySourceKind.Auto, "AUTO 58 %", 0.58f),
            MemoryBreakdownSegment(MemorySourceKind.Compaction, "COMPACT 27 %", 0.27f),
            MemoryBreakdownSegment(MemorySourceKind.Manual, "MANUAL 15 %", 0.15f),
        ),
    )

    private fun chips() = listOf(
        MemoryCategoryChip(MemoryCategory.All, 7),
        MemoryCategoryChip(MemoryCategory.Pinned, 2),
        MemoryCategoryChip(MemoryCategory.Auto, 3),
        MemoryCategoryChip(MemoryCategory.Manual, 2),
        MemoryCategoryChip(MemoryCategory.Compaction, 2),
    )

    @Suppress("LongParameterList")
    private fun row(
        id: String,
        title: String,
        body: String,
        kind: MemorySourceKind,
        tags: List<String>,
        time: String,
        pinned: Boolean = false,
        score: String? = null,
    ) = MemoryRow(
        id = id,
        title = title,
        body = body,
        sourceKind = kind,
        tags = tags,
        relevanceScore = score,
        timestampLabel = time,
        isPinned = pinned,
    )

    private fun sections(score: Boolean = false) = listOf(
        MemorySection(
            "Pinned",
            2,
            listOf(
                row(
                    id = "1",
                    title = "Timezone & schedule",
                    body = "Calendar timezone is Europe/Berlin. schedule_task runs always use " +
                        "device-local time, never UTC.",
                    kind = MemorySourceKind.Manual,
                    tags = listOf("fact", "calendar"),
                    time = "2h",
                    pinned = true,
                    score = if (score) "0.97" else null,
                ),
                row(
                    id = "2",
                    title = "Project deadlines",
                    body = "The next milestone ships by 2026-05-20; the v0.1 tag follows once the release gate closes.",
                    kind = MemorySourceKind.Auto,
                    tags = listOf("project", "deadlines"),
                    time = "2h",
                    pinned = true,
                    score = if (score) "0.94" else null,
                ),
            ),
        ),
        MemorySection(
            "Today",
            1,
            listOf(
                row(
                    id = "3",
                    title = "Reply style",
                    body = "Prefers concise bullet summaries for technical papers; tolerates jargon.",
                    kind = MemorySourceKind.Compaction,
                    tags = listOf("preference", "style"),
                    time = "5h",
                    score = if (score) "0.88" else null,
                ),
            ),
        ),
        MemorySection(
            "This week",
            3,
            listOf(
                row(
                    id = "4",
                    title = "Preferred IDE",
                    body = "Android Studio Iguana with the Knotwork plugin enabled. Uses the embedded JDK.",
                    kind = MemorySourceKind.Auto,
                    tags = listOf("tooling"),
                    time = "yesterday",
                    score = if (score) "0.81" else null,
                ),
            ),
        ),
    )

    private fun detail() = MemoryEntryDetail(
        id = "4",
        title = "LiteRT delegates",
        body = "On Pixel 9, LiteRT delegate options include NPU via the QNN backend; " +
            "the CPU fallback is significant for >2B models.",
        sourceKind = MemorySourceKind.Auto,
        sourceLabel = "Auto-extracted",
        tokenLabel = "58 tok",
        tags = listOf("knowledge", "on-device"),
        learnedFromLabel = "Chat \"Pixel 9 NPU setup\"",
        capturedLabel = "2026-05-27 · 14:02",
        usedInLabel = "6 replies · last 2 h ago",
        isPinned = false,
    )

    fun empty() = MemoryViewState(
        visualState = MemoryVisualState.Empty,
        header = header(),
    )

    fun populated() = MemoryViewState(
        visualState = MemoryVisualState.Populated,
        header = header(),
        categoryChips = chips(),
        sections = sections(),
    )

    /** Same as [populated]; the first two rows are already pinned. */
    fun populatedPinned() = populated()

    fun searching() = MemoryViewState(
        visualState = MemoryVisualState.Searching,
        header = header(),
        categoryChips = chips(),
        searchActive = true,
        searchQuery = "berlin",
        sortMode = MemorySortMode.Relevance,
        sections = sections(score = true),
    )

    fun noMatches() = MemoryViewState(
        visualState = MemoryVisualState.Searching,
        header = header(),
        categoryChips = chips(),
        searchActive = true,
        searchEmpty = true,
        searchQuery = "zzz",
        sortMode = MemorySortMode.Relevance,
        sections = emptyList(),
    )

    fun entryExpanded() = MemoryViewState(
        visualState = MemoryVisualState.EntryExpanded,
        header = header(),
        categoryChips = chips(),
        sections = sections(),
        expandedEntry = detail(),
    )

    fun editing() = MemoryViewState(
        visualState = MemoryVisualState.Editing,
        header = header(),
        categoryChips = chips(),
        sections = sections(),
        expandedEntry = detail(),
    )

    fun compactDialog() = populated().copy(
        compactDialogVisible = true,
        compactEstimate = CompactionEstimateView(removedLabel = "~140", freedLabel = "~1.8 MB", runtimeLabel = "~4 s"),
    )

    fun error() = MemoryViewState(
        visualState = MemoryVisualState.Error,
        header = header(),
        errorMessage = "Vector store unreachable: SQLCipher passphrase missing.",
    )
}
