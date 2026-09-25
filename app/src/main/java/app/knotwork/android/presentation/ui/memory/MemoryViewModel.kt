package app.knotwork.android.presentation.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.usecases.EstimateCompactionUseCase
import app.knotwork.android.domain.usecases.ExportMemoryBaseUseCase
import app.knotwork.android.domain.usecases.MemoryCompactionUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import app.knotwork.android.domain.usecases.SaveMessageToMemoryUseCase
import app.knotwork.android.domain.usecases.SaveToMemoryOutcome
import app.knotwork.design.screens.memory.MemoryCategory
import app.knotwork.design.screens.memory.MemoryDateFilter
import app.knotwork.design.screens.memory.MemorySortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.OutputStream
import javax.inject.Inject

/**
 * ViewModel for the redesigned long-term-memory screen.
 *
 * Owns the loaded chunk list, the view selections (category / sort / date /
 * semantic search), inline edit + tag editing, manual add, manual compaction
 * (with a pre-run estimate), pin toggling, and full export. The screen maps the
 * exposed [MemoryUiState] to the catalog surface.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val exportMemoryBaseUseCase: ExportMemoryBaseUseCase,
    private val saveMessageToMemoryUseCase: SaveMessageToMemoryUseCase,
    private val memoryCompactionUseCase: MemoryCompactionUseCase,
    private val estimateCompactionUseCase: EstimateCompactionUseCase,
    private val retrieveRelevantMemoryUseCase: RetrieveRelevantMemoryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())

    /** The current UI state of the Memory screen. */
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    private val _exportRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One-shot signal asking the screen to open the SAF document picker for full export. */
    val exportRequests: SharedFlow<Unit> = _exportRequests.asSharedFlow()

    private val _messageEvents = MutableSharedFlow<MemoryMessage>(extraBufferCapacity = 1)

    /**
     * One-shot snackbar events. The screen resolves each [MemoryMessage] to a
     * localised string and shows it — keeping user-facing copy in the resource
     * layer rather than the ViewModel.
     */
    val messageEvents: SharedFlow<MemoryMessage> = _messageEvents.asSharedFlow()

    private var searchJob: Job? = null

    init {
        loadAllData()
    }

    /**
     * Loads the surface from scratch (init / explicit Retry). A read failure
     * surfaces the full-screen Error/Retry state.
     */
    fun loadAllData() = load(surfaceError = true)

    /**
     * Silent post-mutation refresh: re-reads the table after a delete / pin /
     * edit / compaction / add. A transient read failure here must NOT flip the
     * whole screen to Error (that would mask a mutation that already
     * succeeded) — it just logs and leaves the current list in place.
     */
    private fun refresh() = load(surfaceError = false)

    private fun load(surfaceError: Boolean) {
        viewModelScope.launch {
            try {
                val memories = memoryRepository.getAllMemories()
                val totalBytes = memoryRepository.observeStats().first().totalBytes
                val lastCompactedAt = settingsRepository.memoryLastCompactedAt.first()
                val sessionNames =
                    resolveSessionNames(memories.mapNotNull { (it.source as? MemorySource.ChatSession)?.sessionId })
                _uiState.update {
                    it.copy(
                        memories = memories,
                        totalBytes = totalBytes,
                        lastCompactedAt = lastCompactedAt,
                        sessionNames = sessionNames,
                        loadFailed = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load memory")
                if (surfaceError) {
                    _uiState.update { it.copy(loadFailed = true) }
                } else {
                    _messageEvents.tryEmit(MemoryMessage.LoadError)
                }
            }
        }
    }

    private suspend fun resolveSessionNames(ids: List<String>): Map<String, String> = ids.distinct().mapNotNull { id ->
        chatRepository.getSessionById(id)?.let { id to it.name }
    }.toMap()

    /** Selects a category chip. */
    fun selectCategory(category: MemoryCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /** Sets the sort mode. */
    fun setSortMode(mode: MemorySortMode) {
        _uiState.update { it.copy(sortMode = mode) }
    }

    /** Sets the date-range filter. */
    fun setDateFilter(filter: MemoryDateFilter) {
        _uiState.update { it.copy(dateFilter = filter) }
    }

    /** Opens the semantic-search field. */
    fun openSearch() {
        _uiState.update { it.copy(searchActive = true, sortMode = MemorySortMode.Relevance) }
    }

    /** Closes search and clears the query/results. */
    fun closeSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchActive = false, searchQuery = "", searchResults = null, sortMode = MemorySortMode.Recent)
        }
    }

    /**
     * Updates the search query and (debounced) runs a semantic search. The
     * user's configured relevance threshold is honoured (passing `null` lets
     * the use case read `memorySearchThreshold`) so results stay relevant
     * rather than returning the whole pool; a larger [SEARCH_RESULT_LIMIT] just
     * widens how many of those relevant hits the browse surface shows.
     */
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = try {
                retrieveRelevantMemoryUseCase.retrieveScored(query = query, limit = SEARCH_RESULT_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Semantic memory search failed")
                emptyList()
            }
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    /** Opens the detail sheet for [id] (read mode). */
    fun openEntry(id: Long) {
        _uiState.update { it.copy(expandedId = id, editing = false) }
    }

    /** Closes the detail sheet. */
    fun closeEntry() {
        _uiState.update { it.copy(expandedId = null, editing = false) }
    }

    /** Switches the open detail sheet to edit mode. */
    fun editEntry(id: Long) {
        _uiState.update { it.copy(expandedId = id, editing = true) }
    }

    /** Leaves edit mode, back to read mode. */
    fun cancelEdit() {
        _uiState.update { it.copy(editing = false) }
    }

    /**
     * Commits an edit: re-embeds [body] with the active provider, then persists
     * the text, embedding, and tags **atomically** (one transaction) so the
     * write can never half-apply. On a re-embed or persistence failure the
     * chunk is left untouched and the **edit sheet stays open with the draft
     * intact** (a snackbar explains the failure), so a recoverable error — e.g.
     * an offline cloud provider — is one tap away from retry instead of
     * discarding the user's typing.
     */
    fun commitEdit(id: Long, body: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                val embedding = embeddingProviderResolver.resolve().embed(body)
                memoryRepository.updateMemoryWithTags(id = id, text = body, embedding = embedding, tags = tags)
                _uiState.update { it.copy(editing = false, expandedId = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the sheet open in edit mode so the draft survives.
                Timber.w(e, "Failed to commit memory edit for %s", id)
                _messageEvents.tryEmit(MemoryMessage.EditError)
            }
        }
    }

    /** Deletes the entry and closes the sheet. */
    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
            _uiState.update { it.copy(expandedId = null, editing = false) }
            refresh()
        }
    }

    /** Toggles the pinned flag of [id]. */
    fun togglePin(id: Long) {
        viewModelScope.launch {
            val current = _uiState.value.memories.firstOrNull { it.id == id } ?: return@launch
            memoryRepository.setMemoryPinned(id = id, pinned = !current.isPinned)
            refresh()
        }
    }

    /** Opens the Compact confirm dialog and loads the estimate. */
    fun showCompactDialog() {
        _uiState.update { it.copy(compactDialogVisible = true, compactEstimate = null) }
        viewModelScope.launch {
            val estimate = try {
                estimateCompactionUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            _uiState.update { it.copy(compactEstimate = estimate) }
        }
    }

    /** Dismisses the Compact dialog. */
    fun dismissCompactDialog() {
        _uiState.update { it.copy(compactDialogVisible = false) }
    }

    /** Runs the real compaction pass, then refreshes. */
    fun confirmCompact() {
        _uiState.update { it.copy(compactDialogVisible = false) }
        viewModelScope.launch {
            try {
                memoryCompactionUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Manual compaction failed")
            }
            refresh()
        }
    }

    /** Opens the Add-memory dialog. */
    fun showAddDialog() {
        _uiState.update { it.copy(addDialogVisible = true) }
    }

    /** Dismisses the Add-memory dialog. */
    fun dismissAddDialog() {
        _uiState.update { it.copy(addDialogVisible = false) }
    }

    /**
     * Saves a manual memory entry from [text]. The Add dialog stays open until
     * the save resolves: on success it closes and the surface refreshes; on a
     * Failed outcome (e.g. an offline cloud embedding provider) it **stays open
     * with the typed text intact** and a snackbar explains the failure, so the
     * user can retry without re-typing. A blank input (Skipped) just closes.
     */
    fun confirmAdd(text: String) {
        viewModelScope.launch {
            when (saveMessageToMemoryUseCase(text)) {
                is SaveToMemoryOutcome.Saved -> {
                    _uiState.update { it.copy(addDialogVisible = false) }
                    refresh()
                }
                is SaveToMemoryOutcome.Failed -> _messageEvents.tryEmit(MemoryMessage.AddError)
                SaveToMemoryOutcome.Skipped -> _uiState.update { it.copy(addDialogVisible = false) }
            }
        }
    }

    /** Raises [exportRequests] so the screen opens the SAF picker for a full export. */
    fun requestExportAll() {
        _exportRequests.tryEmit(Unit)
    }

    /** Serialises every chunk into the SAF-provided [target]. */
    fun exportAllTo(target: OutputStream) {
        viewModelScope.launch {
            try {
                exportMemoryBaseUseCase(target = target, ids = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Memory export failed")
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_RESULT_LIMIT = 50
    }
}

/**
 * One-shot snackbar messages the Memory screen resolves to localised strings.
 * Modelled as an enum so user-facing copy lives in the resource layer, not the
 * ViewModel.
 */
enum class MemoryMessage {
    /** A background refresh after a mutation failed (the mutation itself succeeded). */
    LoadError,

    /** A manual "Add memory" save failed (e.g. an offline cloud embedding provider). */
    AddError,

    /** An inline edit failed to persist (the draft is kept in the open sheet). */
    EditError,
}
