package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph

/**
 * Checks what an imported pipeline says about itself against what its graph
 * does, before the import use cases store it.
 *
 * Today that is the sample prompts' `toolsHint` — the `uses · …` subtitle on
 * the new-chat cards. `PipelineSamplePrompt` argues the hint is honest because
 * the pipeline that runs the prompt declares it; for a file someone else wrote
 * that argument is the attacker's, and a card saying `uses · read_file` on a
 * pipeline that calls `delete_file` primes the user to read the approval sheet
 * as routine. So a hint keeps only the tools a TOOL node of the same graph is
 * fixed to.
 *
 * Applied by the import use cases, not by [PipelineJsonSerializer]: bundled
 * presets parse through the same serializer, and their hints are the app's own —
 * `tool_using_react` hints `search_tool` for a TOOL node that lets the model
 * choose, which no graph check could confirm.
 */
object ImportedPipelineClaims {

    /**
     * Returns [graph] with every sample prompt's `toolsHint` reduced to the
     * tools the graph's TOOL nodes call, in the hint's order; a hint with none
     * left becomes `null`.
     *
     * A TOOL node that leaves the choice to the model (no `toolName`) backs no
     * name: what it will call cannot be read off the file.
     *
     * @param graph The pipeline as parsed from the imported file.
     * @return The same pipeline with checked hints.
     */
    fun checked(graph: PipelineGraph): PipelineGraph {
        if (graph.samplePrompts.none { it.toolsHint != null }) return graph
        val wired = graph.nodes
            .filter { it.type == NodeType.TOOL }
            .mapNotNullTo(mutableSetOf()) { it.toolName?.takeIf { name -> name.isNotBlank() } }
        return graph.copy(
            samplePrompts = graph.samplePrompts.map { prompt ->
                val kept = prompt.toolsHint
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it in wired }
                    ?.distinct()
                    .orEmpty()
                prompt.copy(toolsHint = kept.takeIf { it.isNotEmpty() }?.joinToString(", "))
            },
        )
    }
}
