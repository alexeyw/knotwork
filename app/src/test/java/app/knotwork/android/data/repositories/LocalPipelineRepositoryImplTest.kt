package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.PipelineDao
import app.knotwork.android.data.local.models.ConnectionEntity
import app.knotwork.android.data.local.models.NodeEntity
import app.knotwork.android.data.local.models.PipelineEntity
import app.knotwork.android.data.local.models.PipelineWithNodesAndConnections
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalPipelineRepositoryImplTest {

    private lateinit var pipelineDao: PipelineDao
    private lateinit var repository: LocalPipelineRepositoryImpl

    @Before
    fun setup() {
        pipelineDao = mockk()
        repository = LocalPipelineRepositoryImpl(pipelineDao)
    }

    @Test
    fun `getAllPipelines maps entities to domain models correctly`() = runTest {
        // Arrange
        val pipelineEntity = PipelineEntity(id = "p1", name = "Test Pipeline")
        val nodeEntity = NodeEntity(id = "n1", pipelineId = "p1", type = "TOOL", x = 10f, y = 20f, label = "Test Node")
        val connectionEntity = ConnectionEntity(id = "c1", pipelineId = "p1", sourceNodeId = "n1", targetNodeId = "n2")

        val entities = listOf(
            PipelineWithNodesAndConnections(
                pipeline = pipelineEntity,
                nodes = listOf(nodeEntity),
                connections = listOf(connectionEntity),
            ),
        )

        every { pipelineDao.getAllPipelines() } returns flowOf(entities)

        // Act
        val result = repository.getAllPipelines().first()

        // Assert
        assertEquals(1, result.size)
        val graph = result.first()
        assertEquals("p1", graph.id)
        assertEquals("Test Pipeline", graph.name)

        assertEquals(1, graph.nodes.size)
        val node = graph.nodes.first()
        assertEquals("n1", node.id)
        assertEquals(NodeType.TOOL, node.type)
        assertEquals(10f, node.x)
        assertEquals(20f, node.y)
        assertEquals("Test Node", node.label)

        assertEquals(1, graph.connections.size)
        val connection = graph.connections.first()
        assertEquals("c1", connection.id)
        assertEquals("n1", connection.sourceNodeId)
        assertEquals("n2", connection.targetNodeId)

        // Legacy entities created without an explicit context config must come
        // back as ALL_ENABLED so pipelines saved before per-node context config existed behave the same.
        assertEquals(NodeContextConfig.ALL_ENABLED, node.contextConfig)
    }

    @Test
    fun `getAllPipelines preserves a non-default context config from entity to domain`() = runTest {
        val customConfig = NodeContextConfig(
            chatHistory = false,
            originalTask = true,
            nodeInput = true,
            longTermMemory = false,
            toolResults = false,
        )
        val pipelineEntity = PipelineEntity(id = "p1", name = "Test Pipeline")
        val nodeEntity = NodeEntity(
            id = "n1",
            pipelineId = "p1",
            type = "LITE_RT",
            x = 0f,
            y = 0f,
            label = "Test Node",
            contextConfig = customConfig,
        )

        every { pipelineDao.getAllPipelines() } returns flowOf(
            listOf(
                PipelineWithNodesAndConnections(
                    pipeline = pipelineEntity,
                    nodes = listOf(nodeEntity),
                    connections = emptyList(),
                ),
            ),
        )

        val result = repository.getAllPipelines().first()

        assertEquals(customConfig, result.first().nodes.first().contextConfig)
    }

    @Test
    fun `savePipeline calls savePipelineTransaction on Dao`() = runTest {
        // Arrange
        val pipeline = PipelineGraph(
            id = "p1",
            name = "Test Pipeline",
            nodes = listOf(NodeModel("n1", NodeType.LITE_RT, 0f, 0f, "Label")),
            connections = listOf(ConnectionModel("c1", "n1", "n2")),
        )

        coEvery { pipelineDao.savePipelineTransaction(any(), any(), any()) } returns Unit

        // Act
        repository.savePipeline(pipeline)

        // Assert
        coVerify(exactly = 1) {
            pipelineDao.savePipelineTransaction(
                match { it.id == "p1" && it.name == "Test Pipeline" },
                match { it.size == 1 && it[0].id == "n1" && it[0].type == "LITE_RT" },
                match { it.size == 1 && it[0].id == "c1" && it[0].sourceNodeId == "n1" && it[0].targetNodeId == "n2" },
            )
        }
    }

    @Test
    fun `memory retrieval query round-trips through both mapper directions`() = runTest {
        // Both directions in one test: a mapper that drops the field on either
        // side silently reverts the pipeline to prompt-keyed retrieval.
        val pipeline = PipelineGraph(
            id = "p1",
            name = "Test Pipeline",
            nodes = listOf(NodeModel("n1", NodeType.LITE_RT, 0f, 0f, "Label")),
            memoryRetrievalQuery = "evening journal entries",
        )
        val savedEntity = slot<PipelineEntity>()
        coEvery { pipelineDao.savePipelineTransaction(capture(savedEntity), any(), any()) } returns Unit

        repository.savePipeline(pipeline)

        assertEquals("evening journal entries", savedEntity.captured.memoryRetrievalQuery)

        every { pipelineDao.getAllPipelines() } returns flowOf(
            listOf(
                PipelineWithNodesAndConnections(
                    pipeline = savedEntity.captured,
                    nodes = emptyList(),
                    connections = emptyList(),
                ),
            ),
        )

        val reloaded = repository.getAllPipelines().first().single()

        assertEquals("evening journal entries", reloaded.memoryRetrievalQuery)
    }

    @Test
    fun `savePipelines flattens every graph into one atomic transaction`() = runTest {
        val root = PipelineGraph(
            id = "root",
            name = "Root",
            nodes = listOf(NodeModel("rn", NodeType.PIPELINE, 0f, 0f, "Sub", targetPipelineId = "sub")),
            connections = emptyList(),
        )
        val sub = PipelineGraph(
            id = "sub",
            name = "Sub",
            nodes = listOf(NodeModel("sn", NodeType.LITE_RT, 0f, 0f, "Label")),
            connections = listOf(ConnectionModel("sc", "sn", "sn2")),
        )

        coEvery { pipelineDao.savePipelinesTransaction(any(), any(), any()) } returns Unit

        repository.savePipelines(listOf(root, sub))

        coVerify(exactly = 1) {
            pipelineDao.savePipelinesTransaction(
                match { it.map { p -> p.id }.toSet() == setOf("root", "sub") },
                match { nodes -> nodes.size == 2 && nodes.all { it.pipelineId in setOf("root", "sub") } },
                match { conns -> conns.size == 1 && conns[0].pipelineId == "sub" },
            )
        }
    }

    @Test
    fun `savePipeline forwards context config to entity layer`() = runTest {
        val customConfig = NodeContextConfig(
            chatHistory = false,
            originalTask = true,
            nodeInput = true,
            longTermMemory = false,
            toolResults = true,
        )
        val pipeline = PipelineGraph(
            id = "p1",
            name = "Test Pipeline",
            nodes = listOf(
                NodeModel(
                    id = "n1",
                    type = NodeType.LITE_RT,
                    x = 0f,
                    y = 0f,
                    label = "Label",
                    contextConfig = customConfig,
                ),
            ),
            connections = emptyList(),
        )

        coEvery { pipelineDao.savePipelineTransaction(any(), any(), any()) } returns Unit

        repository.savePipeline(pipeline)

        coVerify(exactly = 1) {
            pipelineDao.savePipelineTransaction(
                any(),
                match { it.size == 1 && it[0].contextConfig == customConfig },
                any(),
            )
        }
    }
}
