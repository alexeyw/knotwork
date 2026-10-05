package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.RunTreeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/**
 * The [NodeInference] of one node visit inside a run: seeds every on-device
 * call from the run seed and keeps what each call did until the engine records it.
 *
 * Calls are **buffered, not written**. An executor's flow may produce elements
 * on another coroutine than the one that consumes it (a `channelFlow`, a
 * `flowOn`), and the trace numbers its records in one sequence owned by the
 * invocation's coroutine. So a call ends by adding a [PendingModelCall] here, and
 * the engine drains the buffer into the trace ([LiveNodeStep]) when the node has
 * finished, parked or failed — in its own coroutine, before the node's own
 * input/output record.
 *
 * Only a call whose stream completed is kept: every executor reads its stream to
 * the end, so a stream that stops early is a failed or cancelled node, whose
 * output never reached the run.
 *
 * @param tree The run tree: its header supplies the run seed and sampler, its
 *   seed path the node's place in the tree.
 * @param node The node making the calls.
 * @param visit The node's zero-based visit index in its invocation.
 */
class RecordingNodeInference(private val tree: RunTreeContext, private val node: NodeModel, private val visit: Int) :
    NodeInference {

    /** Finished calls not yet drained into the trace. */
    private val pending = ConcurrentLinkedQueue<PendingModelCall>()

    /** Index the next call of this visit takes. */
    private val nextCall = AtomicInteger(0)

    override fun local(
        engine: LlmInferenceEngine,
        prompt: String,
        imagePath: String?,
        repairTemperature: Float?,
    ): Flow<String> = flow {
        val call = nextCall.getAndIncrement()
        val sampling = repairTemperature?.let(LocalSampling::repair) ?: LocalSampling(
            sampler = tree.header.sampler,
            seed = RunSeeds.forCall(tree.header.seed, tree.seedPath, node.id, visit, call),
        )
        // What the engine runs on, read before the call: the caller has loaded the
        // model, and the engine is not reloaded while this generation holds it.
        val modelPath = engine.currentModelPath
        val backend = engine.activeBackend
        val contextWindow = engine.activeContextLength
        val output = StringBuilder()
        val started = TimeSource.Monotonic.markNow()
        engine.generateResponseStream(prompt, imagePath, sampling).collect { chunk ->
            output.append(chunk)
            emit(chunk)
        }
        val durationMs = started.elapsedNow().inWholeMilliseconds
        pending.add(
            PendingModelCall.Local(
                nodeId = node.id,
                nodeType = node.type.name,
                visit = visit,
                call = call,
                sampling = sampling,
                modelPath = modelPath,
                backend = backend,
                contextWindow = contextWindow,
                hadImage = imagePath != null,
                prompt = prompt,
                output = output.toString(),
                durationMs = durationMs,
            ),
        )
    }

    override fun cloudCall(provider: String, model: String?) {
        pending.add(
            PendingModelCall.Cloud(
                nodeId = node.id,
                nodeType = node.type.name,
                visit = visit,
                call = nextCall.getAndIncrement(),
                provider = provider,
                model = model,
            ),
        )
    }

    /**
     * Takes every finished call out of the buffer.
     *
     * @return The calls in the order they were made.
     */
    fun drain(): List<PendingModelCall> = generateSequence { pending.poll() }.toList().sortedBy { it.call }
}

/**
 * A model call a node has finished, waiting for the engine to number it and
 * write it to the trace as a [app.knotwork.android.domain.models.RunTraceRecord].
 *
 * @property nodeId The node that made the call.
 * @property nodeType The node's type name.
 * @property visit The node's zero-based visit index.
 * @property call The call's zero-based index within the visit.
 */
sealed interface PendingModelCall {
    val nodeId: String
    val nodeType: String
    val visit: Int
    val call: Int

    /**
     * A finished on-device call.
     *
     * @property sampling The sampler and seed it ran with.
     * @property modelPath The model file the engine had loaded.
     * @property backend The backend the engine ran on.
     * @property contextWindow The engine's context window, in tokens.
     * @property hadImage Whether an image was sent with the prompt.
     * @property prompt The full text sent.
     * @property output The full text streamed back.
     * @property durationMs How long the stream took, from the call to its last
     *   chunk — what a later check expects the repeat to cost.
     */
    data class Local(
        override val nodeId: String,
        override val nodeType: String,
        override val visit: Int,
        override val call: Int,
        val sampling: LocalSampling,
        val modelPath: String?,
        val backend: LocalBackend?,
        val contextWindow: Int?,
        val hadImage: Boolean,
        val prompt: String,
        val output: String,
        val durationMs: Long,
    ) : PendingModelCall

    /**
     * A call to a cloud model.
     *
     * @property provider The provider id.
     * @property model The model id, when the node knows it.
     */
    data class Cloud(
        override val nodeId: String,
        override val nodeType: String,
        override val visit: Int,
        override val call: Int,
        val provider: String,
        val model: String?,
    ) : PendingModelCall
}
