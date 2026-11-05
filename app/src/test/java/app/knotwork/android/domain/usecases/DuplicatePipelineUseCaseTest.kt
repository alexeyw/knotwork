package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.services.EnglishDefaultNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePipelineUseCaseTest {

    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val useCase = DuplicatePipelineUseCase(pipelineRepository, EnglishDefaultNames)

    @Test
    fun `given missing pipeline when invoke then returns failure`() = runTest {
        coEvery { pipelineRepository.getPipelineById("missing") } returns null

        val result = useCase("missing")

        assertTrue(result.isFailure)
        assertEquals(
            InputProblem.NotFound(InputProblem.Subject.PIPELINE),
            (result.exceptionOrNull() as InvalidInputException).problem,
        )
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given existing pipeline when invoke then saves duplicate with new ids and copy suffix`() = runTest {
        val source = PipelineGraph(
            id = "src",
            name = "Source",
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
            ),
            connections = listOf(
                ConnectionModel("c1", "n1", "n2", label = "edge"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("src") } returns source
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("src")

        assertTrue(result.isSuccess)
        val duplicate = result.getOrNull()
        assertNotNull(duplicate)
        duplicate!!

        // The persisted graph matches the returned graph.
        assertEquals(duplicate, saved.captured)

        // Pipeline-level id and name follow the contract.
        assertNotEquals("src", duplicate.id)
        assertEquals("Source (copy)", duplicate.name)

        // Node ids are remapped; types / coordinates are preserved.
        assertEquals(2, duplicate.nodes.size)
        assertTrue(duplicate.nodes.none { it.id == "n1" || it.id == "n2" })
        val newInput = duplicate.nodes.first { it.type == NodeType.INPUT }
        val newOutput = duplicate.nodes.first { it.type == NodeType.OUTPUT }

        // Connections are remapped to point to the new node ids and have fresh ids.
        assertEquals(1, duplicate.connections.size)
        val connection = duplicate.connections.single()
        assertNotEquals("c1", connection.id)
        assertEquals(newInput.id, connection.sourceNodeId)
        assertEquals(newOutput.id, connection.targetNodeId)
        assertEquals("edge", connection.label)
    }

    @Test
    fun `given source declares a memory retrieval query when invoke then the copy keeps it`() = runTest {
        // Pipeline-level behavioural metadata must travel with a copy; dropping
        // it would silently change how the duplicate searches memory.
        val source = PipelineGraph(
            id = "src",
            name = "Source",
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
            ),
            memoryRetrievalQuery = "evening journal entries",
        )
        coEvery { pipelineRepository.getPipelineById("src") } returns source
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("src")

        assertTrue(result.isSuccess)
        assertEquals("evening journal entries", saved.captured.memoryRetrievalQuery)
    }

    @Test
    fun `given save failure when invoke then propagates failure`() = runTest {
        val source = PipelineGraph(
            id = "src",
            name = "Source",
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
            ),
            connections = listOf(ConnectionModel("c1", "n1", "n2")),
        )
        coEvery { pipelineRepository.getPipelineById("src") } returns source
        coEvery { pipelineRepository.savePipeline(any()) } throws RuntimeException("io error")

        val result = useCase("src")

        assertTrue(result.isFailure)
        assertEquals("io error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `given lookup throws when invoke then returns failure rather than throwing`() = runTest {
        coEvery { pipelineRepository.getPipelineById("src") } throws RuntimeException("db closed")

        val result = useCase("src")

        assertTrue(result.isFailure)
        assertEquals("db closed", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given source name near limit when invoke then truncates base before appending copy suffix`() = runTest {
        // 58 chars of "a" → would be 65 total with " (copy)" (length 7), past the
        // 60-char shared limit. The base must be truncated to 53 ("60 − 7") so the
        // final name fits.
        val longName = "a".repeat(58)
        val source = PipelineGraph(
            id = "src",
            name = longName,
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
            ),
            connections = listOf(ConnectionModel("c1", "n1", "n2")),
        )
        coEvery { pipelineRepository.getPipelineById("src") } returns source
        coEvery { pipelineRepository.savePipeline(any()) } returns Unit

        val result = useCase("src")

        assertTrue(result.isSuccess)
        val duplicateName = result.getOrNull()?.name
        assertNotNull(duplicateName)
        assertEquals(60, duplicateName!!.length)
        assertTrue(duplicateName.endsWith(" (copy)"))
        assertEquals("a".repeat(53) + " (copy)", duplicateName)
    }

    @Test
    fun `given source connection has label when invoke then preserves it via copy()`() = runTest {
        val source = PipelineGraph(
            id = "src",
            name = "Source",
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
            ),
            connections = listOf(
                ConnectionModel("c1", "n1", "n2", label = "True"),
            ),
        )
        coEvery { pipelineRepository.getPipelineById("src") } returns source
        coEvery { pipelineRepository.savePipeline(any()) } returns Unit

        val result = useCase("src")

        assertTrue(result.isSuccess)
        val duplicateConnection = result.getOrNull()?.connections?.single()
        assertNotNull(duplicateConnection)
        assertEquals("True", duplicateConnection!!.label)
    }

    @Test
    fun `given source has connections referencing missing node ids when invoke then drops them and returns success`() =
        runTest {
            // The source graph has a "dangling" connection — its target id
            // (`ghost`) is not present in the nodes list. Without the guard the
            // `getValue` lookup would throw and the whole duplicate would crash.
            val source = PipelineGraph(
                id = "src",
                name = "Source",
                nodes = listOf(
                    NodeModel("n1", NodeType.INPUT, 0f, 0f),
                    NodeModel("n2", NodeType.OUTPUT, 10f, 10f),
                ),
                connections = listOf(
                    ConnectionModel("c1", "n1", "n2"),
                    ConnectionModel("c2", "n1", "ghost"),
                    ConnectionModel("c3", "ghost", "n2"),
                ),
            )
            coEvery { pipelineRepository.getPipelineById("src") } returns source
            val saved = slot<PipelineGraph>()
            coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

            val result = useCase("src")

            assertTrue(result.isSuccess)
            val duplicate = result.getOrNull()
            assertNotNull(duplicate)
            // Dangling connections are filtered; only the well-formed one survives.
            assertEquals(1, duplicate!!.connections.size)
            assertEquals(2, duplicate.nodes.size)
            // The persisted graph matches the returned graph.
            assertEquals(duplicate, saved.captured)
        }
}
