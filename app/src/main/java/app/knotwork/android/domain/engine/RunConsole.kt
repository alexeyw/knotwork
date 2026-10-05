package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConsoleEvent
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.RunTraceRepository
import kotlinx.coroutines.flow.FlowCollector

/**
 * Writes one engine invocation's console lines and trace records, numbering them
 * in one sequence.
 *
 * Every console line the run shows, and every record its persistent trace keeps —
 * the console line itself, the memory snapshot, each model call, each node's
 * input/output pair — takes the next `seq`. Uniqueness within a run is what lets
 * the console deduplicate the replay/live seam by `seq`, so a resumed run starts
 * from the interrupted run's next number instead of colliding with its persisted
 * records.
 *
 * Console lines go two ways: into the persistent trace (for a persisted run) and
 * to the screen, as a fresh [AgentOrchestratorState.ConsoleLog] snapshot of every
 * line so far, emitted into the run's flow. The other records go to the trace
 * only, and only for a persisted run.
 *
 * One instance per engine invocation, created inside the invocation's flow: it
 * emits into that flow's collector, from the same coroutine.
 *
 * @param collector The invocation's flow collector, which receives the console
 *   snapshots.
 * @param runTraceRepository The persistent run trace. It buffers and flushes in
 *   batches, so a streamed console line never costs a database commit.
 * @param sessionId Chat session the run belongs to.
 * @param runId The run record the trace belongs to, or `null` for a run that is
 *   not persisted — then nothing is appended, but console lines are still
 *   numbered and shown.
 * @param depth Nesting depth of the invocation: stamped on every line and
 *   record, and above `0` it prefixes each line with [pipelineName].
 * @param pipelineName Name of the graph being run, used in that prefix.
 * @param firstSeq The number the first line or record takes: `0` for a fresh run,
 *   the interrupted run's next number for a resumed one.
 */
