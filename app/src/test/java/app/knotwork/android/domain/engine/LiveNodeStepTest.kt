package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.executors.NodeExecutor
import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunBudgetLedger
import app.knotwork.android.domain.models.RunCeilingLimit
import app.knotwork.android.domain.models.RunCeilings
import app.knotwork.android.domain.models.RunImageDelivery
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [LiveNodeStep].
 *
 * Each case scripts one executor run and checks what reached the run's flow, the
 * run record, the console and the ledger. The charging rules carry the most risk:
 * a node charged twice across a park and resume, or a pass-through charged on
 * every attempt, makes a run that parks often die early — the inverse of what the
 * persisted ledger is for.
 */
class LiveNodeStepTest {

    private val calls = mutableListOf<String>()
    private val executor = ScriptedExecutor()
    private val factory: NodeExecutorFactory = mockk { every { getExecutor(any()) } returns executor }
    private val metrics: MetricsRepository = mockk(relaxed = true)
    private val runs: PipelineRunRepository = mockk(relaxed = true)
    private val trace: RunTraceRepository = mockk(relaxed = true)
    private val emitted = mutableListOf<AgentOrchestratorState>()
    private val collector = FlowCollector<AgentOrchestratorState> { emitted += it }
    private val console = RunConsole(collector, trace, "s", "run-1", 0, "G", 0L)
    private val records = RunRecordWriter.Factory(
        runs,
        mockk(relaxed = true),
        mockk(relaxed = true),
        trace,
    ).open("run-1", "s")

    /** Context that reads nothing: only the carried input. */
    private val carriedOnly = NodeContextConfig(
        chatHistory = false,
        originalTask = false,
        nodeInput = true,
        longTermMemory = false,
        toolResults = false,
    )

    /** The soft warning fires at 75 % of [hardSteps]: a hard ceiling of 2 crosses it on the first step. */
    private fun ledger(hardSteps: Int = 20) = RunBudgetLedger(
        ceilings = RunCeilings(
            origin = RunOrigin.CHAT,
            steps = RunCeilingLimit.Enforced(soft = RunCeilings.softFor(hardSteps), hard = hardSteps),
            tokens = RunCeilingLimit.Enforced(soft = 1_000, hard = 2_000),
        ),
    )

    private val graph = PipelineGraph(
        id = "g",
        name = "G",
        connections = listOf(
            ConnectionModel("r1", "router", "a", "billing"),
            ConnectionModel("r2", "router", "b", "support"),
        ),
    )

    private fun step(
        tree: RunTreeContext = RunTreeContext.standalone().copy(budget = ledger()),
        resuming: Boolean = false,
    ) = LiveNodeStep(
        nodeExecutorFactory = factory,
        metricsRepository = metrics,
        collector = collector,
        console = console,
        records = records,
        inputs = NodeInputComposer.Factory(
            mockk(),
            mockk(),
            mockk(),
            PromptTemplateEngine(),
            emptySet(),
            NodeContextBuilder(),
            ChatHistoryWindowPlanner(),
            mockk(),
            mockk(),
        ).open(console, graph, "s", "the task", tree, null),
        tree = tree,
        graph = graph,
        call = LiveNodeStep.NodeCall("s", "the task", "run-1"),
        resuming = resuming,
        beforeOutput = { calls += "beforeOutput" },
    )

    private fun node(type: NodeType, id: String = type.name, config: NodeContextConfig = carriedOnly) =
        NodeModel(id = id, type = type, x = 0f, y = 0f, label = "label-$id", contextConfig = config)

    private fun consoleLines(type: ConsoleEventType) =
        (emitted.filterIsInstance<AgentOrchestratorState.ConsoleLog>().lastOrNull()?.events).orEmpty().filter {
            it.type ==
                type
        }

