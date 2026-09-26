package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.ToolInvocationResult

/**
 * Immutable snapshot of all pipeline-scoped data that
 * [NodeContextBuilder] can splice into a node's input string.
 *
 * One instance is rebuilt by [GraphExecutionEngine] before every node iteration so
 * each node sees up-to-date chat history, the latest preceding-node output, and
 * any tool results that have been produced earlier in the run. The builder picks
 * which fields to emit based on the node's [app.knotwork.android.domain.models.NodeContextConfig].
 *
 * @property originalUserMessage The user prompt that started this pipeline run.
 * Captured at the engine's entry point and never mutated — even nodes deep in
 * the chain still see the original intent here, regardless of what previous
 * nodes wrote downstream.
 * @property chatHistory The chat messages rendered verbatim under
 * `--- Chat History ---`. When history compression is inactive this is the full
 * persisted history for the active session; when it is active this is only the
 * live window of recent messages (the older tail is represented by
 * [earlierSummary]). Empty list when the session has no prior messages.
 * @property earlierSummary Prose summary of the conversation older than
 * [chatHistory], rendered as `--- Earlier conversation (summarized) ---` ahead
 * of the live window. `null` when compression is off, the history is within
 * budget, or no summary has been computed yet.
 * @property previousNodeOutput The text produced by the immediately preceding
 * node (or the user prompt itself for the first iteration). This is the canonical
 * "what just happened" payload threaded through a chain.
 * @property toolResults Tool invocations that completed successfully earlier in
 * the same pipeline run, in execution order. Empty when no tool node has fired
 * yet.
 * @property memoryEntries Long-term memory entries deemed relevant to
 * [originalUserMessage] (semantic similarity above the configured threshold).
 * Empty list when retrieval is disabled, fails, or returns nothing.
 * @property toolResultCharBudget Longest tool text, in characters, the node may
 * receive: each tool result, a [previousNodeOutput] that is a tool's result
 * verbatim, and a `SYSTEM` row of [chatHistory] (the record of a tool call) are
 * cut to it with a note saying how much was left out. Set for a node that
 * prompts the on-device model, whose context window the user sizes — a TOOL
 * node included, since its input is the prompt its arguments are generated
 * from; `null` — nothing cut — for a node on a cloud provider.
 */
data class PipelineExecutionContext(
    val originalUserMessage: String,
    val chatHistory: List<ChatMessage>,
    val previousNodeOutput: String,
    val toolResults: List<ToolInvocationResult>,
    val memoryEntries: List<MemoryChunk>,
    val earlierSummary: String? = null,
    val toolResultCharBudget: Int? = null,
)
