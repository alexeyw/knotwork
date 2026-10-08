package app.knotwork.design.screens.memory

/**
 * Visual variant of the memory surface.
 */
enum class MemoryVisualState {
    /** No entries stored. */
    Empty,

    /** Default surface with one or more entries, grouped into sections. */
    Populated,

    /** Semantic search active; rows carry a relevance score. */
    Searching,

    /** Detail bottom sheet open (read-only). */
    EntryExpanded,

    /** Detail bottom sheet in edit mode. */
    Editing,

    /** Full-screen error state with Retry. */
    Error,
}

/** Sort modes exposed in the "Sort" dropdown beneath the chips. */
enum class MemorySortMode {
    /** Newest first. */
    Recent,

    /** Highest relevance score first (search). */
    Relevance,

    /** Title alphabetical, ascending. */
    Alphabetical,
}

/** Date-range filter exposed in the second dropdown. */
enum class MemoryDateFilter {
    /** No date constraint. */
    All,

    /** Only entries from the last 7 days. */
    Last7Days,

    /** Only entries from the last 30 days. */
    Last30Days,
}

/**
 * Provenance kind driving a row's left accent bar and source badge, plus the
 * breakdown-bar segments. `Unknown` covers legacy rows with no recorded source.
 */
enum class MemorySourceKind { Auto, Manual, Compaction, Unknown }

/**
 * Single-select category chip in the chip row. `All` shows everything;
 * `Pinned` shows only pinned entries; the rest narrow by [MemorySourceKind].
 */
enum class MemoryCategory { All, Pinned, Auto, Manual, Compaction }

/**
 * One segment of the provenance breakdown bar in the stats header.
 *
 * @property kind Which source this segment represents.
 * @property label Pre-formatted legend text, e.g. `"AUTO 58 %"`.
 * @property fraction Share of the whole, `0f..1f`, driving the segment width.
 */
data class MemoryBreakdownSegment(val kind: MemorySourceKind, val label: String, val fraction: Float)

/**
 * Immutable stats header card content.
 *
 * @property totalLabel Total chunk count, pre-formatted (e.g. `"1248"`).
 * @property sizeLabel On-disk size, pre-formatted (e.g. `"14.2 MB"`).
 * @property lastCompactedLabel "compacted N ago" line, or `null` if never.
 * @property segments Provenance breakdown segments (may be empty).
 */
data class MemoryStatsHeader(
    val totalLabel: String,
    val sizeLabel: String,
    val lastCompactedLabel: String?,
    val segments: List<MemoryBreakdownSegment>,
)

/**
 * One category chip with its live count.
 *
 * @property category The category this chip selects.
 * @property count Number of entries in that category.
 */
data class MemoryCategoryChip(val category: MemoryCategory, val count: Int)

/**
 * Lightweight projection of one memory chunk as a list row.
 *
 * @property id Stable identity used as the `LazyColumn` key.
 * @property title Display title (first line of the body).
 * @property body Full body text; clamped in the row.
 * @property sourceKind Provenance — drives the accent bar + badge.
 * @property tags Tag chips rendered under the body.
 * @property relevanceScore Pre-formatted score (e.g. `"0.97"`); non-null only
 *   in search mode.
 * @property timestampLabel Relative time (e.g. `"2h"`).
 * @property isPinned Whether the row is pinned (drives the pin glyph).
 * @property historyCount How many earlier versions the entry keeps; `0` hides the marker.
 * @property pairRole The entry's half of a pair (a pinned entry with an update waiting,
 *   or that update), or `null` when it is in no pair.
 * @property pairChild `true` when the row is drawn inset under its pinned entry; an
 *   update shown alone (its pinned entry filtered out) is not a child.
 */
data class MemoryRow(
    val id: String,
    val title: String,
    val body: String,
    val sourceKind: MemorySourceKind,
    val tags: List<String>,
    val relevanceScore: String?,
    val timestampLabel: String,
    val isPinned: Boolean,
    val historyCount: Int = 0,
    val pairRole: MemoryPairRole? = null,
    val pairChild: Boolean = false,
)

/** Which half of a pair an entry is. */
enum class MemoryPairRole {
    /** A pinned entry with an update waiting beside it. */
    PinnedWithUpdate,

    /** An update waiting on a pinned entry. */
    UpdateOfPinned,
}

/**
 * One earlier version of an entry, as the detail sheet shows it.
 *
 * @property id Stable identity of the version.
 * @property text The earlier text.
 * @property sourceKind Where that text came from.
 * @property learnedFrom Name of the chat it was learned from, or `null`.
 * @property capturedLabel When it was first stored (medium date, e.g. `"2 Oct 2026"`).
 * @property replacedLabel When a newer statement replaced it (same format).
 */
data class MemoryVersionView(
    val id: String,
    val text: String,
    val sourceKind: MemorySourceKind,
    val learnedFrom: String?,
    val capturedLabel: String,
    val replacedLabel: String,
)

/**
 * The other half of a pair, as the detail sheet's pair block shows it.
 *
 * @property role The half the open entry is.
 * @property otherText Text of the other half.
 * @property otherSourceKind Where the other half's text came from.
 * @property otherLearnedFrom Name of the chat the other half was learned from, or `null`.
 * @property otherCapturedLabel When the other half was stored (medium date).
 */
data class MemoryPairView(
    val role: MemoryPairRole,
    val otherText: String,
    val otherSourceKind: MemorySourceKind,
    val otherLearnedFrom: String?,
    val otherCapturedLabel: String,
)

