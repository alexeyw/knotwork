package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.ChatHistorySummary
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.models.usesContextConfig
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.repositories.ToolSettings
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds what each node of one engine invocation sees: its system prompt with
 * `$VARIABLE`s rendered, its input composed from the context blocks it opted
 * into, and — for exactly one node in the run tree — the run's image.
 *
 * The context blocks come from sources that cost something to read, so each is
 * read only when a node asks for it, and the run-stable ones only once:
 * long-term memory is retrieved by the first node that renders it (a resumed run
 * reuses the interrupted run's snapshot), the chat-history settings and summary
 * are resolved once, and the live message list is re-read for every node that
 * replays it. Tool results accumulate as TOOL nodes finish. Advice raised for
 * the run ([RunTreeContext.contextNotes]) is delivered into the next composed
 * prompt — never into the text that travels between nodes, and never to OUTPUT.
 *
 * One instance per engine invocation, from [Factory.open].
 */
class NodeInputComposer private constructor(
    private val sources: Factory,
    private val console: RunConsole,
    private val graph: PipelineGraph,
    private val sessionId: String,
    private val userPrompt: String,
    private val tree: RunTreeContext,
    memorySnapshot: List<MemoryChunk>?,
) {

    /** Memory retrieved for this run, or the interrupted run's snapshot; `null` until a node needs it. */
    private var memories: List<MemoryChunk>? = memorySnapshot

    /** The run-stable chat-history inputs, or `null` until a node first replays history. */
    private var history: HistorySettings? = null

    /** Whether the one console note about history compression has been pushed. */
    private var historyCompressionLogged = false

    /** Tool results of this run so far, for the `--- Tool Results ---` block. */
    private val toolResults = mutableListOf<ToolInvocationResult>()

    /**
     * The node as its executor should see it, and the input it should execute on.
     *
     * @param node The node about to run, with its system prompt rendered.
     * @param input What it executes on: the composed context for a node that
     *   composes one, the text the run carried to it otherwise.
     */
    data class PreparedNode(val node: NodeModel, val input: String)

    /**
     * Prepares [node] to run on [carriedInput], the text the run brought to it.
     *
     * The system prompt is rendered first — only for node types whose prompt
     * reaches a model (the others ignore `systemPrompt` or use it for non-LLM
     * logic, where placeholders are not expected).
     *
     * Then the input. Control-flow nodes (INPUT, IF_CONDITION, QUEUE_PROCESSOR)
     * keep their raw passthrough — wrapping them would corrupt routing and queue
     * state — and so does OUTPUT in echo mode (no system prompt), so it forwards
     * the upstream result verbatim instead of leaking context headers to the user.
     * Every other node gets only the blocks its context config opted into.
     *
     * @param node The node about to run.
     * @param carriedInput The upstream node's output, or the run's prompt for a
     *   node right behind INPUT.
     * @return The node to execute and its input.
     */
    suspend fun prepare(node: NodeModel, carriedInput: String): PreparedNode {
        val rendered = renderSystemPrompt(node)
        val input = if (node.usesContextConfig()) compose(node, carriedInput) else carriedInput
        return PreparedNode(rendered, input)
    }

    /**
     * Hands the run's image to [node] if it is the one that should see it: the
     * first LITE_RT node, anywhere in the run tree, whose context includes the
     * original task — the image accompanies the user's prompt. Once a node takes
     * it, every later node at any depth, and every CLOUD node, sees text only.
     *
     * @param node The node about to run.
     * @return The image's absolute path for that one node, `null` for any other.
     */
    fun takeImage(node: NodeModel): String? {
        val delivery = tree.imageDelivery ?: return null
        if (delivery.consumed || node.type != NodeType.LITE_RT || !node.contextConfig.originalTask) return null
        delivery.consumed = true
        return delivery.image.absolutePath
    }

    /**
     * Adds a TOOL node's observation to the results later nodes can opt into.
     *
     * @param result The tool that ran and what it returned.
     */
    fun recordToolResult(result: ToolInvocationResult) {
        toolResults += result
    }

    /**
     * Returns a copy of [node] with its `systemPrompt` rendered through
     * [PromptTemplateEngine], substituting all `$VARIABLE` placeholders using the
     * injected [PromptVariableProvider] set. For nodes whose `systemPrompt` is not
     * consumed by an LLM (e.g. [NodeType.TOOL], [NodeType.IF_CONDITION]) the
     * original [node] instance is returned unchanged to avoid wasted work and
     * accidental substitution inside fields that happen to share the syntax.
     */
    private suspend fun renderSystemPrompt(node: NodeModel): NodeModel {
        val rawPrompt = node.systemPrompt
        if (rawPrompt.isNullOrEmpty() || node.type !in LLM_NODE_TYPES) return node
        val rendered = sources.promptTemplateEngine.render(rawPrompt, sources.promptVariableProviders.toList())
        if (rendered === rawPrompt || rendered == rawPrompt) return node
        return node.copy(systemPrompt = rendered)
    }

    /** Composes a context-using node's input from the blocks it opted into. */
    private suspend fun compose(node: NodeModel, carriedInput: String): String {
        // Embed + search only when this node actually renders the memory block;
        // otherwise pass an empty list so retrieval is never triggered on its
        // behalf. The carried input is the background run's second-choice
        // retrieval key.
        val memoryEntries = if (node.contextConfig.longTermMemory) memoriesOnce(carriedInput) else emptyList()
        // Only nodes that render chat history pay for loading + planning it;
        // others get the empty view (no DB read). The view is recomputed per node
        // (fresh message read) so in-run message writes — e.g. TOOL observations —
        // are visible to later history-enabled nodes.
        val chatHistoryView = if (node.contextConfig.chatHistory) chatHistoryView() else ChatHistoryView.EMPTY
        // Read only when the node feeds the on-device model and there is tool
        // text to cut: a result of this run, or an observation row (SYSTEM) in
        // the history it replays.
        val hasToolText = toolResults.isNotEmpty() || chatHistoryView.liveWindow.any { it.role == Role.SYSTEM }
        val toolResultCharBudget = if (hasToolText && feedsOnDeviceModel(node)) {
            sources.toolSettings.workspaceReadTokenBudget.first() * ChatHistoryWindowPlanner.CHARS_PER_TOKEN
        } else {
            null
        }
        val executionContext = PipelineExecutionContext(
            originalUserMessage = userPrompt,
            chatHistory = chatHistoryView.liveWindow,
            previousNodeOutput = carriedInput,
            toolResults = toolResults.toList(),
            memoryEntries = memoryEntries,
            earlierSummary = chatHistoryView.earlierSummary,
            toolResultCharBudget = toolResultCharBudget,
        )
        // No fallback to the carried input: an empty result is the intended
        // outcome of a sparse config (e.g. only toolResults=true before any tool
        // has run). The validation layer forbids the all-flags-false case, so we
        // will not silently leak previous-node output back into a node that
        // opted out.
        val composed = sources.nodeContextBuilder.build(node.contextConfig, executionContext)
        // Deliver a soft warning raised by an earlier node into this node's
        // *composed prompt*, so the model can wind the task up rather than
        // discovering the hard stop by walking into it.
        //
        // Into the composed input, never into the text that travels between
        // nodes. For a node that does not compose a prompt that text is *data*: a
        // pass-through OUTPUT persists it verbatim as the agent's chat message,
        // `QUEUE_PROCESSOR` parses it as a list, `IF_CONDITION` branches on it.
        // Writing the notice there printed the engine's internals to the user as
        // their answer — and guarding only the node it was handed to was not
        // enough, because `INTENT_ROUTER` composes a prompt but deliberately
        // forwards the carried text unchanged, so the pollution outlived the
        // router and reached OUTPUT anyway. Scoping it to one node's input makes
        // that structurally impossible rather than conditionally avoided. A
        // crossing or a stuck verdict announces itself on the console where it
        // happens, but reaches the model only here, on the next node that
        // composes a prompt.
        //
        // Except OUTPUT, which composes one and must still never be handed a
        // note. Two reasons, and the second is the one that bites: advice to wrap
        // up has no reader at the node that *is* the wrapping up; and
        // `OutputNodeExecutor` falls back to persisting its own input verbatim
        // when the model returns nothing, so a note delivered here becomes the
        // agent's chat message on any empty generation. That is the third shape
        // of the same defect — the first two were writing the note to the carried
        // text, and letting it survive an `INTENT_ROUTER` — and it is why the
        // exclusion is on the node type rather than on the executor.
        val notes = if (node.type == NodeType.OUTPUT) null else tree.contextNotes.drain()
        return if (notes != null) "$notes\n\n$composed" else composed
    }

    /**
     * Long-term memory for this run, retrieved at most once.
     *
     * Only the first *executed* node that actually opts into the
     * `--- Long-Term Memory ---` block triggers the query embedding — and that
     * same node decides the retrieval key (see [MemoryRetrievalQueryResolver]): an
     * interactive run keys off the immutable user prompt as it always has, a
     * background run prefers the pipeline's declared query, then the node's own
     * input, because a trigger's prompt is authored once and describes no
     * particular firing. A graph where no executed node requests memory never
     * embeds anything at all — sparing avoidable embedding-provider latency and
     * cost, and not shipping the prompt to a cloud embedding backend the user did
     * not ask memory for. A resumed run is seeded from the interrupted run's
     * persisted snapshot, so it neither re-runs retrieval (the context must be
     * identical to the interrupted one) nor re-counts usage.
     */
    private suspend fun memoriesOnce(nodeInput: String): List<MemoryChunk> {
        memories?.let { return it }
        // The declared query is a prompt template like any other, so `$DATE`
        // and friends resolve per run instead of being frozen at authoring
        // time. Rendering happens only when a declared query exists and only
        // on the one node that triggers retrieval.
        val declaredQuery = graph.memoryRetrievalQuery
            ?.takeIf { it.isNotBlank() }
            ?.let { sources.promptTemplateEngine.render(it, sources.promptVariableProviders.toList()) }
        val query = MemoryRetrievalQueryResolver.resolve(
            origin = tree.origin,
            declaredQuery = declaredQuery,
            nodeInput = nodeInput,
            userPrompt = userPrompt,
        )
        val scored: List<Pair<MemoryChunk, Float>> = try {
            sources.retrieveRelevantMemoryUseCase.retrieveScored(query.text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Memory retrieval suspends (embedding + DB lookup). Swallowing
            // cancellation here would let the parent flow keep running after
            // the caller cancelled, breaking structured concurrency.
            throw e
        } catch (e: Exception) {
            Timber.tag("PipelineDebug").w(e, "Failed to retrieve long-term memories; continuing without them")
            emptyList()
        }
        val verbose = sources.memorySettings.verboseMemoryLoggingEnabled.first()
        console.push(
            ConsoleEventType.MemoryAccess,
            MemoryAccessLogFormatter.format(
                query = query.text,
                source = query.source,
                hits = scored,
                verbose = verbose,
            ),
        )
        val hits = scored.map { it.first }
        // Record that these chunks were injected into this run so the
        // Memory detail sheet can show "Used in N runs". Best-effort:
        // a failure here must never break the pipeline run.
        try {
            sources.memoryRepository.recordUsage(hits.map { it.id }, System.currentTimeMillis())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("PipelineDebug").w(e, "Failed to record memory usage; continuing")
        }
        // Persist the resolved chunks so a checkpoint resume of this run
        // can seed its memory from the snapshot instead of re-running
        // retrieval — the resumed context must be identical to this one.
        console.recordMemorySnapshot(hits)
        return hits.also { memories = it }
    }

    /**
     * The chat history a node replays.
     *
     * Compression splits into a run-stable part and a per-node part:
     *  - The cached summary and the compression settings are resolved at most
     *    once per run. They are safe to memoize because the background
     *    compressor is gated off while a pipeline is active (see
     *    ChatHistoryCompressionCoordinator), so the `chat_history_summaries`
     *    row and the settings cannot change mid-run.
     *  - The live message list is re-read on EVERY call and is NOT memoized.
     *    Message-writing nodes mutate it mid-run — in particular a TOOL node's
     *    observation is persisted as an `isFinal = false` SYSTEM chat message
     *    (ToolInvocationGate) — and a later history-enabled node must see it,
     *    so freezing the list here would hide in-run messages until the next
     *    user turn.
     *
     * Not snapshotted for resume — chat history was never resume-stable.
     */
    private suspend fun chatHistoryView(): ChatHistoryView {
        val settings = history ?: loadHistorySettings().also { history = it }
        // Re-read fresh: the list grows as message-writing nodes append rows.
        val messages = sources.chatRepository.getMessagesForSession(sessionId).first()
        val view = sources.chatHistoryWindowPlanner.plan(
            messages = messages,
            summary = settings.summary,
            compressionEnabled = settings.compressionEnabled,
            thresholdTokens = settings.thresholdTokens,
            liveWindowSize = settings.liveWindow,
        )
        // Surface the compression only when it actually changed what the node
        // sees — a within-budget run stays silent — and only once per run.
        if (!historyCompressionLogged && (view.truncatedWithoutSummary || view.earlierSummary != null)) {
            historyCompressionLogged = true
            if (view.truncatedWithoutSummary) {
                console.push(
                    ConsoleEventType.HistoryCompression,
                    "Chat history over budget; summary not ready, kept the last " +
                        "${view.liveWindow.size} messages",
                )
            } else {
                val gap = if (view.droppedUncoveredCount > 0) {
                    " (${view.droppedUncoveredCount} recent messages not yet summarized)"
                } else {
                    ""
                }
                console.push(
                    ConsoleEventType.HistoryCompression,
                    "Chat history compressed: summarized older turns, kept the last " +
                        "${view.liveWindow.size} messages$gap",
                )
            }
        }
        return view
    }

    /** Reads the compression settings and, when compression is on, the cached summary. */
    private suspend fun loadHistorySettings(): HistorySettings {
        val settings = sources.memorySettings
        val enabled = settings.chatHistoryCompressionEnabled.first()
        val summary = if (enabled) {
            try {
                sources.chatRepository.getHistorySummary(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("PipelineDebug").w(e, "Failed to load chat-history summary; continuing without it")
                null
            }
        } else {
            null
        }
        return HistorySettings(
            compressionEnabled = enabled,
            summary = summary,
            thresholdTokens = settings.chatHistoryCompressionThresholdTokens.first(),
            liveWindow = settings.chatHistoryLiveWindowSize.first(),
        )
    }

    /**
     * Whether [node]'s composed input is a prompt for the on-device model, so
     * tool text in it is cut to the user's single-read budget.
     *
     * A CLOUD node, and any node given a cloud provider, runs on a provider's
     * window the user does not size here — it gets tool text whole, bounded by
     * the response budget only. A TOOL node counts like any other: its input
     * never reaches the tool as it is, it is the prompt the model turns into the
     * tool's arguments, on the local model unless the node names a provider.
     *
     * @param node a node whose context is being composed.
     * @return `true` when the node's executor prompts the local model.
     */
    private fun feedsOnDeviceModel(node: NodeModel): Boolean = when (node.type) {
        NodeType.LITE_RT -> true
        NodeType.CLOUD -> false
        else -> node.cloudProvider.isNullOrBlank()
    }

    /**
     * The chat-history inputs that cannot change while a pipeline runs.
     *
     * @property compressionEnabled Whether older turns may be replaced by a summary.
     * @property summary The cached summary, when compression is on and one exists.
     * @property thresholdTokens The history size above which compression applies.
     * @property liveWindow How many recent messages stay verbatim.
     */
    private class HistorySettings(
        val compressionEnabled: Boolean,
        val summary: ChatHistorySummary?,
        val thresholdTokens: Int,
        val liveWindow: Int,
    )

    /**
     * Opens a [NodeInputComposer] for each engine invocation; holds the sources
     * node inputs are composed from.
     *
     * @property chatRepository The session's messages and its history summary.
     * @property memorySettings History compression and memory logging.
     * @property toolSettings The tool-text budget.
     * @property promptTemplateEngine Renders `$VARIABLE` placeholders.
     * @property promptVariableProviders The registered placeholder values.
     * @property nodeContextBuilder Assembles the opted-into blocks in their fixed order.
     * @property chatHistoryWindowPlanner Decides which messages a node replays.
     * @property retrieveRelevantMemoryUseCase Long-term memory search.
     * @property memoryRepository Records which memories a run used.
     */
    @Singleton
    class Factory @Inject constructor(
        internal val chatRepository: ChatRepository,
        internal val memorySettings: MemorySettings,
        internal val toolSettings: ToolSettings,
        internal val promptTemplateEngine: PromptTemplateEngine,
        internal val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
        internal val nodeContextBuilder: NodeContextBuilder,
        internal val chatHistoryWindowPlanner: ChatHistoryWindowPlanner,
        internal val retrieveRelevantMemoryUseCase: RetrieveRelevantMemoryUseCase,
        internal val memoryRepository: MemoryRepository,
    ) {
        /**
         * Opens the composer of one invocation.
         *
         * @param console The invocation's console, for the memory and history notes
         *   and the memory snapshot.
         * @param graph The running graph, whose declared memory query keys retrieval.
         * @param sessionId The chat session whose history nodes replay.
         * @param userPrompt The invocation's prompt: the original task block, and the
         *   interactive retrieval key.
         * @param tree The run tree, for its origin, pending advice and image.
         * @param memorySnapshot The interrupted run's memory, when resuming; `null`
         *   to retrieve on first use.
         * @return A composer with nothing read yet.
         */
        fun open(
            console: RunConsole,
            graph: PipelineGraph,
            sessionId: String,
            userPrompt: String,
            tree: RunTreeContext,
            memorySnapshot: List<MemoryChunk>?,
        ): NodeInputComposer = NodeInputComposer(this, console, graph, sessionId, userPrompt, tree, memorySnapshot)
    }

    private companion object {
        /**
         * Node types whose `systemPrompt` is forwarded to an LLM engine and
         * therefore needs `$VARIABLE` placeholders resolved before execution.
         * Includes [NodeType.LITE_RT], [NodeType.CLOUD], [NodeType.OUTPUT] from
         * the explicit task spec plus the other LLM-driven node types in this
         * codebase (`SUMMARY`, `INTENT_ROUTER`, `DECOMPOSITION`, `EVALUATION`).
         */
        val LLM_NODE_TYPES: Set<NodeType> = setOf(
            NodeType.LITE_RT,
            NodeType.CLOUD,
            NodeType.OUTPUT,
            NodeType.SUMMARY,
            NodeType.INTENT_ROUTER,
            NodeType.DECOMPOSITION,
            NodeType.EVALUATION,
            NodeType.CLARIFICATION,
        )
    }
}
