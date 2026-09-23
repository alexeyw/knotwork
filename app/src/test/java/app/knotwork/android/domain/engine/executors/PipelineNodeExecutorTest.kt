package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * Unit tests for [PipelineNodeExecutor] — the recursive sub-pipeline executor.
 *
 * The recursive [GraphExecutionEngine] is mocked so each test controls exactly
 * what the sub-run emits, isolating the executor's own logic (target
 * resolution, depth ceiling, output/error mapping, depth threading, the child
 * run lifecycle, resume across the boundary, and HITL park propagation).
 */
class PipelineNodeExecutorTest {

    private val pipelineRepository: PipelineRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val pipelineRunRepository: PipelineRunRepository = mockk(relaxed = true)
    private val runTraceRepository: RunTraceRepository = mockk(relaxed = true)
    private val engine: GraphExecutionEngine = mockk()

    private val executor = PipelineNodeExecutor(
        pipelineRepository = pipelineRepository,
        settingsRepository = settingsRepository,
        pipelineRunRepository = pipelineRunRepository,
        runTraceRepository = runTraceRepository,
        engineProvider = Provider { engine },
    )

    private val subGraph = PipelineGraph(
        id = "sub",
        name = "Sub Pipeline",
        nodes = listOf(
            NodeModel("i", NodeType.INPUT, 0f, 0f),
            NodeModel("o", NodeType.OUTPUT, 1f, 1f),
        ),
        connections = listOf(ConnectionModel("c", "i", "o")),
    )

    private fun pipelineNode(targetPipelineId: String? = "sub") =
        NodeModel(id = "p", type = NodeType.PIPELINE, x = 0f, y = 0f, targetPipelineId = targetPipelineId)

    private fun stubEngine(result: Flow<AgentOrchestratorState>) {
        every { engine.invoke(any(), any(), any(), any(), any(), any(), any()) } returns result
    }

    private suspend fun runExecutor(
        node: NodeModel,
        input: String = "hello",
        runId: String? = null,
        scope: ExecutionScope = ExecutionScope(),
    ) = executor.execute(node, input, "session", "original", runId = runId, scope = scope).toList()

    @Before
    fun setUp() {
        every { settingsRepository.pipelineMaxNestingDepth } returns flowOf(3)
    }

    private fun List<NodeOutput>.singleResult() = filterIsInstance<NodeOutput.Result>().single().result

    private fun List<NodeOutput>.states() = filterIsInstance<NodeOutput.State>().map { it.state }

    @Test
    fun `given resolvable target when execute then forwards sub-pipeline output`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        stubEngine(flowOf(AgentOrchestratorState.Completed("sub answer")))

        val result = runExecutor(pipelineNode()).singleResult()

