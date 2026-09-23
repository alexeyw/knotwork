package app.knotwork.android.domain.pipelineio

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineSamplePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [ImportedPipelineClaims] — an imported pipeline's `uses · …` hints keep only
 * the tools its own TOOL nodes call.
 */
class ImportedPipelineClaimsTest {

    private fun graph(vararg toolNames: String?, prompts: List<PipelineSamplePrompt>) = PipelineGraph(
        id = "p",
        name = "p",
        nodes = toolNames.mapIndexed { i, name ->
            NodeModel(id = "t$i", type = NodeType.TOOL, x = 0f, y = 0f, toolName = name)
        } + NodeModel(id = "llm", type = NodeType.LITE_RT, x = 0f, y = 0f, toolName = "write_file"),
        updatedAt = 0L,
        samplePrompts = prompts,
    )

    @Test
    fun `given hints naming wired and unwired tools when checked then only wired ones remain in order`() {
        val checked = ImportedPipelineClaims.checked(
            graph(
                "search_tool",
                "append_file",
                prompts = listOf(PipelineSamplePrompt("a", "read_file, append_file,  search_tool, append_file")),
            ),
        )

        assertEquals("append_file, search_tool", checked.samplePrompts.single().toolsHint)
    }

    @Test
    fun `given a TOOL node that lets the model choose when checked then it backs no hint`() {
        val checked = ImportedPipelineClaims.checked(
            graph(null, " ", prompts = listOf(PipelineSamplePrompt("a", "search_tool"))),
        )

        assertNull(checked.samplePrompts.single().toolsHint)
    }

    @Test
    fun `given a tool name on a non-TOOL node when checked then it backs no hint`() {
        // The LITE_RT node in the fixture carries toolName = write_file; only a
        // TOOL node calls a tool.
        val checked = ImportedPipelineClaims.checked(graph(prompts = listOf(PipelineSamplePrompt("a", "write_file"))))

        assertNull(checked.samplePrompts.single().toolsHint)
    }

    @Test
    fun `given no hints when checked then the graph is returned as it was`() {
        val original = graph("search_tool", prompts = listOf(PipelineSamplePrompt("a")))

        assertSame(original, ImportedPipelineClaims.checked(original))
    }
}
