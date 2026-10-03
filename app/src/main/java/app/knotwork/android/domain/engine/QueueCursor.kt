package app.knotwork.android.domain.engine

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.structured.JsonPayloadExtractor
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.RouteLabels
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Where one QUEUE_PROCESSOR loop of a run stands: the items still to run, the
 * results so far, and which node the loop belongs to.
 *
 * A QUEUE_PROCESSOR node turns its input into a list of items. The walk then runs
 * the node on the queue's `Item` edge once per item, handing each one the item
 * together with the results of the items before it, and leaves by the `Done`
 * edge with a summary of every result once the list is exhausted. This class
 * keeps that loop's state for one engine invocation and decides each boundary;
 * the walk only moves to where it says.
 *
 * @param graph The running graph, whose `Item` and `Done` edges the loop follows.
 */
class QueueCursor(private val graph: PipelineGraph) {

    /** Items not yet run, in order; the next one is taken from the front. */
    private val remaining = mutableListOf<String>()

    /** Results of the items run so far, rendered into each next item's input. */
    private val results = mutableListOf<String>()

    /** Id of the QUEUE_PROCESSOR whose loop the walk is in, or `null` outside one. */
    private var processorId: String? = null

    /** `true` while the walk is inside a queue's loop. */
    val isActive: Boolean get() = processorId != null

    /**
     * Starts the loop of [processor], which has just run, with the items parsed
     * from [listText]. Any loop already running is replaced.
     *
     * @param processor The QUEUE_PROCESSOR node.
     * @param listText Its output, or its input when it produced none.
     * @return The first item to run, or — with no items or no `Item` edge — the
     *   `Done` edge, taken with the run's input unchanged.
     */
    fun enter(processor: NodeModel, listText: String): QueueEntry {
        remaining.clear()
        remaining.addAll(parseList(listText))
        results.clear()
        processorId = processor.id
        val ports = portsOf(processor.id)
        val itemNodeId = ports.itemNodeId
        if (remaining.isEmpty() || itemNodeId == null) {
            processorId = null
            return QueueEntry.Skipped(nodeById(ports.doneNodeId))
        }
        val itemNode = nodeById(itemNodeId)
        // Every item runs the item path once, then the walk takes the Done path:
        // counted now, while the whole list is still here.
        val remainingSteps =
            remaining.size * GraphRouting.countNodesOnPath(itemNode, graph, stopNodeIds = setOf(processor.id)) +
                GraphRouting.countNodesOnPath(nodeById(ports.doneNodeId), graph)
        return QueueEntry.FirstItem(itemNode, nextItemInput(), remainingSteps)
    }

    /**
     * Ends the current item with [result] and moves to the next one, or leaves the
     * loop by its `Done` edge when nothing is left.
     *
     * @param result The text the item's path produced.
     * @return Where the walk goes next.
     */
    fun advance(result: String): QueueStep {
        results.add(result)
        val ports = portsOf(checkNotNull(processorId) { "advance outside a queue" })
        val itemNodeId = ports.itemNodeId
        if (remaining.isEmpty() || itemNodeId == null) {
            processorId = null
            val summary = "Queue execution completed.\nResults:\n" +
                results.mapIndexed { i, res -> "${i + 1}. $res" }.joinToString("\n")
            return QueueStep(nodeById(ports.doneNodeId), summary)
        }
        return QueueStep(nodeById(itemNodeId), nextItemInput())
    }

    /**
     * Whether a failed item may be survived: the walk is inside a queue whose
     * author turned `stopOnError` off. Opt-in on purpose — `null` and `true` both
     * fail the run, which is what every pipeline saved before the field did.
     *
     * @return `true` when the failure becomes the item's result and the loop goes on.
     */
    fun continuesAfterFailure(): Boolean =
        processorId?.let { id -> graph.nodes.find { it.id == id } }?.stopOnError == false

    /**
     * Ends the current item as failed — the error becomes its result — and moves
     * on as [advance] does.
     *
     * @param error The item's error, already redacted.
     * @return Where the walk goes next.
     */
    fun advancePastFailure(error: String): QueueStep = advance("Subtask failed: $error")