    @Test
    fun `given a node that answers then its states, console and result come through and it is charged`() = runTest {
        val tree = RunTreeContext.standalone().copy(budget = ledger())
        executor.outputs = flowOf(
            NodeOutput.Console(ConsoleEventType.StructuredOutputRepair, "repairing"),
            NodeOutput.State(AgentOrchestratorState.Loading),
            NodeOutput.Result(NodeExecutionResult(outputText = "answer", tokenCount = 12, tokensEstimated = false)),
        )

        val outcome = step(tree).run(node(NodeType.LITE_RT), "carried", carriedByModel = true, pipelineVisitIndex = 0)

        val ran = outcome as LiveOutcome.Ran
        assertEquals("answer", ran.result?.outputText)
        // What the step reports as the node's input is what its executor ran on.
        assertEquals(executor.lastInput, ran.input)
        assertTrue(ran.input, ran.input.endsWith("carried"))
        assertTrue(AgentOrchestratorState.Loading in emitted)
        assertEquals("▶ LITE_RT", consoleLines(ConsoleEventType.NodeExecution).first().message)
        verify { metrics.recordStructuredOutputRepair("label-LITE_RT") }
        verify { metrics.recordNodeExecution(NodeType.LITE_RT, any(), 12) }
        assertEquals(1, tree.budget.stepsSpent)
        assertEquals(12, tree.budget.tokensSpent)
    }

    @Test
    fun `given an executor's own error state then its text is redacted before it reaches the flow`() = runTest {
        executor.outputs = flowOf(NodeOutput.State(AgentOrchestratorState.Error("call to x?api_key=abc failed")))

        step().run(node(NodeType.CLOUD), "in", false, 0)

        assertEquals(
            "call to x?api_key=*** failed",
            (
                emitted.last {
                    it is AgentOrchestratorState.Error
                } as AgentOrchestratorState.Error
                ).message,
        )
    }

    @Test
    fun `given a node that parks the run then the walk ends, the trace is flushed and nothing is charged`() = runTest {
        val tree = RunTreeContext.standalone().copy(budget = ledger())
        executor.outputs = flowOf(
            NodeOutput.State(AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL)),
        )

        val outcome = step(tree).run(node(NodeType.TOOL), "in", false, 0)

