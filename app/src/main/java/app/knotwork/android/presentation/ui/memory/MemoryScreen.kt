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
import app.knotwork.android.domain.models.MemoryVersion
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
import app.knotwork.design.screens.memory.MemoryPairRole
import app.knotwork.design.screens.memory.MemoryPairView
import app.knotwork.design.screens.memory.MemoryRow
import app.knotwork.design.screens.memory.MemorySection
import app.knotwork.design.screens.memory.MemorySortMode
import app.knotwork.design.screens.memory.MemorySourceKind
import app.knotwork.design.screens.memory.MemoryStatsHeader
import app.knotwork.design.screens.memory.MemoryVersionView
import app.knotwork.design.screens.memory.MemoryViewState
import app.knotwork.design.screens.memory.MemoryVisualState
import java.text.DateFormat
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
        onPairUse = { id -> id.toLongOrNull()?.let(viewModel.history::useUpdate) },
        onPairKeep = { id -> id.toLongOrNull()?.let(viewModel.history::keepPinned) },
        onVersionDelete = { id -> id.toLongOrNull()?.let(viewModel.history::deleteVersion) },
    )

    MemoryScreenContent(
        uiState = uiState,
        callbacks = callbacks,
        snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
        nowMillis = System.currentTimeMillis(),
    )
}

/**
 * The Memory screen for a given state, without its ViewModel: the words, the list
 * projection, the dialog overlays and [MemoryContent].
 *
 * @param uiState The state to show.
 * @param callbacks The screen's actions.
 * @param snackbarHost The host messages appear in, lifted above the add button.
 * @param nowMillis The moment ages and date sections are measured from.
 */
@Composable
internal fun MemoryScreenContent(
    uiState: MemoryUiState,
    callbacks: MemoryCallbacks,
    snackbarHost: @Composable () -> Unit,
    nowMillis: Long,
) {
    // The expensive list projection (filter / group / sort / breakdown / chip
    // counts) is memoised only on the fields that affect it, so search
    // keystrokes and dialog-visibility toggles don't re-run the O(N) work.
    val texts = rememberMemoryTexts()
    val listViewState = remember(
        texts,
        uiState.memories,
        uiState.history,
        uiState.pendingUpdates,
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
        uiState.toViewState(nowMillis = nowMillis, texts = texts)
    }
    // Dialog visibility + the loaded estimate are intentionally excluded from
    // the memo keys (toggling them must not re-run the O(N) projection); they
    // are overlaid here cheaply on every recomposition.
    val viewState = listViewState.copy(
        compactDialogVisible = uiState.compactDialogVisible,
        compactEstimate = uiState.compactEstimate?.toView(texts),
        addDialogVisible = uiState.addDialogVisible,
    )

    Box(modifier = Modifier.fillMaxSize().testTag(tag = MEMORY_ROOT_TEST_TAG)) {
        // In the content's Scaffold, which lifts it above the "Add memory" FAB.
        MemoryContent(
            state = viewState,
            callbacks = callbacks,
            snackbarHost = snackbarHost,
        )
    }
}

/**
 * Pure projection of [MemoryUiState] onto the catalog [MemoryViewState].
 * `internal` + `nowMillis` so the grouping / breakdown / labelling is unit-testable;
 * every word it writes comes from [texts].
 */
