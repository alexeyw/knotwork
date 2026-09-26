package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeType

/**
 * Decides whether the text a run carries from node to node was written by a model.
 *
 * Several nodes hand on the text they received: INPUT, IF_CONDITION,
 * QUEUE_PROCESSOR, an INTENT_ROUTER (which forwards its input, not its routing
 * label) and an OUTPUT in echo mode. So the message a run leaves in the chat as
 * the assistant's can be a tool's result — or the user's own words — verbatim.
 * Long-term memory extraction must not read that as something the assistant
 * said; the engine tracks authorship with this rule, the root OUTPUT records it
 * on the row ([app.knotwork.android.domain.models.ChatMessage.relayed]).
 */
object ModelAuthorship {

    /**
     * Node types whose new output text a model generates. A CLARIFICATION's output
     * pairs the question with the user's answer, and a PIPELINE's is whatever its
     * sub-pipeline ended on, so neither is here: unknown authorship counts as not
     * a model's.
     */
    private val MODEL_TEXT_TYPES: Set<NodeType> = setOf(
        NodeType.LITE_RT,
        NodeType.CLOUD,
        NodeType.SUMMARY,
        NodeType.DECOMPOSITION,
        NodeType.EVALUATION,
        NodeType.OUTPUT,
        NodeType.SKILL,
    )

    /**
     * Whether the text a node hands on was written by a model.
     *
     * @param type the node that just ran.
     * @param result what it returned, or `null` when it returned nothing.
     * @param input the text the run carried into the node.
     * @param inputByModel whether [input] was written by a model.
     * @return [inputByModel] when the node hands on [input] unchanged (no output, an
     *   INTENT_ROUTER, or the same text back); `false` for a tool's result, whichever
     *   node dispatched it; otherwise whether [type] generates text with a model.
     */
    fun after(type: NodeType, result: NodeExecutionResult?, input: String, inputByModel: Boolean): Boolean {
        val output = result?.outputText ?: return inputByModel
        return when {
            type == NodeType.INTENT_ROUTER -> inputByModel
            result.resolvedToolName != null -> false
            output == input -> inputByModel
            else -> type in MODEL_TEXT_TYPES
        }
    }
}