        assertEquals(LiveOutcome.Parked, outcome)
        assertEquals(
            "⏸ TOOL parked awaiting user response",
            consoleLines(ConsoleEventType.NodeExecution).last().message,
        )
        coVerify { trace.flush() }
        coVerify(exactly = 0) { runs.updateStatus(any(), any()) }
        verify(exactly = 0) { metrics.recordNodeExecution(any(), any(), any()) }
        assertEquals(0, tree.budget.stepsSpent)
    }

    @Test
    fun `given a gate that asks and then parks then the record keeps waiting`() = runTest {
        executor.outputs = flowOf(
            NodeOutput.State(AgentOrchestratorState.WaitingForApproval("t", "{}", ToolRisk.SENSITIVE, "req")),
            NodeOutput.State(AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL)),
        )

        assertEquals(LiveOutcome.Parked, step().run(node(NodeType.TOOL), "in", false, 0))

        // The park itself is not mirrored: mirroring it would end the wait.
        coVerify(exactly = 1) { runs.updateStatus("run-1", PipelineRunStatus.WAITING_APPROVAL) }
        coVerify(exactly = 0) { runs.updateStatus("run-1", PipelineRunStatus.RUNNING) }
    }

    @Test
    fun `given an executor that throws then the walk ends on a redacted error`() = runTest {
        executor.outputs = flow { throw IllegalStateException("401 for Bearer sk-secret") }

        val outcome = step().run(node(NodeType.CLOUD), "in", false, 0)

        assertEquals(LiveOutcome.Failed, outcome)
        assertEquals(AgentOrchestratorState.Error("401 for Bearer ***"), emitted.last())
        assertEquals("CLOUD: 401 for Bearer ***", consoleLines(ConsoleEventType.Error).single().message)
    }

    @Test
    fun `given a node that waits for approval then the record waits and returns to running when it finishes`() =
        runTest {
            executor.outputs = flowOf(
                NodeOutput.State(AgentOrchestratorState.WaitingForApproval("t", "{}", ToolRisk.SENSITIVE, "req")),
                NodeOutput.Result(NodeExecutionResult(outputText = "done")),
            )

            step().run(node(NodeType.TOOL), "in", false, 0)

            coVerifyOrder {
                runs.updateStatus("run-1", PipelineRunStatus.WAITING_APPROVAL)
                runs.updateStatus("run-1", PipelineRunStatus.RUNNING)
            }
        }

    @Test
    fun `given a resumed attempt then INPUT and OUTPUT are not charged a step again, a fresh attempt charges them`() =
        runTest {
            executor.outputs = flowOf(NodeOutput.Result(NodeExecutionResult(outputText = "x")))
            val resumed = RunTreeContext.standalone().copy(budget = ledger())
            val fresh = RunTreeContext.standalone().copy(budget = ledger())

            step(resumed, resuming = true).run(node(NodeType.INPUT), "in", false, 0)
            step(resumed, resuming = true).run(node(NodeType.LITE_RT), "in", false, 0)
            step(fresh, resuming = false).run(node(NodeType.INPUT), "in", false, 0)

            assertEquals(1, resumed.budget.stepsSpent)
            assertEquals(1, fresh.budget.stepsSpent)
        }

    @Test
    fun `given a sub-pipeline that already stopped the tree then the step is not charged twice, its tokens are`() =
        runTest {
            val tree = RunTreeContext.standalone().copy(budget = ledger())
            executor.outputs = flowOf(
                NodeOutput.Result(
                    NodeExecutionResult(
                        error = "stop",
                        tokenCount = 5,
                        terminationReason = RunTerminationReason.NoProgress,
                    ),
                ),
            )

            step(tree).run(node(NodeType.PIPELINE), "in", false, 0)

            assertEquals(0, tree.budget.stepsSpent)
            assertEquals(5, tree.budget.tokensSpent)
        }

    @Test
    fun `given a soft crossing then it is announced and advised, except on OUTPUT`() = runTest {
        executor.outputs = flowOf(NodeOutput.Result(NodeExecutionResult(outputText = "x")))
        val tree = RunTreeContext.standalone().copy(budget = ledger(hardSteps = 2))

        step(tree).run(node(NodeType.LITE_RT), "in", false, 0)

        assertTrue(
            emitted.any {
                it is AgentOrchestratorState.RunNotice && it.cause is RunNoticeCause.ApproachingCeiling
            },
        )
        assertEquals(1, consoleLines(ConsoleEventType.RunCeiling).size)
        assertEquals(LiveNodeStep.SOFT_CEILING_CONTEXT_NOTE, tree.contextNotes.drain())

        val atOutput = RunTreeContext.standalone().copy(budget = ledger(hardSteps = 2))
        emitted.clear()
        step(atOutput).run(node(NodeType.OUTPUT), "in", false, 0)
        assertFalse(emitted.any { it is AgentOrchestratorState.RunNotice })
        assertNull(atOutput.contextNotes.drain())
    }

    @Test
    fun `given an OUTPUT node then the pre-output hook runs before its executor, and for no other node`() = runTest {
        executor.outputs = flowOf(NodeOutput.Result(NodeExecutionResult(outputText = "x")))

        step().run(node(NodeType.LITE_RT), "in", false, 0)
        step().run(node(NodeType.OUTPUT), "in", false, 0)

        assertEquals(listOf("execute LITE_RT", "beforeOutput", "execute OUTPUT"), calls)
    }

    @Test
    fun `given a node then its scope carries the tree, the visit, the router's choices, the image and authorship`() =
        runTest {
            executor.outputs = flowOf(NodeOutput.Result(NodeExecutionResult(outputText = "x")))
            val delivery = RunImageDelivery(EngineImageInput("/img.jpg", width = 1, height = 1, sizeBytes = 1L))
            val tree = RunTreeContext.standalone().copy(budget = ledger(), imageDelivery = delivery)

            step(
                tree,
            ).run(node(NodeType.INTENT_ROUTER, id = "router"), "in", carriedByModel = true, pipelineVisitIndex = 3)
            val routerScope = executor.lastScope!!
            step(tree).run(node(NodeType.LITE_RT, config = carriedOnly.copy(originalTask = true)), "in", false, 0)
            val visionScope = executor.lastScope!!

            assertEquals(tree, routerScope.run)
            assertEquals(3, routerScope.pipelineVisitIndex)
            assertEquals(listOf("billing", "support"), routerScope.routingChoices)
            assertTrue(routerScope.inputWrittenByModel)
            assertNull(routerScope.imagePath)
            assertEquals("/img.jpg", visionScope.imagePath)
        }

    /** An executor whose output the test scripts, recording the scope it was handed. */
    private inner class ScriptedExecutor : NodeExecutor {
        var outputs: Flow<NodeOutput> = flowOf()
        var lastScope: ExecutionScope? = null
        var lastInput: String? = null

        override fun execute(
            node: NodeModel,
            inputText: String,
            sessionId: String,
            originalPrompt: String,
            runId: String?,
            scope: ExecutionScope,
        ): Flow<NodeOutput> {
            calls += "execute ${node.type.name}"
            lastScope = scope
            lastInput = inputText
            return outputs
        }
    }
}
