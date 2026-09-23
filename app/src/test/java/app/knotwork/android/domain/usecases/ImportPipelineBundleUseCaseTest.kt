package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.pipelineio.PipelineBundleJsonSerializer
import app.knotwork.android.domain.pipelineio.PipelineBundleTestFixtures.linearGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.PipelineCompositionValidator
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ImportPipelineBundleUseCase] — the prepare (parse + validate +
 * collision-detect) and persist (id policy + atomic write) steps.
 */
class ImportPipelineBundleUseCaseTest {

    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var findPipelineBindings: FindPipelineBindingsUseCase
    private lateinit var useCase: ImportPipelineBundleUseCase

    @Before
    fun setup() {
        pipelineRepository = mockk()
        settingsRepository = mockk()
        // Real composition validator so intra-bundle cycles/depth are exercised
        // end-to-end. Descendants outside the bundle resolve to null.
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
        coEvery { pipelineRepository.getPipelineById(any()) } returns null
        every { pipelineRepository.observePipelineNames() } returns flowOf(emptyMap())
        coEvery { pipelineRepository.savePipelines(any()) } returns Unit
        findPipelineBindings = mockk()
        coEvery { findPipelineBindings(any()) } answers
            { firstArg<Collection<String>>().associateWith { PipelineBindings() } }
        useCase = ImportPipelineBundleUseCase(
            pipelineRepository,
            PipelineCompositionValidator(pipelineRepository, settingsRepository),
            findPipelineBindings,
        )
    }

    private fun bundleOf(vararg graphs: PipelineGraph): String =
        PipelineBundleJsonSerializer.serialize(graphs.toList(), exportedAt = 0L)

    @Test
    fun `given malformed bundle when prepare then Failure`() = runTest {
        assertTrue(useCase.prepare("{ not json") is PipelineBundlePrepareResult.Failure)
    }

    @Test
    fun `given a structurally invalid graph when prepare then Failure`() = runTest {
        // A graph that parses but fails validate(): INPUT with no OUTPUT.
        val invalid = PipelineGraph(
            id = "bad",
            name = "bad",
            nodes = listOf(NodeModel(id = "in", type = NodeType.INPUT, x = 0f, y = 0f)),
            connections = emptyList(),
            updatedAt = 0L,
        )

        assertTrue(useCase.prepare(bundleOf(invalid)) is PipelineBundlePrepareResult.Failure)
    }

    @Test
    fun `given an intra-bundle cycle when prepare then Failure`() = runTest {
        // a -> b -> a : a cross-pipeline cycle the single-graph validator can't see.
        val json = bundleOf(linearGraph("a", targets = listOf("b")), linearGraph("b", targets = listOf("a")))

        assertTrue(useCase.prepare(json) is PipelineBundlePrepareResult.Failure)
    }

    @Test
    fun `given a bundle pipeline hinting a tool it does not call when prepare then the hint is dropped`() = runTest {
        val graph = PipelineGraph(
            id = "p1",
            name = "Hinted",
            nodes = listOf(
                NodeModel(id = "in", type = NodeType.INPUT, x = 0f, y = 0f),
                NodeModel(id = "t", type = NodeType.TOOL, x = 0f, y = 0f, toolName = "delete_file"),
                NodeModel(id = "out", type = NodeType.OUTPUT, x = 0f, y = 0f),
            ),
            connections = listOf(
                ConnectionModel(id = "c1", sourceNodeId = "in", targetNodeId = "t"),
                ConnectionModel(id = "c2", sourceNodeId = "t", targetNodeId = "out"),
            ),
            updatedAt = 0L,
            samplePrompts = listOf(PipelineSamplePrompt(title = "Tidy up my notes", toolsHint = "read_file")),
        )

        val ready = useCase.prepare(bundleOf(graph)) as PipelineBundlePrepareResult.Ready

        assertNull(ready.pipelines.single().samplePrompts.single().toolsHint)
    }

    @Test
    fun `given a collision when prepare then it is reported and nothing persisted`() = runTest {
        every { pipelineRepository.observePipelineNames() } returns flowOf(mapOf("root" to "Root"))
        val json = bundleOf(linearGraph("root", targets = listOf("sub")), linearGraph("sub"))

        val prepared = useCase.prepare(json)

        assertTrue(prepared is PipelineBundlePrepareResult.Ready)
        assertEquals(listOf("root"), (prepared as PipelineBundlePrepareResult.Ready).collisions.map { it.incoming.id })
        coVerify(exactly = 0) { pipelineRepository.savePipelines(any()) }
    }

