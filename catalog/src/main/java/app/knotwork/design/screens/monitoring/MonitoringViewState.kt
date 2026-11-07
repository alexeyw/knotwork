package app.knotwork.design.screens.monitoring

/** Visual variant of the Monitoring surface. */
enum class MonitoringVisualState { Loading, Empty, Default, Error }

/**
 * Pre-formatted stat cell rendered in the metrics grid.
 *
 * @property label uppercase metric label (e.g. `"INFERENCE TIME"`).
 * @property value formatted value (e.g. `"42 ms"`).
 */
data class MonitoringStat(val label: String, val value: String)

/**
 * One row of the "Time per node type" breakdown.
 *
 * @property nodeType the node type's name in the interface language (e.g. `"Intent Router"`),
 *   never its code (`INTENT_ROUTER`).
 * @property totalLabel formatted time the run's nodes of this type took (e.g. `"640 ms"`).
 */
data class NodeBreakdownRow(val nodeType: String, val totalLabel: String)

/**
 * Pre-formatted system log line surfaced on the recent-logs list.
 *
 * @property id key of the line in the list, unique among the lines shown. Never built
 *   from [timestamp] and [message]: the same message recorded twice within a second
 *   shares both, and a repeated key crashes the list.
 * @property timestamp time of day the line was recorded (e.g. `"12:04:01"`).
 * @property message the logged text.
 */
data class MonitoringLogLine(val id: String, val timestamp: String, val message: String)

/**
 * Top-level immutable input to `MonitoringContent`.
 */
data class MonitoringViewState(
    val visualState: MonitoringVisualState,
    val powerSavingActive: Boolean = false,
    val stats: List<MonitoringStat> = emptyList(),
    val totalExecutionLine: String? = null,
    val perNodeBreakdown: List<NodeBreakdownRow> = emptyList(),
    val logs: List<MonitoringLogLine> = emptyList(),
    val errorMessage: String? = null,
) {
    init {
        require((visualState == MonitoringVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `MonitoringContent`. */
class MonitoringCallbacks(val onBack: () -> Unit = {}, val onRetry: () -> Unit = {})

/** Convenience factory returning a no-op callback bundle. */
fun noopMonitoringCallbacks(): MonitoringCallbacks = MonitoringCallbacks()
