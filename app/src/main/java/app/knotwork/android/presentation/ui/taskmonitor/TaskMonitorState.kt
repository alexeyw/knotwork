package app.knotwork.android.presentation.ui.taskmonitor

import app.knotwork.android.domain.services.ScheduledTaskLabel
import app.knotwork.android.presentation.ui.common.UiText

/**
 * Represents the type of a task in the monitoring screen.
 */
enum class TaskType {
    SESSION,
    BACKGROUND_WORK,
}

/**
 * Represents the current status of a task.
 */
enum class TaskStatus {
    RUNNING,
    QUEUED,
    FAILED,
    COMPLETED,
}

/**
 * Filter types for the task monitoring list.
 *
 * @property displayNameRes Resource id of the translated chip label rendered
 *   in the filter row. Resolved by the composable that draws the chip via
 *   `stringResource(...)` so the enum stays Android-resource-free.
 */
enum class TaskFilterType(@androidx.annotation.StringRes val displayNameRes: Int) {
    ALL(app.knotwork.android.R.string.taskmonitor_filter_all),
    ACTIVE(app.knotwork.android.R.string.taskmonitor_filter_active),
    BACKGROUND(app.knotwork.android.R.string.taskmonitor_filter_background),
    COMPLETED(app.knotwork.android.R.string.taskmonitor_filter_completed),
}

/**
 * Data model for a single task card.
 *
 * @property id Unique identifier for the task or session.
 * @property title The display title, resolved by the screen in the interface language.
 * @property status The current execution status.
 * @property progress The progress from 0 to 1, or null if indeterminate/not applicable.
 * @property type The type of task (session vs background work).
 * @property pipelineStage The current stage of the pipeline if applicable — a node's
 *   name as the user wrote it, or a stage the app names.
 */
data class TaskItem(
    val id: String,
    val title: UiText,
    val status: TaskStatus,
    val progress: Float?,
    val type: TaskType,
    val pipelineStage: UiText? = null,
    val scheduled: ScheduledTaskLabel? = null,
    val boundSessionName: String? = null,
)

/**
 * UI State for the Task Monitor screen.
 *
 * @property tasks The filtered list of tasks to display.
 * @property filter The currently selected filter.
 * @property isLoading True if initial data is still loading.
 */
data class TaskMonitorState(
    val tasks: List<TaskItem> = emptyList(),
    val filter: TaskFilterType = TaskFilterType.ALL,
    val isLoading: Boolean = true,
    /**
     * `id` of the task whose details bottom sheet is currently open, or
     * `null` if the sheet is dismissed.
     */
    val detailTaskId: String? = null,
    /**
     * How many tasks the agent scheduled for itself are queued or running —
     * counted before filtering, because the escape hatch it drives has to be
     * reachable from whichever filter the user happens to be on.
     */
    val scheduledTaskCount: Int = 0,
    /** `true` while the "stop all scheduled tasks" confirmation is showing. */
    val confirmingCancelAll: Boolean = false,
)
