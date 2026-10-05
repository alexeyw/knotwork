package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import app.knotwork.design.components.console.SpanStatus
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure-Kotlin mappers that bridge the domain orchestrator output to the
 * catalog console-pane row models consumed by `ChatHomeContent`. Lives in
 * `:app` because the catalog layer cannot reach `app.knotwork.android.*` types
 * (Clean Architecture).
 *
 * All public functions are deterministic and free of Android dependencies
 * beyond `org.json.JSONObject` (already used elsewhere in the chat-home
 * presentation layer), so they remain trivially unit-testable.
 */

/** Pre-formatted clock pattern used in [ConsoleLine.timestamp]. */
internal const val CONSOLE_LINE_TIMESTAMP_PATTERN: String = "HH:mm:ss.SSS"

/**
 * Cached [DateTimeFormatter] instance reused for every timestamp the
 * console mappers render. Critical for throughput: the orchestrator emits
 * cumulative `ConsoleLog` snapshots on every pipeline step, so the mapper
 * is invoked once per visible row per emission — `SimpleDateFormat`
 * instantiated per call produced an O(N²) allocation bottleneck for long
 * runs. `DateTimeFormatter` is immutable and thread-safe so a single
 * top-level instance is safe to share. Pinned to the device default zone
 * so the displayed clock matches the user's locale.
 */
private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern(CONSOLE_LINE_TIMESTAMP_PATTERN).withZone(ZoneId.systemDefault())

/**
 * Maps a single domain [ConsoleEvent] to the catalog [ConsoleLine] consumed
 * by the Logs tab of the chat-home console pane. The mapping is total —
 * every value of [ConsoleEventType] is covered, so the renderer never has
 * to fall back to a default branch.
 *
 * @param event Domain event accumulated by `GraphExecutionEngine`.
 * @return Display-oriented row with a millisecond-precision local timestamp
 *   string, an attribution [ConsoleSource], and a severity [ConsoleLevel].
 */
fun ConsoleEvent.toConsoleLine(): ConsoleLine = ConsoleLine(
    timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp)),
    source = type.toConsoleSource(),
    level = type.toConsoleLevel(),
    text = message,
    depth = depth,
)

/**
 * Projects the [ConsoleEventType] taxonomy onto the [ConsoleSource]
 * attribution shown next to each [ConsoleLine].
 */
internal fun ConsoleEventType.toConsoleSource(): ConsoleSource = when (this) {
    ConsoleEventType.NodeExecution -> ConsoleSource.NODE
    ConsoleEventType.ToolCall -> ConsoleSource.TOOL
    ConsoleEventType.MemoryAccess -> ConsoleSource.MEMORY
    ConsoleEventType.HistoryCompression -> ConsoleSource.MEMORY
    ConsoleEventType.SystemMessage -> ConsoleSource.RUNTIME
    ConsoleEventType.Error -> ConsoleSource.RUNTIME
    ConsoleEventType.StructuredOutputRepair -> ConsoleSource.RUNTIME
    ConsoleEventType.CloudRetry -> ConsoleSource.RUNTIME
    ConsoleEventType.RunCeiling -> ConsoleSource.RUNTIME
    ConsoleEventType.StuckDetector -> ConsoleSource.RUNTIME
}

/**
 * Projects the [ConsoleEventType] taxonomy onto the [ConsoleLevel]
 * severity. Errors are surfaced with the `Error` level; everything else is
 * informational. The legacy collapsed-console renderer rendered tool calls
 * as `Warn` (yellow); the redesigned Knotwork pane reserves `Warn` for
 * actual warnings, so tool calls fall back to `Info`.
 */
internal fun ConsoleEventType.toConsoleLevel(): ConsoleLevel = when (this) {
    ConsoleEventType.Error -> ConsoleLevel.Error
    ConsoleEventType.StructuredOutputRepair -> ConsoleLevel.Warn
    ConsoleEventType.CloudRetry -> ConsoleLevel.Warn
    ConsoleEventType.RunCeiling -> ConsoleLevel.Warn
    ConsoleEventType.StuckDetector -> ConsoleLevel.Warn
    ConsoleEventType.NodeExecution -> ConsoleLevel.Trace
    ConsoleEventType.MemoryAccess,
    ConsoleEventType.HistoryCompression,
    ConsoleEventType.ToolCall,
    ConsoleEventType.SystemMessage,
    -> ConsoleLevel.Info
}

/**
 * Projects a [AgentOrchestratorState.TraceStep] onto a [ConsoleTraceSpan]
 * consumed by the Traces tab. The catalog row carries a pre-formatted
 * [ConsoleTraceSpan.startedAt] string, so the caller threads the wall-clock
 * timestamp it observed when the trace step landed.
 *
 * @param trace Domain trace step emitted by `GraphExecutionEngine`.
 * @param startedAtMs Wall-clock time the trace step landed, in
 *   `System.currentTimeMillis()` units.
 * @return Span row with `Ok` status — the engine emits trace steps only for
 *   successful node completions; failures short-circuit the flow with an
 *   `Error` orchestrator state before any trace would be appended.
 */
fun traceStepToConsoleSpan(trace: AgentOrchestratorState.TraceStep, startedAtMs: Long): ConsoleTraceSpan =
    ConsoleTraceSpan(
        name = trace.nodeName,
        durationMs = trace.durationMs,
        startedAt = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(startedAtMs)),
        status = SpanStatus.Ok,
        depth = trace.depth,
        inputSha256 = trace.inputSha256,
        outputSha256 = trace.outputSha256,
    )