class RunConsole(
    private val collector: FlowCollector<AgentOrchestratorState>,
    private val runTraceRepository: RunTraceRepository,
    private val sessionId: String,
    private val runId: String?,
    private val depth: Int,
    private val pipelineName: String,
    firstSeq: Long,
) {

    /** Every line pushed so far, re-emitted whole on each push. */
    private val events = mutableListOf<ConsoleEvent>()

    /** The number the next line or record takes. */
    private var nextSeq = firstSeq

    /**
     * Shows one console line and, for a persisted run, appends it to the trace.
     *
     * The message is redacted here, because a line is shown, copied whole by
     * *Copy all* and persisted, and an executor's own diagnostics may quote a
     * provider error (see [CloudErrorSanitizer]). A nested run prefixes the line
     * with its pipeline's name, so the merged console reads `[Translator] ▶ …`
     * even before the depth indents it.
     *
     * @param type The console channel the line belongs to.
     * @param message The line as the caller wrote it.
     */
    suspend fun push(type: ConsoleEventType, message: String) {
        val redacted = CloudErrorSanitizer.redactSecrets(message)
        val displayMessage = if (depth > 0) "[$pipelineName] $redacted" else redacted
        val event = ConsoleEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            message = displayMessage,
            seq = nextSeq++,
            depth = depth,
        )
        events += event
        if (runId != null) {
            runTraceRepository.append(
                RunTraceRecord.ConsoleEntry(
                    runId = runId,
                    sessionId = sessionId,
                    seq = event.seq,
                    timestamp = event.timestamp,
                    type = type,
                    message = displayMessage,
                    depth = depth,
                ),
            )
        }
        collector.emit(AgentOrchestratorState.ConsoleLog(events.toList(), runId))
    }

    /**
     * Records the long-term memory a run retrieved, so a checkpoint resume seeds
     * its memory from this snapshot instead of retrieving again — the resumed
     * context has to be identical to the interrupted one. Nothing is recorded,
     * and no number is taken, for a run that is not persisted.
     *
     * @param entries The chunks injected into the run.
     */
    suspend fun recordMemorySnapshot(entries: List<MemoryChunk>) {
        val id = runId ?: return
        runTraceRepository.append(
            RunTraceRecord.MemorySnapshot(
                runId = id,
                sessionId = sessionId,
                seq = nextSeq++,
                timestamp = System.currentTimeMillis(),
                entries = entries,
            ),
        )
    }

    /**
     * Records a node that has just run: its full input and output, so the Vars
     * and Traces console tabs can be rebuilt for a finished run, and with the
     * routing verdicts and tool attribution a checkpoint resume substitutes for
     * re-running it. Nothing is recorded, and no number is taken, for a run that
     * is not persisted.
     *
     * A TOOL or CLOUD node's record is flushed at once. The trace is
     * write-buffered — flushed on size, on a 500 ms timer, or at suspension
     * points — so a process death inside that window used to lose the record of
     * a tool that had already run, and the resume called it a second time.
     * Measured on the reference device: killed 112 ms after the tool returned,
     * the resumed run re-invoked it (a second `tools/call` on the wire); killed
     * 1.2 s after, it replayed as designed. A CLOUD node earns the same treatment
     * for a different reason: re-running it costs money and the provider's rate
     * limit rather than a side effect on the world. For an on-device node a
     * repeat is lost time only, and the record waits for the next batch.
     *
     * Both texts are hashed here ([TraceHashing]), the one place every node's
     * record passes, so an export or a later check can show the record was not
     * altered.
     *
     * @param node The node that ran.
     * @param visit Its zero-based visit index in this invocation.
     * @param inputText What it executed on.
     * @param outputText What it produced (its input, for a node that produced nothing).
     * @param durationMs How long it took.
     * @param result Its result — token count, condition, routing key and tool name.
     */
    suspend fun recordNodeIo(
        node: NodeModel,
        visit: Int,
        inputText: String,
        outputText: String,
        durationMs: Long,
        result: NodeExecutionResult?,
    ) {
        val id = runId ?: return
        runTraceRepository.append(
            RunTraceRecord.NodeIo(
                runId = id,
                sessionId = sessionId,
                seq = nextSeq++,
                timestamp = System.currentTimeMillis(),
                nodeId = node.id,
                nodeType = node.type.name,
                inputText = inputText,
                outputText = outputText,
                durationMs = durationMs,
                tokenCount = result?.tokenCount,
                conditionResult = result?.conditionResult,
                routingKey = result?.routingKey,
                resolvedToolName = result?.resolvedToolName,
                depth = depth,
                visit = visit,
                inputSha256 = TraceHashing.sha256Hex(inputText),
                outputSha256 = TraceHashing.sha256Hex(outputText),
            ),
        )
        if (node.type == NodeType.TOOL || node.type == NodeType.CLOUD) {
            runTraceRepository.flush()
        }
    }

    /**
     * Records a model call a node finished, numbered in the same sequence as the
     * node's console lines and its input/output record — before the latter, since
     * the node made the call while it ran. Nothing is recorded, and no number is
     * taken, for a run that is not persisted.
     *
     * An on-device call keeps its full prompt and output with their hashes: that
     * is what a later check repeats. A cloud call keeps only which provider and
     * model were asked.
     *
     * @param pending The finished call.
     * @param modelSha256 SHA-256 of the model file an on-device call ran on, from
     *   the model registry, or `null` when it is not known (yet); ignored for a
     *   cloud call.
     */
    suspend fun recordModelCall(pending: PendingModelCall, modelSha256: String?) {
        val id = runId ?: return
        val record = when (pending) {
            is PendingModelCall.Local -> RunTraceRecord.LocalModelCall(
                runId = id,
                sessionId = sessionId,
                seq = nextSeq++,
                timestamp = System.currentTimeMillis(),
                nodeId = pending.nodeId,
                nodeType = pending.nodeType,
                visit = pending.visit,
                call = pending.call,
                depth = depth,
                sampling = pending.sampling,
                modelPath = pending.modelPath,
                modelSha256 = modelSha256,
                backend = pending.backend,
                contextWindow = pending.contextWindow,
                hadImage = pending.hadImage,
                prompt = pending.prompt,
                output = pending.output,
                promptSha256 = TraceHashing.sha256Hex(pending.prompt),
                outputSha256 = TraceHashing.sha256Hex(pending.output),
                durationMs = pending.durationMs,
            )
            is PendingModelCall.Cloud -> RunTraceRecord.CloudModelCall(
                runId = id,
                sessionId = sessionId,
                seq = nextSeq++,
                timestamp = System.currentTimeMillis(),
                nodeId = pending.nodeId,
                nodeType = pending.nodeType,
                visit = pending.visit,
                call = pending.call,
                depth = depth,
                provider = pending.provider,
                model = pending.model,
            )
        }
        runTraceRepository.append(record)
    }

    /**
     * Makes everything appended so far durable — before the run waits for an
     * answer it may never get in this process.
     */
    suspend fun flush() {
        runTraceRepository.flush()
    }
}