        assertEquals("sub answer", result.outputText)
        assertNull(result.error)
    }

    @Test
    fun `given top-level run when execute then sub-pipeline runs one level deeper with node input as prompt`() =
        runTest {
            coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
            stubEngine(flowOf(AgentOrchestratorState.Completed("ok")))

            runExecutor(pipelineNode(), input = "carried input")

            // userPrompt = the node's input; runId = null (non-persisted);
            // depth = parent + 1; budget threaded through (null here).
            verify {
                engine.invoke("session", "carried input", subGraph, null, null, 1, null)
            }
        }

    @Test
    fun `given blank target when execute then fails without touching repository`() = runTest {
        val result = runExecutor(pipelineNode(targetPipelineId = null)).singleResult()

        assertNull(result.outputText)
        assertTrue(result.error!!.contains("no target pipeline"))
        verify(exactly = 0) { engine.invoke(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given missing target when execute then fails with not-found error`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns null

        val result = runExecutor(pipelineNode()).singleResult()

        assertTrue(result.error!!.contains("not found"))
        verify(exactly = 0) { engine.invoke(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `given depth one below the limit when execute then the sub-pipeline still runs`() = runTest {
        // limit = 3, current depth = 2 -> child depth = 3 -> allowed.
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        stubEngine(flowOf(AgentOrchestratorState.Completed("deep ok")))

        val result = runExecutor(pipelineNode(), scope = ExecutionScope(depth = 2)).singleResult()

        assertEquals("deep ok", result.outputText)
        verify { engine.invoke(any(), any(), any(), any(), any(), 3, any()) }
    }

    @Test
    fun `given depth at the limit when execute then refuses and does not recurse`() = runTest {
        // limit = 3, current depth = 3 -> child depth = 4 -> refused.
        val result = runExecutor(pipelineNode(), scope = ExecutionScope(depth = 3)).singleResult()

        assertTrue(result.error!!.contains("nesting depth"))
        verify(exactly = 0) { engine.invoke(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pipelineRepository.getPipelineById(any()) }
    }

    @Test
    fun `given sub-pipeline error when execute then propagates the error`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        stubEngine(flowOf(AgentOrchestratorState.Error("sub boom")))

        val result = runExecutor(pipelineNode()).singleResult()

        assertEquals("sub boom", result.error)
        assertNull(result.outputText)
    }

    @Test
    fun `given sub-pipeline produces no terminal state when execute then reports no output`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        stubEngine(flowOf(AgentOrchestratorState.Loading))

        val result = runExecutor(pipelineNode()).singleResult()

        assertTrue(result.error!!.contains("no output"))
    }

    // --- Persisted child run (runId != null) ---------------------------------

    @Test
    fun `given persisted run and no existing child when execute then creates a running child linked to the parent`() =
        runTest {
            coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
            coEvery { pipelineRunRepository.getRun(any()) } returns null
            stubEngine(flowOf(AgentOrchestratorState.Completed("done")))

            val result = runExecutor(pipelineNode(), runId = "root").singleResult()

            assertEquals("done", result.outputText)
            // Deterministic child id, linked to the parent, then RUNNING.
            coVerify {
                pipelineRunRepository.createRun(
                    match { it.id == "root::p::0" && it.parentRunId == "root" && it.pipelineId == "sub" },
                )
                pipelineRunRepository.markRunning("root::p::0", "sub", subGraph.contentHash())
            }
            // Engine driven with the child run id, fresh (no resume), depth 1.
            verify { engine.invoke("session", "hello", subGraph, "root::p::0", null, 1, null) }
            // Child settled COMPLETED by the executor (the engine never does).
            coVerify { pipelineRunRepository.finishRun("root::p::0", PipelineRunStatus.COMPLETED, null) }
        }

    @Test
    fun `given persisted child interrupted when execute then resumes it from its trace instead of restarting`() =
        runTest {
            coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
            coEvery { pipelineRunRepository.getRun("root::p::0") } returns childRun(
                status = PipelineRunStatus.INTERRUPTED,
                hash = subGraph.contentHash(),
            )
            stubEngine(flowOf(AgentOrchestratorState.Completed("resumed")))

            val result = runExecutor(pipelineNode(), runId = "root").singleResult()

            assertEquals("resumed", result.outputText)
            // Resumed: flipped back through the queued→running transition, never re-created.
            coVerify { pipelineRunRepository.markResumed("root::p::0", PipelineRunStatus.INTERRUPTED) }
            coVerify(exactly = 0) { pipelineRunRepository.createRun(any()) }
            // Engine driven with a (non-null) resume payload.
            verify { engine.invoke("session", "hello", subGraph, "root::p::0", match { it != null }, 1, null) }
        }

    @Test
    fun `given persisted child with a changed graph hash when execute then fails instead of resuming`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        coEvery { pipelineRunRepository.getRun("root::p::0") } returns childRun(
            status = PipelineRunStatus.INTERRUPTED,
            hash = "STALE_HASH",
        )

        val result = runExecutor(pipelineNode(), runId = "root").singleResult()

        assertTrue(result.error!!.contains("edited since it was interrupted"))
        verify(exactly = 0) { engine.invoke(any(), any(), any(), any(), any(), any(), any()) }
        // The child settles with the same typed cause its top-level counterpart
        // uses, so a sub-pipeline invalidated by an edit is not reported as an
        // ordinary failure just because it happened one level down.
        coVerify {
            pipelineRunRepository.finishRun(
                "root::p::0",
                PipelineRunStatus.FAILED,
                any(),
                RunTerminationReason.GraphChanged,
            )
        }
        assertEquals(RunTerminationReason.GraphChanged, result.terminationReason)
    }

    @Test
    fun `given the child parks when execute then propagates suspension and does not settle the child`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        coEvery { pipelineRunRepository.getRun(any()) } returns null
        stubEngine(flowOf(AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL)))

        val outputs = runExecutor(pipelineNode(), runId = "root")

        // The suspension is forwarded so the parent stack parks…
        assertTrue(outputs.states().any { it is AgentOrchestratorState.SuspendedInBackground })
        // …and no terminal Result is emitted, nor is the child settled.
        assertTrue(outputs.filterIsInstance<NodeOutput.Result>().isEmpty())
        coVerify(exactly = 0) { pipelineRunRepository.finishRun(eq("root::p::0"), any(), any()) }
    }

    @Test
    fun `given a nested approval gate when execute then forwards the card but drops streaming states`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        coEvery { pipelineRunRepository.getRun(any()) } returns null
        stubEngine(
            flowOf(
                AgentOrchestratorState.Thinking("partial"),
                AgentOrchestratorState.WaitingForApproval(
                    "delete_file",
                    "{}",
                    risk = ToolRisk.DESTRUCTIVE,
                    requestId = "req-1",
                ),
                AgentOrchestratorState.Completed("after approval"),
            ),
        )

        val states = runExecutor(pipelineNode(), runId = "root").states()

        assertTrue(states.any { it is AgentOrchestratorState.WaitingForApproval })
        assertTrue(states.none { it is AgentOrchestratorState.Thinking })
    }

    @Test
    fun `given a non-zero visit index when execute then the child run id encodes the visit`() = runTest {
        coEvery { pipelineRepository.getPipelineById("sub") } returns subGraph
        coEvery { pipelineRunRepository.getRun(any()) } returns null
        stubEngine(flowOf(AgentOrchestratorState.Completed("ok")))

        runExecutor(pipelineNode(), runId = "root", scope = ExecutionScope(pipelineVisitIndex = 2))

        verify { engine.invoke("session", "hello", subGraph, "root::p::2", null, 1, null) }
    }

    private fun childRun(status: PipelineRunStatus, hash: String) = PipelineRun(
        id = "root::p::0",
        sessionId = "session",
        pipelineId = "sub",
        origin = RunOrigin.CHAT,
        status = status,
        currentNodeId = null,
        startedAt = 0L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = hash,
        userPrompt = "hello",
        parentRunId = "root",
    )
}
