package app.knotwork.android.presentation.ui.memory

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.usecases.CompactionEstimate
import app.knotwork.android.presentation.common.DisplayFormat
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.memory.CompactionEstimateView
import app.knotwork.design.screens.memory.MemoryBreakdownSegment
import app.knotwork.design.screens.memory.MemoryCallbacks
import app.knotwork.design.screens.memory.MemoryCategory
import app.knotwork.design.screens.memory.MemoryCategoryChip
import app.knotwork.design.screens.memory.MemoryContent
import app.knotwork.design.screens.memory.MemoryDateFilter
import app.knotwork.design.screens.memory.MemoryEntryDetail
import app.knotwork.design.screens.memory.MemoryRow
import app.knotwork.design.screens.memory.MemorySection
import app.knotwork.design.screens.memory.MemorySortMode
import app.knotwork.design.screens.memory.MemorySourceKind
import app.knotwork.design.screens.memory.MemoryStatsHeader
import app.knotwork.design.screens.memory.MemoryViewState
import app.knotwork.design.screens.memory.MemoryVisualState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Long-term-memory screen. Subscribes to [MemoryViewModel],
 * maps [MemoryUiState] to the catalog [MemoryViewState] (stats header,
 * provenance breakdown, category chips, time-grouped sections, detail sheet,
 * dialogs), and forwards every interaction back to the VM.
 */
