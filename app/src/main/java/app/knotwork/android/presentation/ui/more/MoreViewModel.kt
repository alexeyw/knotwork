package app.knotwork.android.presentation.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.BuildConfig
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MemoryStats
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.WorkspaceListing
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.models.isBusy
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.usecases.workspace.ListWorkspaceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Aggregates the live counters surfaced on the More tab.
 *
 * The screen itself is mostly navigation — but the per-row subtitles
 * (memory chunks count, active model name, active-task count, etc.) are
 * live so the user can tell at a glance whether anything is running.
 *
 * Re-emission frequency is throttled at the source (each upstream flow
 * only emits on real change), so the combined flow stays cheap.
 */
@HiltViewModel
class MoreViewModel @Inject constructor(
    memoryRepository: MemoryRepository,
    private val localModelRepository: LocalModelRepository,
    private val promptPresetRepository: PromptPresetRepository,
    private val taskQueueManager: TaskQueueManager,
    private val networkActivityTracker: NetworkActivityTracker,
    pipelinePresetRepository: PipelinePresetRepository,
    private val listWorkspaceUseCase: ListWorkspaceUseCase,
    chatRepository: ChatRepository,
) : ViewModel() {

    val uiState: StateFlow<MoreUiState> = combine(
        memoryRepository.observeStats(),
        localModelRepository.getAllModels(),
        // Combined bundled + user catalogue — mirrors what the Prompt
        // Library screen actually renders so the subtitle counter is
        // consistent with the screen the row links to.
        combine(
            promptPresetRepository.getBundledPresets(),
            promptPresetRepository.getUserPresets(),
        ) { bundled, user -> bundled + user },
        taskQueueManager.activeSessionsState,
        networkActivityTracker.lastOutboundAt,
        // Independent wall-clock ticker so the footer privacy pill
        // transitions (`online · network call just now` → `on-device · no
        // network calls in last N m` → minute-increment) fire even
        // when no upstream data flow emits. Without this tick the
        // status text would stay stuck on whatever it was at the last
        // memory / model / prompt / task / network-activity emission.
        statusTicker(),
        pipelinePresetRepository.getUserPresets(),
        workspaceSummary(),
        chatRepository.getArchivedSessionsFlow(),
    ) { values -> reduceUiState(values = values) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = MoreUiState(),
    )

    /**
     * Reduces the positional values produced by [combine] into a
     * [MoreUiState]. Pulled out into a dedicated function so the
     * unchecked-cast suppressions for each positional slot stay
     * confined to a single tight scope (and so the `combine` lambda
     * itself stays readable).
     */
    @Suppress("UNCHECKED_CAST", "MagicNumber")
    private fun reduceUiState(values: Array<Any?>): MoreUiState {
        val memStats = values[0] as MemoryStats
        val models = values[1] as List<LocalModel>
        val prompts = values[2] as List<PromptPreset>
        val activeSessions = values[3] as Map<*, AgentOrchestratorState>
        val lastNetwork = values[4] as Long?
        val now = values[5] as Long
        val userPresets = values[6] as List<PipelinePreset>
        val workspace = values[7] as WorkspaceResult<WorkspaceListing>
        val archivedChats = values[8] as List<ChatSession>
        val active = models.firstOrNull { it.isActive }
        // Every in-flight status, as the task monitor lists it — not just the two
        // streaming ones, which made a run spent loading, in a tool or on a stage count as
        // nothing. A queued run is counted separately below.
        val runningCount = activeSessions.values.count {
            it.isBusy && it !is AgentOrchestratorState.Queued
        }
        // Only a run actually waiting behind another one. `Idle` is the resting state
        // of every chat the queue has seen, which read as "queued" before the queue
        // could say which session was waiting.
        val queuedCount = activeSessions.values.count { it is AgentOrchestratorState.Queued }
        return MoreUiState(
            memory = MoreMemoryCount(chunks = memStats.chunkCount, bytes = memStats.totalBytes),
            activeModelName = active?.name,
            modelsLoaded = true,
            // Categories = distinct `NodeType`s present in the catalogue.
            // The Library renders all LLM-driven node types as tabs and shows
            // bundled + user presets under each, so this is the same shape.
            prompts = MorePromptCount(categories = prompts.map { it.nodeType }.distinct().size, prompts = prompts.size),
            tasks = MoreTaskCount(running = runningCount, queued = queuedCount),
            tasksBadge = runningCount,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            savedPresets = userPresets.size,
            files = workspaceStat(workspace),
            archivedChats = archivedChats.size,
            network = networkStat(now, lastNetwork),
        )
    }

    /**
     * Wall-clock heartbeat used as an independent input to [uiState]'s
     * `combine`. Emits the current epoch-ms immediately on subscription
     * and again every [STATUS_TICK_INTERVAL_MS], so the `networkStatus`
     * formatter — which is a pure function of `(now, lastOutboundAt)` —
     * re-runs at least once per minute even when no upstream data flow
     * fires.
     *
     * `SharingStarted.WhileSubscribed` (configured on the resulting
     * `stateIn`) guarantees this ticker stops as soon as the UI
     * unsubscribes, so a backgrounded More tab does not burn cycles.
     */
    private fun statusTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(STATUS_TICK_INTERVAL_MS)
        }
    }

    /**
     * One-shot workspace listing used for the Files row subtitle. Emits a single
     * snapshot on subscription; because `stateIn` uses
     * `SharingStarted.WhileSubscribed`, re-entering the More tab re-subscribes
     * and recomputes a fresh count, which is timely enough for a subtitle.
     */
    private fun workspaceSummary(): Flow<WorkspaceResult<WorkspaceListing>> = flow { emit(listWorkspaceUseCase()) }

    private companion object {
        /**
         * Outbound-call timestamps younger than this window flip the footer
         * indicator from `on-device` (green) to `online · network call just now`
         * (warn). 60 s ≈ "in this session".
         */
        const val FRESH_NETWORK_WINDOW_MS: Long = 60_000L

        /**
         * Ticker period for the wall-clock heartbeat that drives the
         * footer privacy-pill transitions. 60 s matches the minute
         * granularity of the rendered text ("in last N m").
         */
        const val STATUS_TICK_INTERVAL_MS: Long = 60_000L

        /** WhileSubscribed grace period — see TaskMonitorViewModel. */
        const val STATE_STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * The workspace listing as the Files row's counter.
 *
 * @param result The one-shot workspace listing.
 * @return Unavailable on failure, Empty with no files, else the count and size.
 */
internal fun workspaceStat(result: WorkspaceResult<WorkspaceListing>): MoreWorkspaceStat = when (result) {
    is WorkspaceResult.Failure -> MoreWorkspaceStat.Unavailable
    is WorkspaceResult.Success -> if (result.value.files.isEmpty()) {
        MoreWorkspaceStat.Empty
    } else {
        MoreWorkspaceStat.Files(count = result.value.files.size, bytes = result.value.usage.usedBytes)
    }
}

/**
 * When the app last reached the network, from the [lastOutboundAt] timestamp
 * emitted by [NetworkActivityTracker].
 *
 * - `null` → [MoreNetworkStat.NoCallsYet]
 * - within the last 60 s → [MoreNetworkStat.JustNow]
 * - older → [MoreNetworkStat.Quiet] with the whole minutes since
 *
 * @param now epoch milliseconds at which the status is computed.
 * @param lastOutboundAt epoch milliseconds of the most recent outbound
 * call, or `null` if none has been recorded since process start.
 */
internal fun networkStat(now: Long, lastOutboundAt: Long?): MoreNetworkStat {
    if (lastOutboundAt == null) return MoreNetworkStat.NoCallsYet
    val elapsedMs = (now - lastOutboundAt).coerceAtLeast(minimumValue = 0L)
    return if (elapsedMs < FRESH_WINDOW_MS) {
        MoreNetworkStat.JustNow
    } else {
        MoreNetworkStat.Quiet(minutes = elapsedMs / MS_PER_MINUTE)
    }
}

private const val FRESH_WINDOW_MS = 60_000L
private const val MS_PER_MINUTE = 60_000L