internal fun MemoryUiState.toViewState(nowMillis: Long, texts: MemoryTexts): MemoryViewState {
    val expanded = expandedId?.let { id -> memories.firstOrNull { it.id == id } }
    val visualState = when {
        loadFailed -> MemoryVisualState.Error
        expanded != null && editing -> MemoryVisualState.Editing
        expanded != null -> MemoryVisualState.EntryExpanded
        memories.isEmpty() -> MemoryVisualState.Empty
        searchActive -> MemoryVisualState.Searching
        else -> MemoryVisualState.Populated
    }

    val facts = rowFacts()
    val sections = if (searchActive) {
        // Search results are a single relevance-ordered list (the order
        // retrieveScored returns) — no time bucketing, which would scatter the
        // ranking the search header advertises. Blank title = no section header.
        val hits = searchResults ?: emptyList()
        if (hits.isEmpty()) {
            emptyList()
        } else {
            val rows = facts.withUpdatesUnderPinned(hits) { it.first.id }.map { (hit, child) ->
                hit.first.toRow(hit.second, nowMillis, facts, child, texts)
            }
            listOf(MemorySection(title = "", count = rows.count { !it.pairChild }, rows = rows))
        }
    } else {
        val filtered = memories
            .filter { it.matchesCategory(selectedCategory) }
            .filter { it.matchesDate(dateFilter, nowMillis) }
        buildSections(filtered.map { it to null as Float? }, sortMode, nowMillis, facts, selectedCategory, texts)
    }

    // Dialog visibility + the loaded estimate are authored live by the screen
    // (they are not in the projection's memo keys), so they are left at their
    // defaults here; the screen overlays them. See MemoryScreen's `.copy(...)`.
    return MemoryViewState(
        visualState = visualState,
        header = buildHeader(nowMillis, texts),
        categoryChips = buildCategoryChips(),
        selectedCategory = selectedCategory,
        sortMode = sortMode,
        dateFilter = dateFilter,
        searchActive = searchActive,
        searchEmpty = searchEmpty(),
        searchQuery = searchQuery,
        sections = sections,
        expandedEntry = expanded?.toDetail(nowMillis, sessionNames, facts, texts),
        errorMessage = null,
    )
}

/** Single source of truth for "a search ran and returned nothing" — used by toViewState and the screen. */
internal fun MemoryUiState.searchEmpty(): Boolean =
    searchActive && searchQuery.isNotBlank() && searchResults?.isEmpty() == true