@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val stream = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
        if (stream != null) viewModel.exportAllTo(stream)
    }
    LaunchedEffect(viewModel) {
        viewModel.exportRequests.collect { exportLauncher.launch(EXPORT_FILENAME) }
    }

    val loadErrorText = stringResource(R.string.memory_msg_load_error)
    val addErrorText = stringResource(R.string.memory_msg_add_error)
    val editErrorText = stringResource(R.string.memory_msg_edit_error)
    LaunchedEffect(viewModel) {
        viewModel.messageEvents.collect { message ->
            val text = when (message) {
                MemoryMessage.LoadError -> loadErrorText
                MemoryMessage.AddError -> addErrorText
                MemoryMessage.EditError -> editErrorText
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // The expensive list projection (filter / group / sort / breakdown / chip
    // counts) is memoised only on the fields that affect it, so search
    // keystrokes and dialog-visibility toggles don't re-run the O(N) work.
    val listViewState = remember(
        uiState.memories,
        uiState.selectedCategory,
        uiState.sortMode,
        uiState.dateFilter,
        uiState.searchResults,
        uiState.searchActive,
        uiState.searchQuery,
        uiState.expandedId,
        uiState.editing,
        uiState.totalBytes,
        uiState.lastCompactedAt,
        uiState.sessionNames,
        uiState.loadFailed,
    ) {
        uiState.toViewState(nowMillis = System.currentTimeMillis())
    }
    // Dialog visibility + the loaded estimate are intentionally excluded from
    // the memo keys (toggling them must not re-run the O(N) projection); they
    // are overlaid here cheaply on every recomposition.
    val viewState = listViewState.copy(
        compactDialogVisible = uiState.compactDialogVisible,
        compactEstimate = uiState.compactEstimate?.toView(),
        addDialogVisible = uiState.addDialogVisible,
    )

    val callbacks = MemoryCallbacks(
        onBack = onBack,
        onSearchOpen = viewModel::openSearch,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onClearSearch = viewModel::closeSearch,
        onCategorySelect = viewModel::selectCategory,
        onSortChange = viewModel::setSortMode,
        onDateFilterChange = viewModel::setDateFilter,
        onEntryClick = { id -> id.toLongOrNull()?.let(viewModel::openEntry) },
        onEntryPinToggle = { id -> id.toLongOrNull()?.let(viewModel::togglePin) },
        onCloseDetail = viewModel::closeEntry,
        onEntryEditRequest = { id -> id.toLongOrNull()?.let(viewModel::editEntry) },
        onEntryEditCommit = { id, body, tags -> id.toLongOrNull()?.let { viewModel.commitEdit(it, body, tags) } },
        onEntryEditCancel = viewModel::cancelEdit,
        onEntryDelete = { id -> id.toLongOrNull()?.let(viewModel::deleteEntry) },
        onCompactClick = viewModel::showCompactDialog,
        onCompactConfirm = viewModel::confirmCompact,
        onCompactDismiss = viewModel::dismissCompactDialog,
        onAddClick = viewModel::showAddDialog,
        onAddConfirm = viewModel::confirmAdd,
        onAddDismiss = viewModel::dismissAddDialog,
        onExportAll = viewModel::requestExportAll,
        onErrorRetry = viewModel::loadAllData,
    )

    Box(modifier = Modifier.fillMaxSize().testTag(tag = MEMORY_ROOT_TEST_TAG)) {
        // In the content's Scaffold, which lifts it above the "Add memory" FAB.
        MemoryContent(
            state = viewState,
            callbacks = callbacks,
            snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
        )
    }
}

/**
 * Pure projection of [MemoryUiState] onto the catalog [MemoryViewState].
 * `internal` + `nowMillis` so the grouping / breakdown / labelling is unit-testable.
 */
internal fun MemoryUiState.toViewState(nowMillis: Long): MemoryViewState {
    val expanded = expandedId?.let { id -> memories.firstOrNull { it.id == id } }
    val visualState = when {
        loadFailed -> MemoryVisualState.Error
        expanded != null && editing -> MemoryVisualState.Editing
        expanded != null -> MemoryVisualState.EntryExpanded
        memories.isEmpty() -> MemoryVisualState.Empty
        searchActive -> MemoryVisualState.Searching
        else -> MemoryVisualState.Populated
    }

    val sections = if (searchActive) {
        // Search results are a single relevance-ordered list (the order
        // retrieveScored returns) — no time bucketing, which would scatter the
        // ranking the search header advertises. Blank title = no section header.
        val hits = searchResults ?: emptyList()
        if (hits.isEmpty()) {
            emptyList()
        } else {
            listOf(
                MemorySection(
                    title = "",
                    count = hits.size,
                    rows = hits.map {
                        it.first.toRow(it.second, nowMillis)
                    },
                ),
            )
        }
    } else {
        val scored = memories
            .filter { it.matchesCategory(selectedCategory) }
            .filter { it.matchesDate(dateFilter, nowMillis) }
            .map { it to null as Float? }
        buildSections(scored, sortMode, nowMillis)
    }

    // Dialog visibility + the loaded estimate are authored live by the screen
    // (they are not in the projection's memo keys), so they are left at their
    // defaults here; the screen overlays them. See MemoryScreen's `.copy(...)`.
    return MemoryViewState(
        visualState = visualState,
        header = buildHeader(nowMillis),
        categoryChips = buildCategoryChips(),
        selectedCategory = selectedCategory,
        sortMode = sortMode,
        dateFilter = dateFilter,
        searchActive = searchActive,
        searchEmpty = searchEmpty(),
        searchQuery = searchQuery,
        sections = sections,
        expandedEntry = expanded?.toDetail(nowMillis, sessionNames),
        errorMessage = null,
    )
}

/** Single source of truth for "a search ran and returned nothing" — used by toViewState and the screen. */
internal fun MemoryUiState.searchEmpty(): Boolean =
    searchActive && searchQuery.isNotBlank() && searchResults?.isEmpty() == true

private fun MemoryUiState.buildHeader(nowMillis: Long): MemoryStatsHeader {
    val total = memories.size
    val auto = memories.count { it.source is MemorySource.ChatSession }
    val manual = memories.count { it.source is MemorySource.Manual }
    val compaction = memories.count { it.source is MemorySource.Compaction }
    val segments = buildList {
        if (auto > 0) add(segment(MemorySourceKind.Auto, "AUTO", auto, total))
        if (compaction > 0) add(segment(MemorySourceKind.Compaction, "COMPACT", compaction, total))
        if (manual > 0) add(segment(MemorySourceKind.Manual, "MANUAL", manual, total))
    }
    return MemoryStatsHeader(
        totalLabel = total.toString(),
        sizeLabel = DisplayFormat.formatBytes(totalBytes),
        lastCompactedLabel = if (lastCompactedAt >
            0L
        ) {
            "compacted ${relativeShort(nowMillis - lastCompactedAt)} ago"
        } else {
            null
        },
        segments = segments,
    )
}

private fun segment(kind: MemorySourceKind, label: String, count: Int, total: Int): MemoryBreakdownSegment {
    val pct = if (total > 0) (count * 100f / total).roundToInt() else 0
    return MemoryBreakdownSegment(
        kind = kind,
        label = "$label $pct %",
        fraction = if (total >
            0
        ) {
            count.toFloat() / total
        } else {
            0f
        },
    )
}

private fun MemoryUiState.buildCategoryChips(): List<MemoryCategoryChip> = listOf(
    MemoryCategoryChip(MemoryCategory.All, memories.size),
    MemoryCategoryChip(MemoryCategory.Pinned, memories.count { it.isPinned }),
    MemoryCategoryChip(MemoryCategory.Auto, memories.count { it.source is MemorySource.ChatSession }),
    MemoryCategoryChip(MemoryCategory.Manual, memories.count { it.source is MemorySource.Manual }),
    MemoryCategoryChip(MemoryCategory.Compaction, memories.count { it.source is MemorySource.Compaction }),
)

private fun buildSections(
    scored: List<Pair<MemoryChunk, Float?>>,
    sortMode: MemorySortMode,
    nowMillis: Long,
): List<MemorySection> {
    // Bucket: Pinned first, then Today / This week / Earlier by timestamp.
    val pinned = scored.filter { it.first.isPinned }
    val rest = scored.filter { !it.first.isPinned }
    val today = rest.filter { nowMillis - it.first.timestamp < DAY_MS }
    val week = rest.filter {
        val age = nowMillis - it.first.timestamp
        age in DAY_MS until WEEK_MS
    }
    val earlier = rest.filter { nowMillis - it.first.timestamp >= WEEK_MS }

    fun List<Pair<MemoryChunk, Float?>>.sorted(): List<Pair<MemoryChunk, Float?>> = when (sortMode) {
        MemorySortMode.Recent -> sortedByDescending { it.first.timestamp }
        MemorySortMode.Relevance -> this // upstream search order preserved
        MemorySortMode.Alphabetical -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.title() })
    }

    return listOf(
        "Pinned" to pinned,
        "Today" to today,
        "This week" to week,
        "Earlier" to earlier,
    ).mapNotNull { (title, bucket) ->
        if (bucket.isEmpty()) {
            null
        } else {
            MemorySection(
                title = title,
                count = bucket.size,
                rows = bucket.sorted().map { it.first.toRow(it.second, nowMillis) },
            )
        }
    }
}

