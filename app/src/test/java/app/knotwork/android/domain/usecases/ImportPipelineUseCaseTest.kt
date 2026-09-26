package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineImportOutcome
import app.knotwork.android.domain.pipelineio.PipelineJsonSerializer
import app.knotwork.android.domain.repositories.PipelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ImportPipelineUseCase].
 *
 * Boundaries:
 *
 * - The parsing pipeline lives inside [PipelineJsonSerializer] and is
 *   tested separately. Here we focus on the use case's
 *   parse-then-conditionally-persist orchestration: which outcomes
 *   trigger a save, which do not, and how the save's own [Result] is
 *   propagated.
 */
class ImportPipelineUseCaseTest {

    private lateinit var savePipelineUseCase: SavePipelineUseCase
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var findPipelineBindings: FindPipelineBindingsUseCase
    private lateinit var useCase: ImportPipelineUseCase

    @Before
    fun setup() {
        savePipelineUseCase = mockk()
        pipelineRepository = mockk()
        // Default: no collision — the imported id is free.
        coEvery { pipelineRepository.getPipelineById(any()) } returns null
        findPipelineBindings = mockk()
        coEvery { findPipelineBindings.of(any()) } returns PipelineBindings()
        useCase = ImportPipelineUseCase(savePipelineUseCase, pipelineRepository, findPipelineBindings)
    }

    /** Minimal valid v1 document covering the happy path. */
    private val validJson = """
        {
          "schemaVersion": 1,
          "id": "p", "name": "demo", "updatedAt": 0,
          "nodes":[
            {"id":"n1","type":"INPUT","position":{"x":0,"y":0},
             "label":"In","config":{},"contextConfig":{}},
            {"id":"n2","type":"OUTPUT","position":{"x":200,"y":0},
             "label":"Out","config":{"systemPrompt":"reply"},"contextConfig":{}}
          ],
          "connections":[
            {"id":"c1","fromNodeId":"n1","toNodeId":"n2","label":null}
          ]
        }
    """.trimIndent()

    /** Same shape as [validJson] but with a future schemaVersion. */
    private val mismatchJson = validJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")

