package app.knotwork.design.screens.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
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

    @Test
    fun memory_pair_list_light() = snapshot("pair_list", dark = false) {
        MemoryContent(state = MemoryPreview.pairList())
    }

    @Test
    fun memory_pair_list_dark() = snapshot("pair_list", dark = true) {
        MemoryContent(state = MemoryPreview.pairList())
    }

    @Test
    fun memory_history_collapsed_light() = snapshot("history_collapsed", dark = false) {
        MemoryContent(state = MemoryPreview.historySheet())
    }

    @Test
    fun memory_history_collapsed_dark() = snapshot("history_collapsed", dark = true) {
        MemoryContent(state = MemoryPreview.historySheet())
    }

    @Test
    fun memory_history_expanded_light() = snapshot("history_expanded", dark = false, before = ::openHistory) {
        MemoryContent(state = MemoryPreview.historySheet())
    }

    @Test
    fun memory_history_expanded_dark() = snapshot("history_expanded", dark = true, before = ::openHistory) {
        MemoryContent(state = MemoryPreview.historySheet())
    }

    @Test
    fun memory_pair_pinned_sheet_light() = snapshot("pair_pinned_sheet", dark = false) {
        MemoryContent(state = MemoryPreview.pinnedPairSheet())
    }

    @Test
    fun memory_pair_pinned_sheet_dark() = snapshot("pair_pinned_sheet", dark = true) {
        MemoryContent(state = MemoryPreview.pinnedPairSheet())
    }

    @Test
    fun memory_pair_update_sheet_light() = snapshot("pair_update_sheet", dark = false) {
        MemoryContent(state = MemoryPreview.updatePairSheet())
    }

    @Test
    fun memory_pair_update_sheet_dark() = snapshot("pair_update_sheet", dark = true) {
        MemoryContent(state = MemoryPreview.updatePairSheet())
    }

    @Test
    fun memory_editing_history_light() = snapshot("editing_history", dark = false) {
        MemoryContent(state = MemoryPreview.editingWithHistory())
    }

    @Test
    fun memory_editing_history_dark() = snapshot("editing_history", dark = true) {
        MemoryContent(state = MemoryPreview.editingWithHistory())
    }

    @Test
    fun memory_delete_version_dialog_light() =
        snapshot("delete_version_dialog", dark = false, before = ::askToDeleteFirstVersion) {
            MemoryContent(state = MemoryPreview.historySheet())
        }

    @Test
    fun memory_pair_list_font_scale_2() = snapshot("pair_list_fs20", dark = false, fontScale = LARGEST_FONT_SCALE) {
        MemoryContent(state = MemoryPreview.pairList())
    }

    @Test
    fun memory_history_expanded_font_scale_2() =
        snapshot("history_expanded_fs20", dark = false, fontScale = LARGEST_FONT_SCALE, before = ::openHistory) {
            MemoryContent(state = MemoryPreview.historySheet())
        }

    @Test
    fun memory_pair_pinned_sheet_font_scale_2() =
        snapshot("pair_pinned_sheet_fs20", dark = false, fontScale = LARGEST_FONT_SCALE) {
            MemoryContent(state = MemoryPreview.pinnedPairSheet())
        }

    private fun openHistory() {
        composeTestRule.onNodeWithText("2 earlier versions").performClick()
        composeTestRule.waitForIdle()
    }

    private fun askToDeleteFirstVersion() {
        openHistory()
        composeTestRule.onAllNodesWithContentDescription("More options for this version")[0].performClick()
        composeTestRule.onNodeWithText("Delete this version").performClick()
        composeTestRule.waitForIdle()
    }

    private fun snapshot(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        before: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    content()
                }
            }
        }
        before()
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/memory_${name}_$themeTag.png",
        )
    }

    private companion object {
        /** Android system "Largest" font-size preset. */
        const val LARGEST_FONT_SCALE = 2f
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

    private fun cityRow(child: Boolean) = if (child) {
        row(
            id = "11",
            title = "Home city",
            body = "Moved to Lisbon in September; looking for a flat in Alfama.",
            kind = MemorySourceKind.Auto,
            tags = listOf("personal", "place"),
            time = "1 d",
        ).copy(pairRole = MemoryPairRole.UpdateOfPinned, pairChild = true)
    } else {
        row(
            id = "10",
            title = "Home city",
            body = "Lives in Berlin, Kreuzberg.",
            kind = MemorySourceKind.Manual,
            tags = listOf("personal", "place"),
            time = "5 mo",
            pinned = true,
        ).copy(pairRole = MemoryPairRole.PinnedWithUpdate)
    }

    private fun languageVersions() = listOf(
        MemoryVersionView(
            id = "21",
            text = "Prefers replies in Russian.",
            sourceKind = MemorySourceKind.Auto,
            learnedFrom = "Visa appointment",
            capturedLabel = "14 Jun 2026",
            replacedLabel = "2 Oct 2026",
        ),
        MemoryVersionView(
            id = "20",
            text = "Writes in Russian and English.",
            sourceKind = MemorySourceKind.Compaction,
            learnedFrom = null,
            capturedLabel = "3 Feb 2026",
            replacedLabel = "14 Jun 2026",
        ),
    )

    private fun languageDetail() = MemoryEntryDetail(
        id = "12",
        title = "Reply language",
        body = "Prefers replies in English, except for messages to family.",
        sourceKind = MemorySourceKind.Auto,
        sourceLabel = "Auto-extracted",
        tokenLabel = "14 tok",
        tags = listOf("preference", "style"),
        learnedFromLabel = "Chat \"Weekend plans\"",
        capturedLabel = "2026-10-02 · 18:40",
        usedInLabel = "4 replies · last 1 h ago",
        isPinned = false,
        history = languageVersions(),
    )

    private fun cityDetail(pinnedHalf: Boolean) = if (pinnedHalf) {
        MemoryEntryDetail(
            id = "10",
            title = "Home city",
            body = "Lives in Berlin, Kreuzberg.",
            sourceKind = MemorySourceKind.Manual,
            sourceLabel = "Saved manually",
            tokenLabel = "7 tok",
            tags = listOf("personal", "place"),
            learnedFromLabel = null,
            capturedLabel = "2026-05-12 · 09:15",
            usedInLabel = "9 replies · last 3 h ago",
            isPinned = true,
            pair = MemoryPairView(
                role = MemoryPairRole.PinnedWithUpdate,
                otherText = "Moved to Lisbon in September; looking for a flat in Alfama.",
                otherSourceKind = MemorySourceKind.Auto,
                otherLearnedFrom = "Flat viewings",
                otherCapturedLabel = "5 Oct 2026",
            ),
        )
    } else {
        MemoryEntryDetail(
            id = "11",
            title = "Home city",
            body = "Moved to Lisbon in September; looking for a flat in Alfama.",
            sourceKind = MemorySourceKind.Auto,
            sourceLabel = "Auto-extracted",
            tokenLabel = "14 tok",
            tags = listOf("personal", "place"),
            learnedFromLabel = "Chat \"Flat viewings\"",
            capturedLabel = "2026-10-05 · 20:11",
            usedInLabel = null,
            isPinned = false,
            pair = MemoryPairView(
                role = MemoryPairRole.UpdateOfPinned,
                otherText = "Lives in Berlin, Kreuzberg.",
                otherSourceKind = MemorySourceKind.Manual,
                otherLearnedFrom = null,
                otherCapturedLabel = "12 May 2026",
            ),
        )
    }

    private fun pairSections() = listOf(
        MemorySection("Pinned", 1, listOf(cityRow(child = false), cityRow(child = true))),
        MemorySection(
            "This week",
            1,
            listOf(
                row(
                    id = "12",
                    title = "Reply language",
                    body = "Prefers replies in English, except for messages to family.",
                    kind = MemorySourceKind.Auto,
                    tags = listOf("preference", "style"),
                    time = "4 d",
                ).copy(historyCount = 2),
            ),
        ),
    )

    /** A pinned entry with its waiting update drawn under it, and an entry with two earlier versions. */
    fun pairList() = populated().copy(sections = pairSections())

    /** The sheet of an entry with two earlier versions (collapsed until a test opens it). */
    fun historySheet() = pairList().copy(
        visualState = MemoryVisualState.EntryExpanded,
        expandedEntry = languageDetail(),
    )

    /** The pinned half's sheet: an update is waiting. */
    fun pinnedPairSheet() = pairList().copy(
        visualState = MemoryVisualState.EntryExpanded,
        expandedEntry = cityDetail(pinnedHalf = true),
    )

    /** The update's sheet: it updates a pinned memory. */
    fun updatePairSheet() = pairList().copy(
        visualState = MemoryVisualState.EntryExpanded,
        expandedEntry = cityDetail(pinnedHalf = false),
    )

    /** Edit mode on an entry with history: the note replaces the history and pair blocks. */
    fun editingWithHistory() = pairList().copy(
        visualState = MemoryVisualState.Editing,
        expandedEntry = languageDetail(),
    )

    fun error() = MemoryViewState(
        visualState = MemoryVisualState.Error,
        header = header(),
        errorMessage = "Vector store unreachable: SQLCipher passphrase missing.",
    )
}