    /** Takes the next item and wraps it with the results so far. */
    private fun nextItemInput(): String {
        val nextItem = remaining.removeAt(0)
        val contextStr = results.mapIndexed { i, res -> "Result of Subtask ${i + 1}:\n$res" }.joinToString("\n\n")
        val subtaskInstruction = DefaultPrompts.QueueProcessor.SUBTASK_INSTRUCTION
        return if (contextStr.isNotEmpty()) {
            "PREVIOUS RESULTS CONTEXT:\n$contextStr\n\n---\n\n$subtaskInstruction" +
                "\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
        } else {
            "$subtaskInstruction\n\nCURRENT SUBTASK TO EXECUTE:\n$nextItem"
        }
    }

    /** The node at the end of a queue's `Item` and `Done` edges. */
    private fun portsOf(processorId: String): Ports {
        val edges = graph.connections.filter { it.sourceNodeId == processorId }
        return Ports(
            itemNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.ITEM) }?.targetNodeId
                ?: edges.firstOrNull()?.targetNodeId,
            doneNodeId = edges.find { RouteLabels.matches(it.label, RouteLabels.DONE) }?.targetNodeId,
        )
    }

    private fun nodeById(id: String?): NodeModel? = graph.nodes.find { it.id == id }

    /**
     * Where a queue's two edges lead.
     *
     * @property itemNodeId The `Item` edge's target, or the first edge's when none is
     *   labelled `Item`; `null` when the queue has no outgoing edge.
     * @property doneNodeId The `Done` edge's target, or `null` when it is unwired.
     */
    private class Ports(val itemNodeId: String?, val doneNodeId: String?)

    private companion object {
        /** Lenient JSON used to parse a queue's seed list (see [parseList]). */
        val listJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parses a list of items from a node's text output, used to seed a
         * `QUEUE_PROCESSOR` from an upstream `DECOMPOSITION` (or any list-producing
         * node).
         *
         * JSON isolation is delegated to the shared [JsonPayloadExtractor] and the
         * array is deserialized with `kotlinx.serialization` — a `DECOMPOSITION` node
         * already validated and re-encoded its list through the structured-output
         * gate, so the common case is a clean array. The Markdown-list fallback (and
         * the single-item fallback) remain for nodes that emit a plain bulleted or
         * numbered list rather than JSON.
         *
         * @param text The upstream node output to parse.
         * @return The parsed items, or a single-element list of [text] when nothing
         *   list-shaped is found.
         */
        fun parseList(text: String): List<String> {
            val payload = JsonPayloadExtractor.extract(text)
            if (payload.startsWith("[")) {
                try {
                    val list = listJson.decodeFromString(ListSerializer(String.serializer()), payload)
                    if (list.isNotEmpty()) return list
                } catch (e: IllegalArgumentException) {
                    // Not a valid string array — fall through to the Markdown-list parsing.
                    // `decodeFromString` is non-suspend, so this cannot mask a CancellationException.
                    Timber.tag("PipelineDebug").e(e, "Error parsing JSON list")
                }
            }

            val lines = text.lines().map { it.trim() }.filter { it.matches(Regex("""^(\d+\.|-|\*)\s+.*""")) }
            if (lines.isNotEmpty()) {
                return lines.map { it.replaceFirst(Regex("""^(\d+\.|-|\*)\s+"""), "") }
            }

            return listOf(text)
        }
    }
}

/**
 * One boundary of a queue's loop: the node the walk moves to next and the input it
 * carries there.
 *
 * @property node The next node, or `null` when the edge it would take is unwired.
 * @property inputText The input for [node].
 */
data class QueueStep(val node: NodeModel?, val inputText: String)

/** What happens when a QUEUE_PROCESSOR node hands the walk its list. */
sealed interface QueueEntry {

    /**
     * The loop starts.
     *
     * @property node The node on the queue's `Item` edge.
     * @property inputText The first item, wrapped for that node.
     * @property remainingSteps Steps the loop and the path after it are expected to
     *   take, for the progress indicator: the item path once per item, then the
     *   `Done` path.
     */
    data class FirstItem(val node: NodeModel?, val inputText: String, val remainingSteps: Int) : QueueEntry

    /**
     * Nothing to loop over — an empty list, or no `Item` edge. The walk leaves by
     * the `Done` edge with the run's input unchanged.
     *
     * @property node The node on the `Done` edge, or `null` when it is unwired.
     */
    data class Skipped(val node: NodeModel?) : QueueEntry
}