private fun MemoryChunk.toRow(score: Float?, nowMillis: Long): MemoryRow = MemoryRow(
    id = id.toString(),
    title = title(),
    body = text,
    sourceKind = source.toKind(),
    tags = tags,
    relevanceScore = score?.let { String.format(Locale.US, "%.2f", it) },
    timestampLabel = relativeShort(nowMillis - timestamp),
    isPinned = isPinned,
)

private fun MemoryChunk.toDetail(nowMillis: Long, sessionNames: Map<String, String>): MemoryEntryDetail {
    val learnedFrom = (source as? MemorySource.ChatSession)?.sessionId?.let { id ->
        sessionNames[id]?.let { "Chat \"$it\"" }
    }
    val usedIn = if (useCount > 0) {
        val last = lastUsedAt?.let { " · last ${relativeShort(nowMillis - it)} ago" }.orEmpty()
        "$useCount replies$last"
    } else {
        null
    }
    return MemoryEntryDetail(
        id = id.toString(),
        title = title(),
        body = text,
        sourceKind = source.toKind(),
        sourceLabel = source.toLabel(),
        tokenLabel = "${DisplayFormat.approxTokenCount(text).coerceAtLeast(1)} tok",
        tags = tags,
        learnedFromLabel = learnedFrom,
        capturedLabel = formatCaptured(timestamp),
        usedInLabel = usedIn,
        isPinned = isPinned,
    )
}

private fun CompactionEstimate.toView(): CompactionEstimateView = CompactionEstimateView(
    removedLabel = "~$estimatedRemoved",
    freedLabel = "~${DisplayFormat.formatBytes(estimatedFreedBytes)}",
    runtimeLabel = "~$estimatedRuntimeSeconds s",
)

private fun MemoryChunk.title(): String = text.lineSequence().firstOrNull()?.take(MEMORY_TITLE_MAX_CHARS).orEmpty()

private fun MemoryChunk.matchesCategory(category: MemoryCategory): Boolean = when (category) {
    MemoryCategory.All -> true
    MemoryCategory.Pinned -> isPinned
    MemoryCategory.Auto -> source is MemorySource.ChatSession
    MemoryCategory.Manual -> source is MemorySource.Manual
    MemoryCategory.Compaction -> source is MemorySource.Compaction
}

private fun MemoryChunk.matchesDate(filter: MemoryDateFilter, nowMillis: Long): Boolean = when (filter) {
    MemoryDateFilter.All -> true
    MemoryDateFilter.Last7Days -> nowMillis - timestamp < WEEK_MS
    MemoryDateFilter.Last30Days -> nowMillis - timestamp < MONTH_MS
}

private fun MemorySource.toKind(): MemorySourceKind = when (this) {
    is MemorySource.ChatSession -> MemorySourceKind.Auto
    MemorySource.Manual -> MemorySourceKind.Manual
    is MemorySource.Compaction -> MemorySourceKind.Compaction
    MemorySource.Unknown -> MemorySourceKind.Unknown
}

private fun MemorySource.toLabel(): String = when (this) {
    is MemorySource.ChatSession -> "Auto-extracted"
    MemorySource.Manual -> "Saved manually"
    is MemorySource.Compaction -> "Compacted"
    MemorySource.Unknown -> "Unknown"
}

/** Compact relative duration label: `Nm` / `Nh` / `Nd` / `Nw`. */
private fun relativeShort(ageMillis: Long): String {
    val mins = ageMillis / MINUTE_MS
    return when {
        mins < 1 -> "now"
        mins < MINUTES_PER_HOUR -> "$mins m"
        mins < MINUTES_PER_DAY -> "${mins / MINUTES_PER_HOUR} h"
        mins < MINUTES_PER_WEEK -> "${mins / MINUTES_PER_DAY} d"
        else -> "${mins / MINUTES_PER_WEEK} w"
    }
}

/** TestTag applied to the screen root. */
internal const val MEMORY_ROOT_TEST_TAG = "memory_screen_root"

private const val MEMORY_TITLE_MAX_CHARS = 60
private const val MIME_JSON = "application/json"
private const val EXPORT_FILENAME = "memory-base.json"

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 60L * 24
private const val MINUTES_PER_WEEK = 60L * 24 * 7
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7L * DAY_MS
private const val MONTH_MS = 30L * DAY_MS

/** Formats a capture timestamp as `yyyy-MM-dd · HH:mm` in the current locale. */
private fun formatCaptured(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd · HH:mm", Locale.getDefault()).format(Date(timestamp))