/**
 * Projects a single [AgentOrchestratorState.NodeIO] snapshot onto the pair
 * of [ConsoleVarRow]s rendered for that node in the Vars tab. The grouping
 * header on each row is the node label (`<TYPE>#<id-prefix>`), so the
 * catalog `ConsoleVarsBody` can collate input/output pairs of the same
 * node into a single section.
 *
 * The raw values are escaped through [JSONObject.quote] so multi-line and
 * special-character payloads render as a single safe JSON string without
 * breaking the row layout.
 *
 * @param io Per-node I/O snapshot.
 * @return Ordered `[input, output]` row pair.
 */
fun nodeIoToVarRows(io: AgentOrchestratorState.NodeIO): List<ConsoleVarRow> {
    val node = formatNodeLabel(io.nodeType, io.nodeId)
    return listOf(
        ConsoleVarRow(
            node = node,
            key = CONSOLE_VAR_KEY_INPUT,
            valueJson = JSONObject.quote(io.input),
            depth = io.depth,
            inputSha256 = io.inputSha256,
            outputSha256 = io.outputSha256,
        ),
        ConsoleVarRow(
            node = node,
            key = CONSOLE_VAR_KEY_OUTPUT,
            valueJson = JSONObject.quote(io.output),
            depth = io.depth,
            inputSha256 = io.inputSha256,
            outputSha256 = io.outputSha256,
        ),
    )
}

/**
 * Merges the replayed console baseline of a run with a live cumulative
 * snapshot of the same run, deduplicating by [ConsoleEvent.seq] — the
 * monotonic per-run position assigned by the engine. The live event wins a
 * collision (it is the same record, fresher by construction). The result is
 * ordered by seq, so the seam between replayed history and the live stream
 * renders correctly regardless of how much of the run the live snapshot
 * covers (everything, after an in-process reattach; only the tail, after a
 * future checkpoint resume).
 *
 * @param baseline Replayed events loaded from the persistent run trace.
 * @param live The cumulative live snapshot from the engine.
 * @return The merged event list ordered by seq, without duplicates.
 */
fun mergeConsoleEventsBySeq(baseline: List<ConsoleEvent>, live: List<ConsoleEvent>): List<ConsoleEvent> {
    val bySeq = LinkedHashMap<Long, ConsoleEvent>()
    baseline.forEach { bySeq[it.seq] = it }
    live.forEach { bySeq[it.seq] = it }
    return bySeq.values.sortedBy { it.seq }
}

/**
 * Re-hydrates a persisted console entry into the domain [ConsoleEvent] used
 * by the Logs tab. The replayed event keeps its original in-run [ConsoleEvent.seq],
 * which is what lets the replay/live seam deduplicate when a live cumulative
 * snapshot of the same run arrives on top of the baseline.
 *
 * @param entry Persisted console record loaded from the run trace.
 * @return The equivalent domain event.
 */
fun consoleEntryToConsoleEvent(entry: RunTraceRecord.ConsoleEntry): ConsoleEvent = ConsoleEvent(
    timestamp = entry.timestamp,
    type = entry.type,
    message = entry.message,
    seq = entry.seq,
    depth = entry.depth,
)

/**
 * Re-hydrates a persisted node I/O record into the orchestrator
 * [AgentOrchestratorState.NodeIO] snapshot shape, so the replay path can
 * feed the exact same Vars-tab projection ([nodeIoToVarRows]) as the live
 * path.
 *
 * @param record Persisted node I/O record loaded from the run trace.
 * @return The equivalent per-node I/O snapshot.
 */
fun nodeIoRecordToNodeIo(record: RunTraceRecord.NodeIo): AgentOrchestratorState.NodeIO = AgentOrchestratorState.NodeIO(
    nodeId = record.nodeId,
    nodeType = record.nodeType,
    input = record.inputText,
    output = record.outputText,
    depth = record.depth,
    inputSha256 = record.inputSha256.takeUnless { record.nodeType == NodeType.PIPELINE.name },
    outputSha256 = record.outputSha256.takeUnless { record.nodeType == NodeType.PIPELINE.name },
)

/**
 * Projects a persisted node I/O record onto a [ConsoleTraceSpan] for the
 * Traces tab of a replayed run. [ConsoleTraceSpan.startedAt] renders the
 * record's completion timestamp — the persisted analogue of the live path's
 * "wall clock when the step landed".
 *
 * @param record Persisted node I/O record loaded from the run trace.
 * @return Span row with `Ok` status — node failures short-circuit the run
 *   before a NodeIo record is appended, mirroring the live-path invariant.
 */
fun nodeIoRecordToConsoleSpan(record: RunTraceRecord.NodeIo): ConsoleTraceSpan = ConsoleTraceSpan(
    name = record.nodeType,
    durationMs = record.durationMs,
    startedAt = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(record.timestamp)),
    status = SpanStatus.Ok,
    depth = record.depth,
    inputSha256 = record.inputSha256.takeUnless { record.nodeType == NodeType.PIPELINE.name },
    outputSha256 = record.outputSha256.takeUnless { record.nodeType == NodeType.PIPELINE.name },
)

/**
 * Formats the grouping header for a [ConsoleVarRow] — concatenates the
 * node type with a short id suffix so two `LITE_RT` nodes in the same
 * pipeline remain distinguishable in the Vars tab.
 */
internal fun formatNodeLabel(nodeType: String, nodeId: String): String {
    val shortId = nodeId.take(NODE_ID_SHORT_LENGTH)
    return "$nodeType#$shortId"
}

/** Length of the node-id suffix appended to the grouping header. */
private const val NODE_ID_SHORT_LENGTH: Int = 6

/** Key rendered for the input row of a [AgentOrchestratorState.NodeIO]. */
internal const val CONSOLE_VAR_KEY_INPUT: String = "input"

/** Key rendered for the output row of a [AgentOrchestratorState.NodeIO]. */
internal const val CONSOLE_VAR_KEY_OUTPUT: String = "output"
