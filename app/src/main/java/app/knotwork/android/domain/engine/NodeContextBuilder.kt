package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.structured.ReasoningBlockSplitter
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.prompt.ChatTranscript
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the textual input that [GraphExecutionEngine] feeds into a node's
 * executor by concatenating only the context blocks the node opted into via
 * its [NodeContextConfig].
 *
 * The builder is the single source of truth for context layout: every block
 * uses the same delimiter style (`--- <Block Name> ---`) and the order is
 * fixed regardless of which subset of flags is enabled. The deterministic
 * order matters because:
 *
 *  1. Prompt caches downstream (Anthropic, OpenAI, on-device LiteRT) hash a
 *     prefix of the prompt; reordering blocks would invalidate the cache.
 *  2. LLMs are sensitive to position effects — keeping the user-facing
 *     payload (`Previous Node Output`) at the end mirrors a typical
 *     "system → context → user message" arrangement.
 *
 * Block order (top to bottom):
 *
 *  1. **Original Task** — the immutable user message that started the run.
 *     Sets the goal up-front so the LLM does not lose sight of it after
 *     several intermediate transformations.
 *  2. **Earlier conversation (summarized)** — a prose summary standing in for
 *     the part of the history older than the live window, present only when
 *     history compression is active. Occupies the chat-history slot, rendered
 *     immediately before the verbatim Chat History block so the overall block
 *     order is unchanged.
 *  3. **Chat History** — prior session messages (numbered). When compression is
 *     active this is only the live window; otherwise the full history.
 *  4. **Long-Term Memory** — semantically retrieved memory chunks (numbered).
 *  5. **Tool Results** — outputs from tool invocations earlier in this run,
 *     each cut to [PipelineExecutionContext.toolResultCharBudget] when set.
 *  6. **Previous Node Output** — the immediate predecessor's payload, the
 *     thing the current node is expected to act on.
 *
 * Empty data blocks (e.g. `chatHistory = true` but the session has no
 * messages yet) are dropped entirely — emitting a header without content is
 * pure noise for the model. If every enabled block is empty (or no flags are
 * enabled at all) the builder returns an empty string; callers must decide
 * how to handle that, since the ban on fully-empty contexts is enforced at
 * the validation layer.
 */
@Singleton
class NodeContextBuilder @Inject constructor() {

    /**
     * Renders the assembled context string for a single node execution.
     *
     * @param config The node's per-node selection of enabled context blocks.
     * @param ctx Snapshot of pipeline-scoped data captured by the engine just
     * before the node fires.
     * @return The concatenated context string, with blocks separated by a
     * blank line. May be empty if no enabled flag yields any content.
     */
    fun build(config: NodeContextConfig, ctx: PipelineExecutionContext): String {
        val blocks = mutableListOf<String>()

        if (config.originalTask && ctx.originalUserMessage.isNotBlank()) {
            blocks += renderBlock(HEADER_ORIGINAL_TASK, ctx.originalUserMessage.trim())
        }

        if (config.chatHistory) {
            // The summarised-tail block sits in the chat-history slot, ahead of
            // the verbatim live window, so the fixed §6.2 block order is kept.
            // It is gated on the same `chatHistory` flag — a node that opted out
            // of history must not receive a summary of it either.
            ctx.earlierSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                blocks += renderBlock(HEADER_EARLIER_SUMMARY, summary.trim())
            }
            if (ctx.chatHistory.isNotEmpty()) {
                blocks += renderBlock(HEADER_CHAT_HISTORY, formatChatHistory(ctx.chatHistory, ctx.toolResultCharBudget))
            }
        }

        if (config.longTermMemory && ctx.memoryEntries.isNotEmpty()) {
            blocks += renderBlock(HEADER_LONG_TERM_MEMORY, formatMemory(ctx.memoryEntries))
        }

        if (config.toolResults && ctx.toolResults.isNotEmpty()) {
            blocks += renderBlock(HEADER_TOOL_RESULTS, formatToolResults(ctx.toolResults, ctx.toolResultCharBudget))
        }

