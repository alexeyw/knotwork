package app.knotwork.android.presentation.ui.taskmonitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ScheduledTaskTag
import app.knotwork.android.domain.usecases.CancelScheduledTasksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for monitoring active chat sessions and WorkManager background tasks.
 * Combines data from ChatRepository and WorkManager into a unified UI state.
 */
@HiltViewModel
class TaskMonitorViewModel @Inject constructor(
    chatRepository: ChatRepository,
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    private val taskQueueManager: TaskQueueManager,
    private val cancelScheduledTasks: CancelScheduledTasksUseCase,
) : ViewModel() {

    private val _filter = MutableStateFlow(TaskFilterType.ACTIVE)
    private val _detailTaskId = MutableStateFlow<String?>(value = null)
    private val _confirmingCancelAll = MutableStateFlow(value = false)

    private val workInfosFlow = workManager.getWorkInfosFlow(
        WorkQuery.Builder.fromStates(
            listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED,
                WorkInfo.State.BLOCKED,
                WorkInfo.State.CANCELLED,
            ),
        ).build(),
    )

    /**
     * The unified UI state containing filtered tasks and loading status.
     */
    val uiState: StateFlow<TaskMonitorState> = combine(
        // Archived sessions are included on purpose: a run in flight must not
        // disappear from the monitor because the user archived its chat.
        chatRepository.getSessionsFlow(includeArchived = true),
        workInfosFlow,
        taskQueueManager.activeSessionsState,
        _filter,
        combine(_detailTaskId, _confirmingCancelAll) { detailId, confirming -> detailId to confirming },
    ) { sessions, workInfos, activeSessionsMap, filter, (detailId, confirmingCancelAll) ->
        val sessionTasks = sessions.mapNotNull { session ->
            val orchestratorState = activeSessionsMap[session.id] ?: AgentOrchestratorState.Idle

            // Map Orchestrator State to TaskStatus
            val status = when (orchestratorState) {
                is AgentOrchestratorState.Idle -> TaskStatus.COMPLETED
                is AgentOrchestratorState.Completed -> TaskStatus.COMPLETED
                is AgentOrchestratorState.Error -> TaskStatus.FAILED
                is AgentOrchestratorState.Queued -> TaskStatus.QUEUED
                else -> TaskStatus.RUNNING
            }

            // Map Orchestrator State to Pipeline Stage
            val stage = when (orchestratorState) {
                is AgentOrchestratorState.PipelineStage -> orchestratorState.stepInfo.nodeName
                is AgentOrchestratorState.Thinking -> "Thinking"
                is AgentOrchestratorState.ExecutingTool -> "Tool Execution"
                is AgentOrchestratorState.WaitingForApproval -> "Waiting Approval"
                is AgentOrchestratorState.Loading -> "Loading"
                is AgentOrchestratorState.Answering -> "Answering"
                else -> null
            }

            TaskItem(
                id = session.id,
                title = "Chat Session: ${session.name}",
                status = status,
                progress = null,
                type = TaskType.SESSION,
                pipelineStage = stage,
            )
        }

        val sessionNamesById = sessions.associate { it.id to it.name }
        val workTasks = workInfos.map { info ->
            val stage = info.progress.getString("current_stage")
            val isPassedOutput = stage == "OUTPUT" || stage == "COMPLETED"
            // A queued task's input data is not readable, so everything the row
            // can say about it comes from the tag the scheduler attached.
            val scheduled = ScheduledTaskTag.parse(info.tags)

            TaskItem(
                id = info.id.toString(),
                title = "Background Task (${info.tags.firstOrNull() ?: "AgentWorker"})",
                status = when {
                    isPassedOutput -> TaskStatus.COMPLETED
                    info.state == WorkInfo.State.RUNNING -> TaskStatus.RUNNING
                    info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> TaskStatus.QUEUED
                    info.state == WorkInfo.State.FAILED -> TaskStatus.FAILED
                    info.state == WorkInfo.State.SUCCEEDED ||
                        info.state == WorkInfo.State.CANCELLED -> TaskStatus.COMPLETED
                    else -> TaskStatus.QUEUED
                },
                progress = if (info.state == WorkInfo.State.RUNNING) -1f else 1f,
                type = TaskType.BACKGROUND_WORK,
                pipelineStage = stage,
                scheduled = scheduled,
                boundSessionName = scheduled?.sessionId?.let(sessionNamesById::get),
            )
        }

        val allTasks = sessionTasks + workTasks

        val scheduledCount = workInfos.count {
            ScheduledTaskTag.MARKER in it.tags && it.state in UNFINISHED_STATES
        }

        val filteredTasks = when (filter) {
            TaskFilterType.ALL -> allTasks
            TaskFilterType.ACTIVE -> allTasks.filter {
                it.status == TaskStatus.RUNNING ||
                    // A chat waiting behind another run is still in flight — it used to
                    // read as running, and must not drop out of the default view now
                    // that it says it is queued.
                    (it.type == TaskType.SESSION && it.status == TaskStatus.QUEUED)
            }
            TaskFilterType.BACKGROUND -> allTasks.filter {
                it.type == TaskType.BACKGROUND_WORK &&
                    it.status == TaskStatus.QUEUED
            }
            TaskFilterType.COMPLETED -> allTasks.filter {
                it.status == TaskStatus.COMPLETED ||
                    it.status == TaskStatus.FAILED
            }
        }

        TaskMonitorState(
            tasks = filteredTasks,
            filter = filter,
            isLoading = false,
            detailTaskId = detailId,
            // Counted over every work item, not the filtered list: the escape
            // hatch must be reachable from whichever filter is selected.
            scheduledTaskCount = scheduledCount,
            // Derived, not just mirrored: the last scheduled task can finish
            // while the confirmation is open, and a dialog offering to stop
            // nothing (or worse, one that reappears when an unrelated task is
            // scheduled later) would be asking about a premise that is gone.
            confirmingCancelAll = confirmingCancelAll && scheduledCount > 0,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = TaskMonitorState(),
    )

    /**
     * Updates the current filter for the task list.
     *
     * @param newFilter The new [TaskFilterType] to apply.
     */
    fun onFilterChanged(newFilter: TaskFilterType) {
        _filter.value = newFilter
    }

    /**
     * Cancels a specific WorkManager task by its ID.
     *
     * @param taskId The UUID string of the work request.
     */
    fun onCancelTaskClicked(taskId: String) {
        try {
            workManager.cancelWorkById(UUID.fromString(taskId))
        } catch (e: IllegalArgumentException) {
            // Ignored: Invalid UUID format
        }
    }

    /** Stages the "stop all scheduled tasks" confirmation. */
    fun onCancelAllScheduledClicked() {
        _confirmingCancelAll.value = true
    }

    /** Dismisses the confirmation without cancelling anything. */
    fun onCancelAllScheduledDismissed() {
        _confirmingCancelAll.value = false
    }

    /**
     * Cancels every task the agent scheduled for itself.
     *
     * The user's way out of a task that keeps re-scheduling itself: cancelling
     * the one queued item by hand does not help while the run that will enqueue
     * the next one is still executing, and before this the only remaining option
     * was clearing the app's data.
     */
    fun onCancelAllScheduledConfirmed() {
        _confirmingCancelAll.value = false
        cancelScheduledTasks()
    }

    /**
     * Sets the current chat session before navigating.
     *
     * @param sessionId The ID of the chat session to open.
     * @param onComplete Callback invoked when the session is set.
     */
    fun onOpenChatClicked(sessionId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setCurrentChatSessionId(sessionId)
            onComplete()
        }
    }

    /** Opens the row-detail bottom sheet for the given task id. */
    fun openDetails(taskId: String) {
        _detailTaskId.value = taskId
    }

    /** Closes the row-detail bottom sheet without affecting task state. */
    fun closeDetails() {
        _detailTaskId.value = null
    }

    private companion object {
        /**
         * Grace period in milliseconds the upstream flow keeps running after the
         * last subscriber detaches; protects the chain against quick navigation
         * round-trips that would otherwise tear it down and re-build it.
         */
        const val STATE_STOP_TIMEOUT_MS: Long = 5_000L

        /**
         * Work states that still have a future: a scheduled task in one of these
         * is something "stop all scheduled tasks" can actually settle, so only
         * these are counted towards offering that action.
         */
        val UNFINISHED_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
        )
    }
}
