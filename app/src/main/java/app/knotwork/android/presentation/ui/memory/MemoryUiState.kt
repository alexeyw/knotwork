package app.knotwork.android.presentation.ui.memory

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryPendingUpdate
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.android.domain.usecases.CompactionEstimate
import app.knotwork.design.screens.memory.MemoryCategory
import app.knotwork.design.screens.memory.MemoryDateFilter
import app.knotwork.design.screens.memory.MemorySortMode

/**
 * UI state for the redesigned Memory screen. Holds the raw chunk list plus the
 * user's view selections (category / sort / date / search) and transient dialog
 * state; the screen maps this to the catalog `MemoryViewState`.
 *
 * @property memories All stored chunks (newest-first not guaranteed; the screen sorts).
 * @property history Earlier versions by chunk id, the most recently replaced first.
 * @property pendingUpdates Each update waiting on a pinned chunk.
 * @property totalBytes On-disk size of the memory table.
 * @property lastCompactedAt Epoch-millis of the last compaction (`0` = never).
 * @property sessionNames Session id → display name, for the detail "Learned from" line.
 * @property selectedCategory Active category chip.
 * @property sortMode Active sort mode.
 * @property dateFilter Active date-range filter.
 * @property searchActive Whether the search field is shown.
 * @property searchQuery Current search query.
 * @property searchResults Scored semantic-search hits (`null` until a query runs).
 * @property expandedId Id of the entry whose detail sheet is open, or `null`.
 * @property editing Whether the open detail sheet is in edit mode.
 * @property compactDialogVisible Whether the Compact confirm dialog is shown.
 * @property compactEstimate Loaded compaction estimate (`null` while loading).
 * @property addDialogVisible Whether the Add-memory dialog is shown.
 * @property loadFailed `true` when an initial/explicit load failed; drives the
 *   Error/Retry state (a silent post-mutation refresh failure does not set it).
 */
data class MemoryUiState(
    val memories: List<MemoryChunk> = emptyList(),
    val history: Map<Long, List<MemoryVersion>> = emptyMap(),
    val pendingUpdates: List<MemoryPendingUpdate> = emptyList(),
    val totalBytes: Long = 0L,
    val lastCompactedAt: Long = 0L,
    val sessionNames: Map<String, String> = emptyMap(),
    val selectedCategory: MemoryCategory = MemoryCategory.All,
    val sortMode: MemorySortMode = MemorySortMode.Recent,
    val dateFilter: MemoryDateFilter = MemoryDateFilter.All,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Pair<MemoryChunk, Float>>? = null,
    val expandedId: Long? = null,
    val editing: Boolean = false,
    val compactDialogVisible: Boolean = false,
    val compactEstimate: CompactionEstimate? = null,
    val addDialogVisible: Boolean = false,
    val loadFailed: Boolean = false,
)