private fun MemoryUiState.buildHeader(nowMillis: Long, texts: MemoryTexts): MemoryStatsHeader {
    val total = memories.size
    val auto = memories.count { it.source is MemorySource.ChatSession }
    val manual = memories.count { it.source is MemorySource.Manual }
    val compaction = memories.count { it.source is MemorySource.Compaction }
    val segments = buildList {
        if (auto > 0) add(segment(MemorySourceKind.Auto, texts.segmentAuto, auto, total))
        if (compaction > 0) add(segment(MemorySourceKind.Compaction, texts.segmentCompaction, compaction, total))
        if (manual > 0) add(segment(MemorySourceKind.Manual, texts.segmentManual, manual, total))
    }
    return MemoryStatsHeader(
        totalLabel = total.toString(),
        sizeLabel = DisplayFormat.formatBytes(totalBytes, texts.sizes),
        lastCompactedLabel = if (lastCompactedAt >
            0L
        ) {
            val age = nowMillis - lastCompactedAt
            if (texts.ages.isNow(age)) texts.compactedJustNow else texts.compactedAgo.format(texts.ages.short(age))
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
    facts: MemoryRowFacts,
    category: MemoryCategory,
    texts: MemoryTexts,
): List<MemorySection> {
    // A waiting update is never sorted on its own: it is drawn under its pinned entry,
    // wherever that entry lands. It stays a row of its own only when its pinned entry
    // is filtered out — or when a filter shows the pinned entry and not the update,
    // except Pinned, which shows the update under its pinned entry anyway.
    val visible = scored.mapTo(HashSet()) { it.first.id }
    val attached = facts.pinnedToUpdate.filter { (pinnedId, update) ->
        pinnedId in visible && (update.id in visible || category == MemoryCategory.Pinned)
    }
    val attachedIds = attached.values.mapTo(HashSet()) { it.id }
    val topLevel = scored.filter { it.first.id !in attachedIds }

    // Bucket: Pinned first, then Today / This week / Earlier by timestamp.
    val pinned = topLevel.filter { it.first.isPinned }
    val rest = topLevel.filter { !it.first.isPinned }
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
        texts.sectionPinned to pinned,
        texts.sectionToday to today,
        texts.sectionThisWeek to week,
        texts.sectionEarlier to earlier,
    ).mapNotNull { (title, bucket) ->
        if (bucket.isEmpty()) {
            null
        } else {
            MemorySection(
                title = title,
                count = bucket.size,
                rows = bucket.sorted().flatMap { (chunk, score) ->
                    listOfNotNull(
                        chunk.toRow(score, nowMillis, facts, child = false, texts),
                        attached[chunk.id]?.toRow(null, nowMillis, facts, child = true, texts),
                    )
                },
            )
        }
    }
}

/**
 * What a row needs to know beyond its own chunk: how many earlier versions each entry
 * keeps and which entries form a pair.
 *
 * @property historyCounts Earlier versions by chunk id.
 * @property pinnedToUpdate Each pinned chunk id with the chunk of its waiting update.
 * @property updateToPinned Each waiting update's id with the chunk it updates.
 */
private class MemoryRowFacts(
    val historyCounts: Map<Long, Int>,
    val pinnedToUpdate: Map<Long, MemoryChunk>,
    val updateToPinned: Map<Long, MemoryChunk>,
    val history: Map<Long, List<MemoryVersion>>,
) {
    /** The pair role of the chunk with [id], or `null`. */
    fun roleOf(id: Long): MemoryPairRole? = when (id) {
        in pinnedToUpdate -> MemoryPairRole.PinnedWithUpdate
        in updateToPinned -> MemoryPairRole.UpdateOfPinned
        else -> null
    }

    /**
     * Orders [items] (already ranked) so a waiting update follows its pinned entry
     * when both are present, at the pinned entry's position; a half without its
     * partner keeps its own place.
     *
     * @return Each item with `true` when it is drawn as a child under its pinned entry.
     */
    fun <T> withUpdatesUnderPinned(items: List<T>, idOf: (T) -> Long): List<Pair<T, Boolean>> {
        val byId = items.associateBy(idOf)
        return items.flatMap { item ->
            val id = idOf(item)
            val pinnedId = updateToPinned[id]?.id
            when {
                pinnedId != null && pinnedId in byId -> emptyList()
                else -> listOfNotNull(
                    item to false,
                    pinnedToUpdate[id]?.id?.let { updateId -> byId[updateId]?.let { it to true } },
                )
            }
        }
    }
}

private fun MemoryUiState.rowFacts(): MemoryRowFacts {
    val byId = memories.associateBy { it.id }
    val links = pendingUpdates.mapNotNull { link ->
        val pinned = byId[link.pinnedChunkId]
        val update = byId[link.updateChunkId]
        if (pinned != null && update != null) pinned to update else null
    }
    return MemoryRowFacts(
        historyCounts = history.mapValues { it.value.size },
        pinnedToUpdate = links.associate { (pinned, update) -> pinned.id to update },
        updateToPinned = links.associate { (pinned, update) -> update.id to pinned },
        history = history,
    )
}

private fun MemoryChunk.toRow(
    score: Float?,
    nowMillis: Long,
    facts: MemoryRowFacts,
    child: Boolean,
    texts: MemoryTexts,
): MemoryRow = MemoryRow(
    id = id.toString(),
    title = title(),
    body = text,
    sourceKind = source.toKind(),
    tags = tags,
    relevanceScore = score?.let { String.format(texts.locale, "%.2f", it) },
    timestampLabel = texts.ages.short(nowMillis - timestamp),
    isPinned = isPinned,
    historyCount = facts.historyCounts[id] ?: 0,
    pairRole = facts.roleOf(id),
    pairChild = child,
)

private fun MemoryChunk.toDetail(
    nowMillis: Long,
    sessionNames: Map<String, String>,
    facts: MemoryRowFacts,
    texts: MemoryTexts,
): MemoryEntryDetail {
    val learnedFrom = (source as? MemorySource.ChatSession)?.sessionId?.let { id ->
        sessionNames[id]?.let { texts.learnedFromChat.format(it) }
    }
    // The counter goes up once per run whose memory block received the chunk —
    // runs, not replies: the model may not have used it.
    val usedIn = if (useCount > 0) {
        val last = lastUsedAt?.let {
            val age = nowMillis - it
            " · " +
                if (texts.ages.isNow(age)) texts.lastUsedJustNow else texts.lastUsedAgo.format(texts.ages.short(age))
        }.orEmpty()
        texts.usedInRuns(useCount) + last
    } else {
        null
    }
    val role = facts.roleOf(id)
    val other = when (role) {
        MemoryPairRole.PinnedWithUpdate -> facts.pinnedToUpdate[id]
        MemoryPairRole.UpdateOfPinned -> facts.updateToPinned[id]
        null -> null
    }
    return MemoryEntryDetail(
        id = id.toString(),
        title = title(),
        body = text,
        sourceKind = source.toKind(),
        sourceLabel = source.toLabel(texts),
        tokenLabel = texts.tokens.format(DisplayFormat.approxTokenCount(text).coerceAtLeast(1)),
        tags = tags,
        learnedFromLabel = learnedFrom,
        capturedLabel = formatCaptured(timestamp),
        usedInLabel = usedIn,
        isPinned = isPinned,
        history = facts.history[id].orEmpty().map { version ->
            MemoryVersionView(
                id = version.id.toString(),
                text = version.text,
                sourceKind = version.source.toKind(),
                learnedFrom = version.source.chatName(sessionNames),
                capturedLabel = formatDate(version.capturedAt),
                replacedLabel = formatDate(version.replacedAt),
            )
        },
        pair = if (role != null && other != null) {
            MemoryPairView(
                role = role,
                otherText = other.text,
                otherSourceKind = other.source.toKind(),
                otherLearnedFrom = other.source.chatName(sessionNames),
                otherCapturedLabel = formatDate(other.timestamp),
            )
        } else {
            null
        },
    )
}

/** Name of the chat a [MemorySource.ChatSession] came from, when that chat still exists. */
private fun MemorySource.chatName(sessionNames: Map<String, String>): String? =
    (this as? MemorySource.ChatSession)?.sessionId?.let(sessionNames::get)

private fun CompactionEstimate.toView(texts: MemoryTexts): CompactionEstimateView = CompactionEstimateView(
    removedLabel = "~$estimatedRemoved",
    freedLabel = "~${DisplayFormat.formatBytes(estimatedFreedBytes, texts.sizes)}",
    runtimeLabel = texts.secondsApprox.format(estimatedRuntimeSeconds),
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

private fun MemorySource.toLabel(texts: MemoryTexts): String = when (this) {
    is MemorySource.ChatSession -> texts.sourceAuto
    MemorySource.Manual -> texts.sourceManual
    is MemorySource.Compaction -> texts.sourceCompaction
    MemorySource.Unknown -> texts.sourceUnknown
}

/** TestTag applied to the screen root. */
internal const val MEMORY_ROOT_TEST_TAG = "memory_screen_root"

private const val MEMORY_TITLE_MAX_CHARS = 60
private const val MIME_JSON = "application/json"
private const val EXPORT_FILENAME = "memory-base.json"

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7L * DAY_MS
private const val MONTH_MS = 30L * DAY_MS

/** Formats a date in the locale's medium style (`2 Oct 2026`), as earlier versions show it. */
private fun formatDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timestamp))

/** Formats a capture timestamp as `yyyy-MM-dd · HH:mm` in the current locale. */
private fun formatCaptured(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd · HH:mm", Locale.getDefault()).format(Date(timestamp))
