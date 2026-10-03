package app.knotwork.design.screens.taskmonitor

/** Visual variant of the Task Monitor surface. */
enum class TaskMonitorVisualState {
    Loading,
    Empty,
    Default,
    Error,
}

/**
 * Task lifecycle stage as rendered by the trailing status pill.
 *
 * Mirrors the app-side `TaskStatus` enum but lives in the catalog so the
 * catalog package stays Android-resource-free.
 */
enum class TaskRowStatus { Queued, Running, Success, Cancelled, Failed }

/**
 * Top-level filter chip applied to the list.
 */
enum class TaskFilterKind(val displayName: String) {
    All(displayName = "All"),
    Active(displayName = "Active"),
    Background(displayName = "Background"),
    Completed(displayName = "Completed"),
}

/**
 * One task surfaced as a row.
 *
 * @property id stable identifier (typically the WorkManager request id or
 * chat session id).
 * @property title display label (chat title or worker name).
 * @property subtitle optional mono subtitle (typically the pipeline stage).
 * @property status trailing status pill driver.
 * @property progress 0..1 fraction shown as a determinate bar when the
 * status is [TaskRowStatus.Running]; null hides the bar (indeterminate
 * is rendered as a steady accent fill).
 * @property isCancellable when true, the row supports swipe-to-cancel.
 */
data class TaskMonitorRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val status: TaskRowStatus,
    val progress: Float? = null,
    val isCancellable: Boolean = false,
)

/**
 * Detail payload populating the `ModalBottomSheet` opened on row tap.
 *
 * @property logs human-readable log lines (mono).
 * @property canOpenChat `true` when the [id] addresses a chat session
 * — drives the visibility of the `Open chat` CTA in the detail sheet.
 * `false` for background WorkManager tasks whose id is a `UUID`,
 * because navigating to a chat with that id would write an invalid
 * `currentChatSessionId` and route to a non-existent screen.
 */
data class TaskMonitorDetail(
    val id: String,
    val title: String,
    val subtitle: String?,
    val status: TaskRowStatus,
    val logs: List<String>,
    val canOpenChat: Boolean = false,
)

/**
 * Top-level immutable input to `TaskMonitorContent`.
 *
 * @property scheduledTaskCount How many tasks the agent scheduled for itself are
 * currently queued or running. Drives the "cancel every scheduled task" action,
 * which is hidden at `0` — the action exists for the case where a task keeps
 * re-scheduling itself, and an always-present bulk-cancel on an empty list is
 * just a hazard.
 * @property confirmingCancelAll `true` while the bulk-cancel confirmation is on
 * screen.
 */
data class TaskMonitorViewState(
    val visualState: TaskMonitorVisualState,
    val filter: TaskFilterKind = TaskFilterKind.All,
    val rows: List<TaskMonitorRow> = emptyList(),
    val expandedDetail: TaskMonitorDetail? = null,
    val errorMessage: String? = null,
    val scheduledTaskCount: Int = 0,
    val confirmingCancelAll: Boolean = false,
) {
    init {
        require((visualState == TaskMonitorVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `TaskMonitorContent`. */
class TaskMonitorCallbacks(
    val onBack: () -> Unit = {},
    val onFilterChanged: (TaskFilterKind) -> Unit = {},
    val onRowClick: (String) -> Unit = {},
    val onRowCancel: (String) -> Unit = {},
    val onCancelAllScheduled: () -> Unit = {},
    val onCancelAllScheduledConfirm: () -> Unit = {},
    val onCancelAllScheduledDismiss: () -> Unit = {},
    val onDetailDismiss: () -> Unit = {},
    val onDetailOpenChat: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopTaskMonitorCallbacks(): TaskMonitorCallbacks = TaskMonitorCallbacks()
