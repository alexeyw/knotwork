package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPipelineFromPresetUseCaseTest {

    private val presetRepository: PipelinePresetRepository = mockk(relaxed = true)
    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val useCase = LoadPipelineFromPresetUseCase(presetRepository, pipelineRepository)

    private fun validGraph(): PipelineGraph = PipelineGraph(
        id = "template-id",
        name = "Template",
        nodes = listOf(
            NodeModel(
                id = "tpl-input",
                type = NodeType.INPUT,
                x = 0f,
                y = 0f,
                contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
            ),
            NodeModel(
                id = "tpl-output",
                type = NodeType.OUTPUT,
                x = 100f,
                y = 0f,
                contextConfig = NodeContextConfig.defaultForType(NodeType.OUTPUT),
            ),
        ),
        connections = listOf(
            ConnectionModel(id = "tpl-conn", sourceNodeId = "tpl-input", targetNodeId = "tpl-output", label = "edge"),
        ),
    )

    private fun preset(graph: PipelineGraph = validGraph(), name: String = "Local-only Q&A") = PipelinePreset(
        id = "local_only_qa",
        name = name,
        description = "desc",
        category = PresetCategory.LOCAL,
        graph = graph,
        tags = listOf("offline"),
        isBundled = true,
    )

    @Test
    fun `given missing preset when invoke then returns failure without touching pipeline repository`() = runTest {
        coEvery { presetRepository.getPresetById("nope") } returns null

        val result = useCase("nope")

        assertTrue(result.isFailure)
        assertEquals(
            InputProblem.NotFound(InputProblem.Subject.PRESET, "nope"),
            (result.exceptionOrNull() as InvalidInputException).problem,
        )
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given valid preset when invoke then persists pipeline with fresh ids and returns its id`() = runTest {
        val source = preset()
        coEvery { presetRepository.getPresetById("local_only_qa") } returns source
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("local_only_qa")

        assertTrue(result.isSuccess)
        val newId = result.getOrNull()
        assertNotNull(newId)
        assertEquals(newId, saved.captured.id)
        // Pipeline id is regenerated.
        assertNotEquals("template-id", saved.captured.id)
        // Every node id is regenerated.
        assertTrue(saved.captured.nodes.none { it.id == "tpl-input" || it.id == "tpl-output" })
        // Connection ids are regenerated; source/target are remapped; label preserved.
        val connection = saved.captured.connections.single()
        assertNotEquals("tpl-conn", connection.id)
        val newInput = saved.captured.nodes.first { it.type == NodeType.INPUT }
        val newOutput = saved.captured.nodes.first { it.type == NodeType.OUTPUT }
        assertEquals(newInput.id, connection.sourceNodeId)
        assertEquals(newOutput.id, connection.targetNodeId)
        assertEquals("edge", connection.label)
    }

    @Test
    fun `given preset with sample prompts when invoke then the materialized pipeline keeps them`() = runTest {
        val prompts = listOf(
            PipelineSamplePrompt(title = "Look up benchmarks", toolsHint = "search_tool"),
            PipelineSamplePrompt(title = "Explain on-device inference"),
        )
        coEvery { presetRepository.getPresetById("local_only_qa") } returns
            preset(graph = validGraph().copy(samplePrompts = prompts))
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("local_only_qa")

        assertTrue(result.isSuccess)
        assertEquals(prompts, saved.captured.samplePrompts)
    }

    @Test
    fun `given preset with a declared memory query when invoke then the materialized pipeline keeps it`() = runTest {
        // A materialized preset must behave like its template on background
        // runs too — the declared retrieval key is not template-only metadata.
        coEvery { presetRepository.getPresetById("local_only_qa") } returns
            preset(graph = validGraph().copy(memoryRetrievalQuery = "evening journal entries"))
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("local_only_qa")

        assertTrue(result.isSuccess)
        assertEquals("evening journal entries", saved.captured.memoryRetrievalQuery)
    }

    @Test
    fun `given preset with orphan connections when invoke then drops orphans and keeps the rest`() = runTest {
        val orphanedGraph = validGraph().copy(
            connections = listOf(
                ConnectionModel(id = "tpl-conn", sourceNodeId = "tpl-input", targetNodeId = "tpl-output"),
                ConnectionModel(id = "ghost-1", sourceNodeId = "tpl-input", targetNodeId = "ghost"),
                ConnectionModel(id = "ghost-2", sourceNodeId = "ghost", targetNodeId = "tpl-output"),
            ),
        )
        coEvery { presetRepository.getPresetById("p") } returns preset(graph = orphanedGraph)
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("p")

        assertTrue(result.isSuccess)
        assertEquals(1, saved.captured.connections.size)
    }

    @Test
    fun `given template that fails validation when invoke then returns PipelineValidationException`() = runTest {
        // INPUT-only graph: missing OUTPUT triggers a validation error.
        val brokenGraph = PipelineGraph(
            id = "broken",
            name = "Broken",
            nodes = listOf(
                NodeModel(id = "i", type = NodeType.INPUT, x = 0f, y = 0f),
            ),
        )
        coEvery { presetRepository.getPresetById("p") } returns preset(graph = brokenGraph)

        val result = useCase("p")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PipelineValidationException)
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given long preset name when invoke then truncates resulting pipeline name to MAX_NAME_LENGTH`() = runTest {
        val longName = "x".repeat(80)
        coEvery { presetRepository.getPresetById("p") } returns preset(name = longName)
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("p")

        assertTrue(result.isSuccess)
        assertEquals(60, saved.captured.name.length)
        assertFalse(saved.captured.name.endsWith(" "))
    }

    @Test
    fun `given valid preset when materialize then returns regenerated graph without persisting`() = runTest {
        coEvery { presetRepository.getPresetById("local_only_qa") } returns preset()

        val result = useCase.materialize("local_only_qa")

        assertTrue(result.isSuccess)
        val graph = result.getOrNull()!!
        // Ids are regenerated…
        assertNotEquals("template-id", graph.id)
        assertTrue(graph.nodes.none { it.id == "tpl-input" || it.id == "tpl-output" })
        // …and nothing is written to the repository (the caller decides where it lands).
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given missing preset when materialize then returns failure`() = runTest {
        coEvery { presetRepository.getPresetById("nope") } returns null

        val result = useCase.materialize("nope")

        assertTrue(result.isFailure)
        assertEquals(
            InputProblem.NotFound(InputProblem.Subject.PRESET, "nope"),
            (result.exceptionOrNull() as InvalidInputException).problem,
        )
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given save fails when invoke then propagates failure`() = runTest {
        coEvery { presetRepository.getPresetById("p") } returns preset()
        coEvery { pipelineRepository.savePipeline(any()) } throws RuntimeException("io")

        val result = useCase("p")

        assertTrue(result.isFailure)
        assertEquals("io", result.exceptionOrNull()?.message)
    }

    /** Composed parent preset: INPUT → PIPELINE(target) → OUTPUT, validating cleanly. */
    private fun composedPreset(targetId: String = "subtask_clarify"): PipelinePreset {
        val graph = PipelineGraph(
            id = "composed-tpl",
            name = "Composed",
            nodes = listOf(
                NodeModel(
                    id = "c-in",
                    type = NodeType.INPUT,
                    x = 0f,
                    y = 0f,
                    contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
                ),
                NodeModel(
                    id = "c-pipe",
                    type = NodeType.PIPELINE,
                    x = 50f,
                    y = 0f,
                    targetPipelineId = targetId,
                    contextConfig = NodeContextConfig(
                        chatHistory = false,
                        originalTask = true,
                        nodeInput = true,
                        longTermMemory = false,
                        toolResults = false,
                    ),
                ),
                NodeModel(
                    id = "c-out",
                    type = NodeType.OUTPUT,
                    x = 100f,
                    y = 0f,
                    contextConfig = NodeContextConfig.defaultForType(NodeType.OUTPUT),
                ),
            ),
            connections = listOf(
                ConnectionModel(id = "c1", sourceNodeId = "c-in", targetNodeId = "c-pipe"),
                ConnectionModel(id = "c2", sourceNodeId = "c-pipe", targetNodeId = "c-out"),
            ),
        )
        return PipelinePreset(
            id = "showcase_full_agent",
            name = "Showcase",
            description = "d",
            category = PresetCategory.OTHER,
            graph = graph,
            tags = emptyList(),
            isBundled = true,
        )
    }

    @Test
    fun `given composed preset whose sub-pipeline is absent when invoke then seeds it under its stable preset id`() =
        runTest {
            coEvery { presetRepository.getPresetById("showcase_full_agent") } returns composedPreset()
            coEvery { presetRepository.getPresetById("subtask_clarify") } returns
                preset().copy(id = "subtask_clarify", name = "Subtask — Clarify")
            coEvery { pipelineRepository.getPipelineById("subtask_clarify") } returns null
            val saved = mutableListOf<PipelineGraph>()
            coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

            val result = useCase("showcase_full_agent")

            assertTrue(result.isSuccess)
            // The dependency is persisted under the stable preset id the parent references…
            assertTrue("sub-pipeline must be seeded under its stable id", saved.any { it.id == "subtask_clarify" })
            // …and the parent keeps the flat target reference, under its own fresh id.
            val parent = saved.first { it.id == result.getOrNull() }
            assertEquals(
                "subtask_clarify",
                parent.nodes.first { it.type == NodeType.PIPELINE }.targetPipelineId,
            )
        }

    @Test
    fun `given composed preset whose sub-pipeline already exists when invoke then does not reseed it`() = runTest {
        coEvery { presetRepository.getPresetById("showcase_full_agent") } returns composedPreset()
        coEvery { pipelineRepository.getPipelineById("subtask_clarify") } returns
            validGraph().copy(id = "subtask_clarify")
        val saved = mutableListOf<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("showcase_full_agent")

        assertTrue(result.isSuccess)
        // The already-present sub-pipeline is left untouched (only the parent is saved),
        // and its preset is never even looked up because the saved-pipeline check wins first.
        assertTrue("must not overwrite an existing sub-pipeline", saved.none { it.id == "subtask_clarify" })
        coVerify(exactly = 0) { presetRepository.getPresetById("subtask_clarify") }
    }

    @Test
    fun `given composed preset whose target is not a known preset when invoke then skips seeding and saves parent`() =
        runTest {
            coEvery { presetRepository.getPresetById("showcase_full_agent") } returns
                composedPreset(targetId = "ghost_pipeline")
            coEvery { pipelineRepository.getPipelineById("ghost_pipeline") } returns null
            coEvery { presetRepository.getPresetById("ghost_pipeline") } returns null
            val saved = mutableListOf<PipelineGraph>()
            coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

            val result = useCase("showcase_full_agent")

            assertTrue(result.isSuccess)
            // A dangling target is not seeded; the parent still persists (the missing
            // target surfaces later through composition validation, not here).
            assertTrue(saved.none { it.id == "ghost_pipeline" })
            assertEquals(1, saved.size)
        }
}