    @Test
    fun `given valid JSON when invoke then save is called and outcome is Success`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)

        val invocation = useCase(validJson)

        assertTrue(invocation.outcome is PipelineImportOutcome.Success)
        assertTrue(invocation.saveResult?.isSuccess == true)
        coVerify(exactly = 1) {
            savePipelineUseCase(match<PipelineGraph> { it.id == "p" && it.nodes.size == 2 })
        }
    }

    @Test
    fun `given an id no library row holds but something is bound to when invoke then nothing is saved and it asks`() =
        runTest {
            // The id of a deleted pipeline: chats, triggers or callers still name it.
            // Saving under it would re-bind all of them to the file without a word.
            coEvery { findPipelineBindings.of("p") } returns PipelineBindings(triggerCount = 1, chatCount = 2)

            val invocation = useCase(validJson)

            assertNull(invocation.saveResult)
            val pending = requireNotNull(invocation.pendingCollision)
            assertNull("no library pipeline holds the id", pending.existingName)
            assertEquals(PipelineBindings(triggerCount = 1, chatCount = 2), pending.bindings)
            coVerify(exactly = 0) { savePipelineUseCase(any()) }
        }

    @Test
    fun `given a schema mismatch confirmed for an id something is still bound to when persisted then it asks`() =
        runTest {
            coEvery { findPipelineBindings.of("p") } returns PipelineBindings(isDefault = true)
            val mismatch = useCase(mismatchJson).outcome as PipelineImportOutcome.SchemaMismatch

            val confirmed = useCase.persistConfirmed(mismatch)

            assertTrue(confirmed is ConfirmedImport.Collision)
            assertNull((confirmed as ConfirmedImport.Collision).collision.existingName)
            coVerify(exactly = 0) { savePipelineUseCase(any()) }
        }

    @Test
    fun `given valid JSON when invoke then node and connection ids are freshened before save`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)

        useCase(validJson)

        // Pipeline id is preserved (sync semantics) but every node/connection id
        // is regenerated so it cannot collide with another pipeline's global rows.
        val graph = saved.captured
        assertEquals("pipeline id must be preserved", "p", graph.id)
        assertTrue("node ids must be freshened", graph.nodes.none { it.id == "n1" || it.id == "n2" })
        assertTrue("connection ids must be freshened", graph.connections.none { it.id == "c1" })
        // Endpoints must be remapped to the freshened node ids, not left dangling.
        val nodeIds = graph.nodes.map { it.id }.toSet()
        assertTrue(
            "connection endpoints must resolve to freshened nodes",
            graph.connections.all { it.sourceNodeId in nodeIds && it.targetNodeId in nodeIds },
        )
    }

    @Test
    fun `given two imports reusing the same node ids when invoke then saved graphs share no node id`() = runTest {
        val savedGraphs = mutableListOf<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(savedGraphs)) } returns Result.success(Unit)

        // Same node ids (n1/n2), different pipeline ids — the real-world case where
        // importing a second pipeline used to steal the first's rows via REPLACE.
        useCase(validJson)
        useCase(validJson.replace("\"id\": \"p\"", "\"id\": \"q\""))

        assertEquals(2, savedGraphs.size)
        val firstNodeIds = savedGraphs[0].nodes.map { it.id }.toSet()
        val secondNodeIds = savedGraphs[1].nodes.map { it.id }.toSet()
        assertTrue(
            "the two imports must not share any node id (no cross-pipeline collision)",
            firstNodeIds.intersect(secondNodeIds).isEmpty(),
        )
    }

    @Test
    fun `given collision when resolve REPLACE then node ids are freshened`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)
        val graph = useCase(validJson).let {
            (it.outcome as PipelineImportOutcome.Success).graph
        }

        useCase.persistWithResolution(graph, ImportCollisionResolution.REPLACE)

        assertEquals("REPLACE keeps the pipeline id", "p", saved.captured.id)
        assertTrue(
            "REPLACE must still freshen node ids to avoid stealing another pipeline's rows",
            saved.captured.nodes.none { it.id == "n1" || it.id == "n2" },
        )
    }

    /**
     * A pipeline whose sample prompts name tools: [wiredTool] is what its TOOL
     * node calls, [hints] is what the file claims each prompt uses.
     */
    private fun jsonWithSamplePrompts(wiredTool: String, vararg hints: String): String {
        val prompts = hints.mapIndexed { i, hint -> """{"title":"Prompt $i","toolsHint":"$hint"}""" }
        return """
            {
              "schemaVersion": 1, "id": "p", "name": "demo",
              "nodes":[
                {"id":"n1","type":"INPUT"},
                {"id":"n2","type":"TOOL","config":{"toolName":"$wiredTool"}},
                {"id":"n3","type":"OUTPUT"}
              ],
              "connections":[
                {"id":"c1","fromNodeId":"n1","toNodeId":"n2"},
                {"id":"c2","fromNodeId":"n2","toNodeId":"n3"}
              ],
              "samplePrompts":[${prompts.joinToString(",")}]
            }
        """.trimIndent()
    }

    @Test
    fun `given a tools hint naming a tool the graph does not call when invoke then the hint is dropped`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)

        useCase(jsonWithSamplePrompts(wiredTool = "delete_file", "read_file"))

        assertEquals("Prompt 0", saved.captured.samplePrompts.single().title)
        assertNull(saved.captured.samplePrompts.single().toolsHint)
    }

    @Test
    fun `given a tools hint mixing wired and unwired tools when invoke then only the wired one is kept`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)

        useCase(jsonWithSamplePrompts(wiredTool = "search_tool", "search_tool, read_file", "search_tool"))

        assertEquals(listOf("search_tool", "search_tool"), saved.captured.samplePrompts.map { it.toolsHint })
    }

    @Test
    fun `given an imported hint when the import collides then the pending graph already carries the checked hint`() =
        runTest {
            coEvery { pipelineRepository.getPipelineById("p") } returns PipelineGraph(id = "p", name = "existing")

            val invocation = useCase(jsonWithSamplePrompts(wiredTool = "delete_file", "read_file"))

            assertNull(invocation.pendingCollision!!.incoming.samplePrompts.single().toolsHint)
        }

    @Test
    fun `given an import colliding with a bound pipeline then the collision names the existing row and its bindings`() =
        runTest {
            // The file calls itself "demo"; the library's pipeline under the same
            // id is something else entirely, and it is what Replace overwrites.
            val bindings = PipelineBindings(isDefault = true, callerNames = listOf("Full agent"))
            coEvery { pipelineRepository.getPipelineById("p") } returns
                PipelineGraph(id = "p", name = "Act on the task")
            coEvery { findPipelineBindings.of("p") } returns bindings

            val collision = useCase(validJson).pendingCollision!!

            assertEquals("Act on the task", collision.existingName)
            assertEquals(bindings, collision.bindings)
            assertEquals("demo", collision.incoming.name)
        }

    @Test
    fun `given a clean import when saved then the result is the graph as written, freshened ids included`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)

        val invocation = useCase(validJson)

        assertEquals(saved.captured, invocation.saveResult?.getOrNull())
    }

    @Test
    fun `given a collision resolved by Replace then the result is the graph as written`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)
        val graph = (PipelineJsonSerializer.parse(validJson) as PipelineImportOutcome.Success).graph

        val result = useCase.persistWithResolution(graph, ImportCollisionResolution.REPLACE)

        assertEquals(saved.captured, result.getOrNull())
        assertEquals("p", result.getOrNull()?.id)
    }

    @Test
    fun `given a confirmed mismatch colliding then the collision names the existing row`() = runTest {
        coEvery { pipelineRepository.getPipelineById("p") } returns PipelineGraph(id = "p", name = "Existing")
        val mismatch = useCase(mismatchJson).outcome as PipelineImportOutcome.SchemaMismatch

        val confirmed = useCase.persistConfirmed(mismatch) as ConfirmedImport.Collision

        assertEquals("Existing", confirmed.collision.existingName)
    }

    @Test
    fun `given schema mismatch when invoke then save is not called`() = runTest {
        val invocation = useCase(mismatchJson)

        assertTrue(invocation.outcome is PipelineImportOutcome.SchemaMismatch)
        assertNull(
            "saveResult should be null when no save was attempted",
            invocation.saveResult,
        )
        coVerify(exactly = 0) { savePipelineUseCase(any()) }
    }

    @Test
    fun `given malformed JSON when invoke then Failure and save is not called`() = runTest {
        val invocation = useCase("{ not json")

        assertTrue(invocation.outcome is PipelineImportOutcome.Failure)
        assertNull(invocation.saveResult)
        coVerify(exactly = 0) { savePipelineUseCase(any()) }
    }

    @Test
    fun `given save failure when invoke then save error is propagated`() = runTest {
        val boom = RuntimeException("disk full")
        coEvery { savePipelineUseCase(any()) } returns Result.failure(boom)

        val invocation = useCase(validJson)

        assertTrue(invocation.outcome is PipelineImportOutcome.Success)
        assertFalse(invocation.saveResult?.isSuccess == true)
        assertEquals(boom, invocation.saveResult?.exceptionOrNull())
    }

    @Test
    fun `persistConfirmed saves the SchemaMismatch graph when its id is free`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        val mismatch = useCase(mismatchJson).outcome as PipelineImportOutcome.SchemaMismatch

        val confirmed = useCase.persistConfirmed(mismatch)

        assertTrue(confirmed is ConfirmedImport.Saved)
        assertTrue((confirmed as ConfirmedImport.Saved).result.isSuccess)
        coVerify(exactly = 1) {
            savePipelineUseCase(match<PipelineGraph> { it.id == mismatch.graph.id })
        }
    }

    @Test
    fun `persistConfirmed returns Collision without saving when the id already exists`() = runTest {
        coEvery { pipelineRepository.getPipelineById("p") } returns
            PipelineGraph(id = "p", name = "existing")
        val mismatch = useCase(mismatchJson).outcome as PipelineImportOutcome.SchemaMismatch

        val confirmed = useCase.persistConfirmed(mismatch)

        assertTrue(confirmed is ConfirmedImport.Collision)
        coVerify(exactly = 0) { savePipelineUseCase(any()) }
    }

    @Test
    fun `given id collision when invoke then no save and pendingCollision is set`() = runTest {
        coEvery { pipelineRepository.getPipelineById("p") } returns
            PipelineGraph(id = "p", name = "existing")

        val invocation = useCase(validJson)

        assertTrue(invocation.outcome is PipelineImportOutcome.Success)
        assertNull("no save is attempted on collision", invocation.saveResult)
        assertNotNull("pendingCollision carries the parsed graph", invocation.pendingCollision)
        assertEquals("p", invocation.pendingCollision?.incoming?.id)
        coVerify(exactly = 0) { savePipelineUseCase(any()) }
    }

    @Test
    fun `given collision when resolve REPLACE then save keeps the original id`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        val graph = PipelineGraph(id = "p", name = "demo")

        useCase.persistWithResolution(graph, ImportCollisionResolution.REPLACE)

        coVerify(exactly = 1) { savePipelineUseCase(match<PipelineGraph> { it.id == "p" }) }
    }

    @Test
    fun `given collision when resolve IMPORT_AS_COPY then save uses a fresh id`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { savePipelineUseCase(capture(saved)) } returns Result.success(Unit)
        val graph = PipelineGraph(id = "p", name = "demo")

        useCase.persistWithResolution(graph, ImportCollisionResolution.IMPORT_AS_COPY)

        assertFalse("copy must not reuse the colliding id", saved.captured.id == "p")
    }
}