        if (config.nodeInput && ctx.previousNodeOutput.isNotBlank()) {
            // A tool's result verbatim — the engine forwards a TOOL node's output
            // unchanged, through pass-through nodes too — gets the same bound as
            // in the Tool Results block. Any other payload is left whole.
            val producedByTool = ctx.toolResults.any { it.output == ctx.previousNodeOutput }
            val previous = ctx.previousNodeOutput.trim()
            val body = if (producedByTool) boundToolText(previous, ctx.toolResultCharBudget) else previous
            blocks += renderBlock(HEADER_PREVIOUS_NODE_OUTPUT, body)
        }

        return blocks.joinToString(BLOCK_SEPARATOR)
    }

    private fun renderBlock(header: String, body: String): String = "$header\n$body"

    /**
     * Renders the live window as a numbered transcript.
     *
     * Agent turns are passed through [ReasoningBlockSplitter] on the way out.
     * New answers are already split at the executor that produced them, so this
     * is not the fix — it is what stops a chat that predates the fix from going
     * on poisoning every later turn: a `<think>` block stored in an old message
     * would otherwise be replayed into the prompt for the rest of that chat's
     * life. Stored messages are deliberately left as they were written; nothing
     * is rewritten behind the user's back for a display concern.
     *
     * Every list the builder renders (history, memory, tool results) goes through
     * [ChatTranscript]: continuation lines are indented, so stored content — tool
     * output above all — cannot open a turn, an entry or a `--- Block ---` header
     * of its own.
     *
     * A `SYSTEM` row is the app's own record — for a tool call, `Observation from
     * <tool>: <result>`, the result whole — so it gets the tool-text [budget];
     * the user's and the model's turns are left as written.
     */
    private fun formatChatHistory(messages: List<ChatMessage>, budget: Int?): String =
        messages.mapIndexed { index, message ->
            val content = ReasoningBlockSplitter.split(message.content).answer
            ChatTranscript.turn(
                label = "${index + 1}. ${message.role.name}",
                content = if (message.role == Role.SYSTEM) boundToolText(content, budget) else content,
            )
        }.joinToString("\n")

    private fun formatMemory(entries: List<MemoryChunk>): String =
        entries.mapIndexed { index, chunk -> ChatTranscript.entry("${index + 1}. ", chunk.text) }.joinToString("\n")

    private fun formatToolResults(results: List<ToolInvocationResult>, budget: Int?): String =
        results.mapIndexed { index, result ->
            ChatTranscript.turn(
                label = "${index + 1}. ${result.toolName}",
                content = boundToolText(result.output, budget),
            )
        }.joinToString("\n")

    /**
     * Cuts tool text to [budget] characters and says how much was left out.
     *
     * The response budget of `http_request` and MCP bounds what the app holds —
     * a megabyte by default, hundreds of thousands of tokens — not what fits
     * the on-device model's context; this is the bound that does. Text within
     * the budget plus [OWN_NOTE_ALLOWANCE] is left alone, so a result its tool
     * already cut to the same budget (`read_file` does, then says which offset
     * continues it) keeps its own note. The cut never splits a surrogate pair.
     *
     * @param text a tool's result.
     * @param budget longest text to keep, in characters; `null` keeps it whole.
     * @return [text] itself, or its first [budget] characters and the note.
     */
    private fun boundToolText(text: String, budget: Int?): String {
        if (budget == null || text.length <= budget + OWN_NOTE_ALLOWANCE) return text
        var cut = budget.coerceAtLeast(1)
        if (text[cut - 1].isHighSurrogate()) cut--
        return text.substring(0, cut) +
            "\n[... ${text.length - cut} more characters of this tool result were cut " +
            "to fit the on-device model's context]"
    }

    private companion object {
        private const val HEADER_ORIGINAL_TASK = "--- Original Task ---"
        private const val HEADER_EARLIER_SUMMARY = "--- Earlier conversation (summarized) ---"
        private const val HEADER_CHAT_HISTORY = "--- Chat History ---"
        private const val HEADER_LONG_TERM_MEMORY = "--- Long-Term Memory ---"
        private const val HEADER_TOOL_RESULTS = "--- Tool Results ---"
        private const val HEADER_PREVIOUS_NODE_OUTPUT = "--- Previous Node Output ---"
        private const val BLOCK_SEPARATOR = "\n\n"

        /**
         * Characters a tool result may run past the budget and stay whole: room
         * for the note a tool appends after cutting to the same budget itself.
         */
        private const val OWN_NOTE_ALLOWANCE = 256
    }
}