    @Test
    fun `given a collision when prepare then it names the library pipeline and what is bound to it`() = runTest {
        every { pipelineRepository.observePipelineNames() } returns flowOf(mapOf("sub" to "Act on the task"))
        coEvery { findPipelineBindings(listOf("sub")) } returns
            mapOf("sub" to PipelineBindings(callerNames = listOf("Full agent")))
        val json = bundleOf(linearGraph("root", targets = listOf("sub")), linearGraph("sub"))

        val collision = (useCase.prepare(json) as PipelineBundlePrepareResult.Ready).collisions.single()

        assertEquals("sub", collision.incoming.id)
        assertEquals("Act on the task", collision.existingName)
        assertEquals(listOf("Full agent"), collision.bindings.callerNames)
    }

    @Test
    fun `given REPLACE when persist then pipeline ids preserved but node ids freshened, saved atomically`() = runTest {
        val saved: CapturingSlot<List<PipelineGraph>> = slot()
        coEvery { pipelineRepository.savePipelines(capture(saved)) } returns Unit
        val pipelines = listOf(linearGraph("root", targets = listOf("sub")), linearGraph("sub"))

        val result = useCase.persist(pipelines, ImportCollisionResolution.REPLACE)

        assertEquals(2, result.getOrThrow().size)
        assertEquals(setOf("root", "sub"), saved.captured.map { it.id }.toSet())
        coVerify(exactly = 1) { pipelineRepository.savePipelines(any()) }
    }

    @Test
    fun `given bundle pipelines reusing a node id when persist REPLACE then saved node ids are globally unique`() =
        runTest {
            val saved: CapturingSlot<List<PipelineGraph>> = slot()
            coEvery { pipelineRepository.savePipelines(capture(saved)) } returns Unit
            // Two pipelines that both use the node id "node-1" (the shipped-preset pattern).
            val a = PipelineGraph(
                id = "a",
                name = "A",
                nodes = listOf(
                    NodeModel(id = "node-1", type = NodeType.INPUT, x = 0f, y = 0f),
                    NodeModel(id = "node-2", type = NodeType.OUTPUT, x = 0f, y = 0f),
                ),
                connections = listOf(ConnectionModel(id = "conn-1", sourceNodeId = "node-1", targetNodeId = "node-2")),
            )
            val b = a.copy(id = "b", name = "B")

            useCase.persist(listOf(a, b), ImportCollisionResolution.REPLACE)

            val allNodeIds = saved.captured.flatMap { g -> g.nodes.map { it.id } }
            assertEquals("no node id may repeat across pipelines", allNodeIds.size, allNodeIds.toSet().size)
            val allConnIds = saved.captured.flatMap { g -> g.connections.map { it.id } }
            assertEquals("no connection id may repeat across pipelines", allConnIds.size, allConnIds.toSet().size)
        }

    @Test
    fun `given IMPORT_AS_COPY when persist then ids are regenerated and references stay consistent`() = runTest {
        val saved: CapturingSlot<List<PipelineGraph>> = slot()
        coEvery { pipelineRepository.savePipelines(capture(saved)) } returns Unit
        val pipelines = listOf(linearGraph("root", targets = listOf("sub")), linearGraph("sub"))

        useCase.persist(pipelines, ImportCollisionResolution.IMPORT_AS_COPY)

        val savedIds = saved.captured.map { it.id }.toSet()
        assertTrue("original ids must not be reused", savedIds.none { it == "root" || it == "sub" })
        // The remapped root target must resolve to the remapped sub-pipeline id.
        val newSubId = saved.captured.first { it.name == "name-sub" }.id
        val remappedTarget = saved.captured.first { it.name == "name-root" }
            .nodes.first { it.type == NodeType.PIPELINE }.targetPipelineId
        assertEquals(newSubId, remappedTarget)
    }

    @Test
    fun `given save failure when persist then Result failure`() = runTest {
        coEvery { pipelineRepository.savePipelines(any()) } throws RuntimeException("disk full")

        val result = useCase.persist(listOf(linearGraph("root")), ImportCollisionResolution.REPLACE)

        assertTrue(result.isFailure)
    }
}