/**
 * A titled group of rows ("PINNED", "TODAY", "THIS WEEK", …).
 *
 * @property title Section header label (rendered upper-case by the surface).
 * @property count Number of rows in the section.
 * @property rows The rows in the section.
 */
data class MemorySection(val title: String, val count: Int, val rows: List<MemoryRow>)

/**
 * Per-entry payload for the detail bottom sheet.
 *
 * @property id Stable identity.
 * @property title Display title.
 * @property body Full body text.
 * @property sourceKind Provenance — drives the badge tint.
 * @property sourceLabel Human source label (e.g. `"Auto-extracted"`).
 * @property tokenLabel Approximate token count (e.g. `"58 tok"`).
 * @property tags Current tags (editable in edit mode).
 * @property learnedFromLabel "Learned from" line, or `null` when unknown.
 * @property capturedLabel Captured timestamp line.
 * @property usedInLabel "Used in N runs …" line, or `null` when never used.
 * @property isPinned Whether the entry is pinned.
 * @property history Earlier versions, the most recently replaced first.
 * @property pair The other half of the entry's pair, or `null` when it is in none.
 */
data class MemoryEntryDetail(
    val id: String,
    val title: String,
    val body: String,
    val sourceKind: MemorySourceKind,
    val sourceLabel: String,
    val tokenLabel: String,
    val tags: List<String>,
    val learnedFromLabel: String?,
    val capturedLabel: String,
    val usedInLabel: String?,
    val isPinned: Boolean,
    val history: List<MemoryVersionView> = emptyList(),
    val pair: MemoryPairView? = null,
)

/**
 * Pre-formatted compaction estimate shown in the confirm dialog.
 *
 * @property removedLabel e.g. `"~140"`.
 * @property freedLabel e.g. `"~1.8 MB"`.
 * @property runtimeLabel e.g. `"~4 s"`.
 */
data class CompactionEstimateView(val removedLabel: String, val freedLabel: String, val runtimeLabel: String)

/**
 * Top-level immutable input to `MemoryContent`.
 *
 * @property visualState Which variant to render.
 * @property header Stats header card content.
 * @property categoryChips Category chip row with counts.
 * @property selectedCategory Currently-selected category chip.
 * @property sortMode Active sort mode (the "Sort" dropdown value).
 * @property dateFilter Active date-range filter (the second dropdown value).
 * @property searchActive Whether the semantic-search field is shown; drives the
 *   search-field-vs-stats-card swap and per-row relevance scores independently
 *   of [visualState] (so opening a detail sheet mid-search keeps search chrome).
 * @property searchEmpty `true` when a search ran and returned no hits; renders
 *   the "no matches" empty state instead of a blank list.
 * @property searchQuery Current search field value.
 * @property sections Grouped rows to render.
 * @property expandedEntry Detail payload when [visualState] is
 *   [MemoryVisualState.EntryExpanded] / [MemoryVisualState.Editing].
 * @property errorMessage Error text rendered in [MemoryVisualState.Error].
 * @property compactDialogVisible Whether the "Compact memory?" dialog is shown.
 * @property compactEstimate Estimate rendered in that dialog (`null` while loading).
 * @property addDialogVisible Whether the "Add memory" dialog is shown.
 */
data class MemoryViewState(
    val visualState: MemoryVisualState,
    val header: MemoryStatsHeader,
    val categoryChips: List<MemoryCategoryChip> = emptyList(),
    val selectedCategory: MemoryCategory = MemoryCategory.All,
    val sortMode: MemorySortMode = MemorySortMode.Recent,
    val dateFilter: MemoryDateFilter = MemoryDateFilter.All,
    val searchActive: Boolean = false,
    val searchEmpty: Boolean = false,
    val searchQuery: String = "",
    val sections: List<MemorySection> = emptyList(),
    val expandedEntry: MemoryEntryDetail? = null,
    val errorMessage: String? = null,
    val compactDialogVisible: Boolean = false,
    val compactEstimate: CompactionEstimateView? = null,
    val addDialogVisible: Boolean = false,
)

/**
 * Callbacks emitted by `MemoryContent`. `onPairUse` / `onPairKeep` carry the id of the
 * entry whose sheet raised them — either half of the pair; `onVersionDelete` fires
 * after the user confirmed the dialog.
 */
class MemoryCallbacks(
    val onBack: () -> Unit = {},
    val onSearchOpen: () -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onClearSearch: () -> Unit = {},
    val onCategorySelect: (MemoryCategory) -> Unit = {},
    val onSortChange: (MemorySortMode) -> Unit = {},
    val onDateFilterChange: (MemoryDateFilter) -> Unit = {},
    val onEntryClick: (String) -> Unit = {},
    val onEntryPinToggle: (String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onEntryEditRequest: (String) -> Unit = {},
    val onEntryEditCommit: (id: String, body: String, tags: List<String>) -> Unit = { _, _, _ -> },
    val onEntryEditCancel: () -> Unit = {},
    val onEntryDelete: (String) -> Unit = {},
    val onCompactClick: () -> Unit = {},
    val onCompactConfirm: () -> Unit = {},
    val onCompactDismiss: () -> Unit = {},
    val onAddClick: () -> Unit = {},
    val onAddConfirm: (String) -> Unit = {},
    val onAddDismiss: () -> Unit = {},
    val onExportAll: () -> Unit = {},
    val onErrorRetry: () -> Unit = {},
    val onPairUse: (entryId: String) -> Unit = {},
    val onPairKeep: (entryId: String) -> Unit = {},
    val onVersionDelete: (versionId: String) -> Unit = {},
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopMemoryCallbacks(): MemoryCallbacks = MemoryCallbacks()
