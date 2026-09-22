package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.CloudStructuredClient
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.ApprovalNotifier
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.usecases.RecordTriggerHitlEventUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ToolNodeExecutor].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolNodeExecutorTest {

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var toolRepository: ToolRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var approvalNotifier: ApprovalNotifier
    private lateinit var chatRepository: ChatRepository
    private lateinit var pendingInteractionRepository: PendingInteractionRepository
    private lateinit var recordTriggerHitlEvent: RecordTriggerHitlEventUseCase
    private lateinit var toolInvocationGate: ToolInvocationGate
    private lateinit var executor: ToolNodeExecutor

    @Before
    fun setup() {
        llmEngine = mockk(relaxed = true)
        loadModelUseCase = mockk()
        toolRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        approvalNotifier = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        pendingInteractionRepository = mockk(relaxed = true)
        coEvery { pendingInteractionRepository.getForRun(any()) } returns null
        coEvery { pendingInteractionRepository.save(any()) } returns true
        recordTriggerHitlEvent = mockk(relaxed = true)

        toolInvocationGate = ToolInvocationGate(
            toolRepository = toolRepository,
            settingsRepository = settingsRepository,
            approvalNotifier = approvalNotifier,
            chatRepository = chatRepository,
            pendingInteractionRepository = pendingInteractionRepository,
            recordTriggerHitlEvent = recordTriggerHitlEvent,
        )
        executor = ToolNodeExecutor(
            llmEngine = llmEngine,
            loadModelUseCase = loadModelUseCase,
            toolRepository = toolRepository,
            toolInvocationGate = toolInvocationGate,
            structuredOutputGate = StructuredOutputGate(),
            settingsRepository = settingsRepository,
            cloudStructuredFactory = CloudStructuredInferenceClientFactory { _, _ -> null },
        )

        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(60_000L)
        // Default risk for tests that don't care about the HITL gate. Individual
        // tests override `getRisk(...)` to drive the gate explicitly.
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.READ_ONLY
    }

    @Test
    fun `given a cloud provider error carrying an api key when arguments are generated then the error is scrubbed`() =
        runTest {
            val leaking = ToolNodeExecutor(
                llmEngine = llmEngine,
                loadModelUseCase = loadModelUseCase,
                toolRepository = toolRepository,
                toolInvocationGate = toolInvocationGate,
                structuredOutputGate = StructuredOutputGate(),
                settingsRepository = settingsRepository,
                cloudStructuredFactory = CloudStructuredInferenceClientFactory { _, _ ->
                    CloudStructuredClient(
                        inference = { _, _ -> throw RuntimeException(LEAKING_PROVIDER_ERROR) },
                        supportsNativeJson = true,
                    )
                },
            )
            val toolName = "MyTool"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName, cloudProvider = "google")
            coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))

            val outputs = leaking.execute(node, "Do something", "session-1", "").toList()

            val stateError = outputs.filterStates<AgentOrchestratorState.Error>().single().message
            val resultError = outputs.lastResult().error.orEmpty()
            for (text in listOf(stateError, resultError)) {
                assertFalse("key leaked: $text", text.contains(LEAKED_KEY))
                assertTrue("scrub marker missing: $text", text.contains("key=***"))
            }
        }

    @Test
    fun `execute uses LLM to generate arguments for specific tool`() = runTest {
        val toolName = "MyTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "MyTool", "arguments": "arg_value"}""")
        coEvery { toolRepository.executeTool(toolName, "arg_value", any()) } returns "Tool Success"

        val states = executor.execute(node, "Do something", "session-1", "").toList().unwrap()

        // Checking last state
        val lastState = states.last() as NodeExecutionResult
        assertEquals("Tool Success", lastState.outputText)
    }

    @Test
    fun `execute passes the session id to the tool through the execution context`() = runTest {
        // schedule_task binds the scheduled run back to the conversation via this
        // context — the id must come from the engine, never from the LLM arguments.
        val toolName = "MyTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "MyTool", "arguments": "arg_value"}""")
        coEvery { toolRepository.executeTool(any(), any(), any()) } returns "ok"

        executor.execute(node, "Do something", "session-77", "").toList()

        coVerify(exactly = 1) {
            toolRepository.executeTool(toolName, "arg_value", ToolExecutionContext(sessionId = "session-77"))
        }
    }

    @Test
    fun `given approval times out when waiting for user response then emits timeout error`() = runTest {
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

        val toolName = "MyTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns flowOf("""{"tool": "MyTool", "arguments": "args"}""")

        val results = mutableListOf<Any>()
        val job = launch {
            executor.execute(node, "Do something", "session-1", "").collect { output ->
                when (output) {
                    is NodeOutput.State -> results.add(output.state)
                    is NodeOutput.Result -> results.add(output.result)
                    is NodeOutput.Console -> Unit
                }
            }
        }

        advanceTimeBy(200L)
        advanceUntilIdle()

        val lastResult = results.filterIsInstance<NodeExecutionResult>().lastOrNull()
        assertNotNull("Expected NodeExecutionResult with error", lastResult)
        assertNotNull("Expected error field to be set", lastResult?.error)
        assertTrue(lastResult!!.error!!.contains("timed out", ignoreCase = true))
        job.cancel()
    }

    @Test
    fun `given a node that always confirms then a READ_ONLY tool still asks`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.READ_ONLY

        val toolName = "ReadTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName, alwaysConfirm = true)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "ReadTool", "arguments": "args"}""")

        val states = executor.execute(node, "Read", "session-1", "").toList().unwrap()

        // The node's switch ADDS a prompt the policy would not have raised. It
        // is the only direction it can move the gate in — see the ORing in
        // `ToolInvocationGate`.
        assertTrue(
            "A node set to always confirm must ask even for a READ_ONLY tool",
            states.any { it is AgentOrchestratorState.WaitingForApproval },
        )
    }

    @Test
    fun `given a node that always confirms then it cannot waive the destructive block`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
        every { settingsRepository.blockDestructiveTools } returns flowOf(true)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.DESTRUCTIVE

        val toolName = "DeleteTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName, alwaysConfirm = true)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "DeleteTool", "arguments": "args"}""")

        val states = executor.execute(node, "Delete", "session-1", "").toList().unwrap()

        // The hard-deny gate runs first and the node has no say in it. Asserted
        // because the opposite — a node able to talk its way past a user's
        // block — is the failure this control had to be designed around.
        val last = states.last() as NodeExecutionResult
        assertTrue("Expected the block to stand, got: $last", last.error?.contains("blocked by Settings") == true)
    }

    @Test
    fun `given READ_ONLY tool and global override off when execute then no approval emitted`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.READ_ONLY

        val toolName = "ReadTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "ReadTool", "arguments": "args"}""")
        coEvery { toolRepository.executeTool(toolName, "args", any()) } returns "ok"

        val states = executor.execute(node, "Read", "session-1", "").toList().unwrap()

        assertFalse(
            "READ_ONLY tool with global override OFF must not emit WaitingForApproval",
            states.any { it is AgentOrchestratorState.WaitingForApproval },
        )
        verify(exactly = 0) { approvalNotifier.sendApprovalRequest(any(), any(), any(), any()) }
        val last = states.last() as NodeExecutionResult
        assertEquals("ok", last.outputText)
    }

    @Test
    fun `given READ_ONLY tool and global override on when execute then approval emitted with READ_ONLY risk`() =
        runTest {
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.AllCalls)
            every { settingsRepository.blockDestructiveTools } returns flowOf(false)
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
            coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.READ_ONLY

            val toolName = "ReadTool"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
            coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "ReadTool", "arguments": "args"}""")

            val results = mutableListOf<Any>()
            val job = launch {
                executor.execute(node, "Read", "session-1", "").collect { output ->
                    when (output) {
                        is NodeOutput.State -> results.add(output.state)
                        is NodeOutput.Result -> results.add(output.result)
                        is NodeOutput.Console -> Unit
                    }
                }
            }
            advanceTimeBy(200L)
            advanceUntilIdle()

            val waiting = results.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().firstOrNull()
            assertNotNull("Global override must force HITL prompt for READ_ONLY", waiting)
            assertEquals(ToolRisk.READ_ONLY, waiting!!.risk)
            verify(exactly = 1) {
                approvalNotifier.sendApprovalRequest("session-1", toolName, "args", ToolRisk.READ_ONLY)
            }
            job.cancel()
        }

    @Test
    fun `given SENSITIVE tool and global override off when execute then approval emitted with SENSITIVE risk`() =
        runTest {
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
            every { settingsRepository.blockDestructiveTools } returns flowOf(false)
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
            coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

            val toolName = "SensTool"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
            coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "SensTool", "arguments": "args"}""")

            val results = mutableListOf<Any>()
            val job = launch {
                executor.execute(node, "Do", "session-1", "").collect { output ->
                    when (output) {
                        is NodeOutput.State -> results.add(output.state)
                        is NodeOutput.Result -> results.add(output.result)
                        is NodeOutput.Console -> Unit
                    }
                }
            }
            advanceTimeBy(200L)
            advanceUntilIdle()

            val waiting = results.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().firstOrNull()
            assertNotNull("SENSITIVE tools must always trigger HITL prompt", waiting)
            assertEquals(ToolRisk.SENSITIVE, waiting!!.risk)
            verify(exactly = 1) {
                approvalNotifier.sendApprovalRequest("session-1", toolName, "args", ToolRisk.SENSITIVE)
            }
            job.cancel()
        }

    @Test
    fun `given DESTRUCTIVE tool and global override off when execute then approval emitted with DESTRUCTIVE risk`() =
        runTest {
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
            every { settingsRepository.blockDestructiveTools } returns flowOf(false)
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
            coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.DESTRUCTIVE

            val toolName = "DestTool"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
            coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "DestTool", "arguments": "args"}""")

            val results = mutableListOf<Any>()
            val job = launch {
                executor.execute(node, "Do", "session-1", "").collect { output ->
                    when (output) {
                        is NodeOutput.State -> results.add(output.state)
                        is NodeOutput.Result -> results.add(output.result)
                        is NodeOutput.Console -> Unit
                    }
                }
            }
            advanceTimeBy(200L)
            advanceUntilIdle()

            val waiting = results.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().firstOrNull()
            assertNotNull("DESTRUCTIVE tools must always trigger HITL prompt", waiting)
            assertEquals(ToolRisk.DESTRUCTIVE, waiting!!.risk)
            verify(exactly = 1) {
                approvalNotifier.sendApprovalRequest("session-1", toolName, "args", ToolRisk.DESTRUCTIVE)
            }
            job.cancel()
        }

    @Test
    fun `given a pending approval when resumeWithApproval then the approval notification is cancelled`() {
        // Answering the request from the in-chat card must dismiss any live-phase
        // notification that was posted while the app was backgrounded, so a stale
        // shade entry cannot offer a choice that has already been made.
        toolInvocationGate.resumeWithApproval("session-1", isApproved = true)

        verify(exactly = 1) { approvalNotifier.cancelApprovalNotification("session-1") }
    }

    @Test
    fun `given DESTRUCTIVE tool and blockDestructiveTools on when execute then emits error result and skips HITL`() =
        runTest {
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
            every { settingsRepository.blockDestructiveTools } returns flowOf(true)
            coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.DESTRUCTIVE

            val toolName = "DestTool"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
            coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "DestTool", "arguments": "args"}""")

            val states = executor.execute(node, "Do", "session-1", "").toList().unwrap()

            val finalResult = states.filterIsInstance<NodeExecutionResult>().lastOrNull()
            assertNotNull("Policy-blocked destructive must surface a structured result", finalResult)
            assertNotNull(
                "Policy block must populate `error`, NOT `outputText`, so the orchestrator " +
                    "treats the node as failed and the planner does not retry.",
                finalResult!!.error,
            )
            assertTrue(finalResult.error!!.contains("blocked by Settings", ignoreCase = true))
            assertEquals(null, finalResult.outputText)
            coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
            verify(exactly = 0) { approvalNotifier.sendApprovalRequest(any(), any(), any(), any()) }
        }

    @Test
    fun `given getRisk throws when execute then emits structured error result`() = runTest {
        coEvery { toolRepository.getRisk(any(), any()) } throws
            IllegalArgumentException("Unknown tool: HallucinatedTool")

        val toolName = "HallucinatedTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "HallucinatedTool", "arguments": "args"}""")

        val states = executor.execute(node, "Do", "session-1", "").toList().unwrap()

        val finalResult = states.filterIsInstance<NodeExecutionResult>().lastOrNull()
        assertNotNull(finalResult)
        assertNotNull("getRisk failure must surface as a structured error", finalResult!!.error)
        assertTrue(finalResult.error!!.contains("Risk lookup failed", ignoreCase = true))
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        verify(exactly = 0) { approvalNotifier.sendApprovalRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `given SENSITIVE tool and user denies when execute then emits execution denied observation`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

        val toolName = "SensTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "SensTool", "arguments": "args"}""")

        val results = mutableListOf<Any>()
        val job = launch {
            executor.execute(node, "Do", "session-1", "").collect { output ->
                when (output) {
                    is NodeOutput.State -> results.add(output.state)
                    is NodeOutput.Result -> results.add(output.result)
                    is NodeOutput.Console -> Unit
                }
            }
        }
        // Flush pending tasks WITHOUT advancing virtual time so the executor
        // suspends inside withTimeout(...) without firing the 5s timeout.
        runCurrent()
        executor.resumeWithApproval("session-1", isApproved = false)
        advanceUntilIdle()

        val finalResult = results.filterIsInstance<NodeExecutionResult>().lastOrNull()
        assertNotNull(finalResult)
        assertEquals("Execution denied by user", finalResult!!.outputText)
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        job.cancel()
    }

    // --- HITL journalling ---------------------------------------------------
    //
    // Every gate the tool path raises must reach the trigger journal, so that a
    // background run which stopped to ask is distinguishable afterwards from one
    // that never asked. Reported for all runs; the journal drops what it cannot
    // attribute to a fired trigger.

    /** Stages a SENSITIVE tool call that will raise the approval gate. */
    private fun stageSensitiveCall(toolName: String = "SensTool"): NodeModel {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "$toolName", "arguments": "args"}""")
        coEvery { toolRepository.executeTool(any(), any(), any()) } returns "ok"
        return NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
    }

    @Test
    fun `given an approval answered live when execute then the gate is journalled raised then approved`() = runTest {
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        val node = stageSensitiveCall()

        val job = launch { executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { } }
        runCurrent()
        executor.resumeWithApproval("session-1", isApproved = true)
        advanceUntilIdle()

        // The whole point: an approval given inside the live window never parks,
        // and must still be visible as a gate that happened and was approved.
        coVerifyOrder {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Raised(PendingInteractionKind.APPROVAL))
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.APPROVED))
        }
        coVerify(exactly = 0) { recordTriggerHitlEvent(any(), TriggerHitlEvent.Parked) }
        job.cancel()
    }

    @Test
    fun `given the user denies live when execute then the gate is journalled as denied`() = runTest {
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        val node = stageSensitiveCall()

        val job = launch { executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { } }
        runCurrent()
        executor.resumeWithApproval("session-1", isApproved = false)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.DENIED))
        }
        job.cancel()
    }

    @Test
    fun `given the live phase times out and the park is durable when execute then the gate is journalled parked`() =
        runTest {
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
            val node = stageSensitiveCall()

            val job = launch { executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { } }
            advanceTimeBy(200L)
            advanceUntilIdle()

            coVerifyOrder {
                recordTriggerHitlEvent("run-1", TriggerHitlEvent.Raised(PendingInteractionKind.APPROVAL))
                recordTriggerHitlEvent("run-1", TriggerHitlEvent.Parked)
            }
            // A parked gate is unresolved until the user answers (or the window
            // closes) — settling it here would forge an answer nobody gave.
            coVerify(exactly = 0) { recordTriggerHitlEvent(any(), ofType(TriggerHitlEvent.Resolved::class)) }
            job.cancel()
        }

    @Test
    fun `given the park cannot be persisted when execute then the gate is journalled as abandoned`() = runTest {
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
        coEvery { pendingInteractionRepository.save(any()) } returns false
        val node = stageSensitiveCall()

        val job = launch { executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { } }
        advanceTimeBy(200L)
        advanceUntilIdle()

        // Nobody was ever given the chance to answer — that is ABANDONED, and
        // specifically not the TIMED_OUT of a request that did reach the user.
        coVerify(exactly = 1) {
            recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.ABANDONED))
        }
        job.cancel()
    }

    @Test
    fun `given a resumed run carrying a parked decision when execute then only the resolution is journalled`() =
        runTest {
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
            val node = stageSensitiveCall()
            coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
                runId = "run-1",
                sessionId = "session-1",
                kind = PendingInteractionKind.APPROVAL,
                toolName = "SensTool",
                toolArgs = "args",
                risk = ToolRisk.SENSITIVE,
                decision = PendingDecision.APPROVED,
                requestedAt = 0L,
            )

            executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { }

            // The gate was raised (and counted) in the earlier process; counting
            // it again on resume would double-count a single request.
            coVerify(exactly = 0) { recordTriggerHitlEvent(any(), ofType(TriggerHitlEvent.Raised::class)) }
            coVerify(exactly = 1) {
                recordTriggerHitlEvent("run-1", TriggerHitlEvent.Resolved(TriggerHitlResolution.APPROVED))
            }
        }

    @Test
    fun `given a tool needing no approval when execute then no gate is journalled`() = runTest {
        val toolName = "MyTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "MyTool", "arguments": "args"}""")
        coEvery { toolRepository.executeTool(any(), any(), any()) } returns "ok"

        executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { }

        coVerify(exactly = 0) { recordTriggerHitlEvent(any(), any()) }
    }

    @Test
    fun `pendingApprovalFor exposes the suspended request and clears after resolution`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(5_000L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

        val toolName = "SensTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "SensTool", "arguments": "args"}""")
        coEvery { toolRepository.executeTool(any(), any(), any()) } returns "ok"

        assertNull(executor.pendingApprovalFor("session-1"))

        val job = launch {
            executor.execute(node, "Do", "session-1", "").collect { }
        }
        runCurrent()

        // Suspended on the approval gate: the snapshot must be addressable.
        val pending = executor.pendingApprovalFor("session-1")
        assertNotNull(pending)
        assertEquals(toolName, pending!!.toolName)
        assertEquals(ToolRisk.SENSITIVE, pending.risk)
        assertNull("Other sessions must not see the request", executor.pendingApprovalFor("session-2"))

        executor.resumeWithApproval("session-1", isApproved = true)
        advanceUntilIdle()

        assertNull("Resolved request must be cleared", executor.pendingApprovalFor("session-1"))
        job.cancel()
    }

    @Test
    fun `pendingApprovalFor clears when the suspended gate is cancelled`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(60_000L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

        val toolName = "SensTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "SensTool", "arguments": "args"}""")

        val job = launch {
            executor.execute(node, "Do", "session-1", "").collect { }
        }
        runCurrent()
        assertNotNull(executor.pendingApprovalFor("session-1"))

        // Plain cancellation of the suspended gate (scope teardown, an
        // abandoned editor test run) must not leak the holder — a stale
        // entry would serve a request no coroutine can ever settle.
        job.cancel()
        runCurrent()

        assertNull("Cancelled gate must clear its pending request", executor.pendingApprovalFor("session-1"))
    }

    @Test
    fun `pendingApprovalFor clears after the approval times out`() = runTest {
        every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.SensitiveOrDestructive)
        every { settingsRepository.blockDestructiveTools } returns flowOf(false)
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(1_000L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE

        val toolName = "SensTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "SensTool", "arguments": "args"}""")

        val job = launch {
            executor.execute(node, "Do", "session-1", "").collect { }
        }
        runCurrent()
        assertNotNull(executor.pendingApprovalFor("session-1"))

        advanceUntilIdle() // virtual time fires the 1s approval timeout

        assertNull("Timed-out request must be cleared", executor.pendingApprovalFor("session-1"))
        job.cancel()
    }

    @Test
    fun `execute uses LLM for auto mode to select tool and generate arguments`() = runTest {
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "auto")
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool("ToolA", "DescA", "SchemaA"),
            AgentTool("ToolB", "DescB", "SchemaB"),
        )
        every { llmEngine.generateResponseStream(any()) } returns flowOf("""{"tool": "ToolB", "arguments": "arg_b"}""")
        coEvery { toolRepository.executeTool("ToolB", "arg_b", any()) } returns "Tool B Success"

        val states = executor.execute(node, "Do B", "session-1", "").toList().unwrap()

        val lastState = states.last() as NodeExecutionResult
        assertEquals("Tool B Success", lastState.outputText)
    }

    @Test
    fun `execute treats a blank tool name as auto-select`() = runTest {
        // The editor's "Auto" tool option persists as a null / blank toolName
        // (NodeConfigCodec maps an empty toolId to null). It must behave like
        // the explicit "auto" sentinel — the LLM picks a tool — rather than
        // failing with "missing toolName configuration".
        listOf(null, "", "   ").forEach { blank ->
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = blank)
            coEvery { toolRepository.getAvailableTools() } returns listOf(
                AgentTool("ToolA", "DescA", "SchemaA"),
            )
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "ToolA", "arguments": "arg_a"}""")
            coEvery { toolRepository.executeTool("ToolA", "arg_a", any()) } returns "Tool A Success"

            val states = executor.execute(node, "Do A", "session-1", "").toList().unwrap()

            val last = states.last() as NodeExecutionResult
            assertEquals(
                "blank toolName <$blank> should auto-select and run the tool",
                "Tool A Success",
                last.outputText,
            )
            assertNull("auto-select must not error for blank toolName <$blank>", last.error)
        }
    }

    // ─── Two-phase background waiting ───────────────────────────────────────

    /** Arms a SENSITIVE single-tool gate with a 100ms live window. */
    private fun armSensitiveGate(toolName: String = "MyTool") {
        every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
        coEvery { toolRepository.getRisk(any(), any()) } returns ToolRisk.SENSITIVE
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any()) } returns
            flowOf("""{"tool": "$toolName", "arguments": "args"}""")
    }

    @Test
    fun `given live timeout on a persisted run when execute then parks instead of failing`() = runTest {
        armSensitiveGate()
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

        val results = mutableListOf<Any>()
        val job = launch {
            executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { output ->
                when (output) {
                    is NodeOutput.State -> results.add(output.state)
                    is NodeOutput.Result -> results.add(output.result)
                    is NodeOutput.Console -> Unit
                }
            }
        }
        advanceTimeBy(200L)
        advanceUntilIdle()

        // The flow ends with the parked state and NO error / result.
        assertTrue(results.last() is AgentOrchestratorState.SuspendedInBackground)
        assertEquals(
            PendingInteractionKind.APPROVAL,
            (results.last() as AgentOrchestratorState.SuspendedInBackground).kind,
        )
        assertTrue(results.filterIsInstance<NodeExecutionResult>().isEmpty())
        coVerify {
            pendingInteractionRepository.save(
                match {
                    it.runId == "run-1" &&
                        it.kind == PendingInteractionKind.APPROVAL &&
                        it.toolName == "MyTool" &&
                        it.toolArgs == "args" &&
                        it.risk == ToolRisk.SENSITIVE
                },
            )
        }
        verify {
            approvalNotifier.sendPersistentApprovalRequest("run-1", "session-1", "MyTool", "args", ToolRisk.SENSITIVE)
        }
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        job.cancel()
    }

    @Test
    fun `given a run parking on the gate when the durable record is written then the live gate is already gone`() =
        runTest {
            // The two states must never be observable at the same time. While
            // both exist, a notification approval takes SubmitApprovalDecision's
            // live short-circuit and resolves a deferred whose withTimeout has
            // already expired — the user's decision is swallowed and the run
            // stays parked. Asserting at the moment of the durable save (rather
            // than after the flow ends) is what pins the ordering: checking
            // afterwards would pass even with the `finally`-only cleanup.
            armSensitiveGate()
            val liveGateAtParkTime = mutableListOf<Any?>()
            coEvery { pendingInteractionRepository.save(any()) } answers {
                liveGateAtParkTime += executor.pendingApprovalFor("session-1")
                true
            }
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

            val job = launch {
                executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { }
            }
            advanceTimeBy(200L)
            advanceUntilIdle()

            assertEquals("the durable park must have happened", 1, liveGateAtParkTime.size)
            assertNull("live gate must be retired before the durable record exists", liveGateAtParkTime.single())
            job.cancel()
        }

    @Test
    fun `given a DESTRUCTIVE delete_file on a persisted run when live timeout then parks for background approval`() =
        runTest {
            // The workspace write tools carry no bespoke HITL code — they flow through
            // the same risk-driven gate. This pins the background-approval path for the
            // contour: delete_file is DESTRUCTIVE, so on a live-window timeout the run
            // parks (rather than failing) with the exact call snapshot for later resume.
            every { settingsRepository.toolCallTimeoutMs } returns flowOf(100L)
            coEvery { toolRepository.getRisk("delete_file", any()) } returns ToolRisk.DESTRUCTIVE
            coEvery { toolRepository.getAvailableTools() } returns
                listOf(AgentTool("delete_file", "Deletes a file", "Schema"))
            every { llmEngine.generateResponseStream(any()) } returns
                flowOf("""{"tool": "delete_file", "arguments": "{\"path\":\"reports/old.md\"}"}""")
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "delete_file")

            val results = mutableListOf<Any>()
            val job = launch {
                executor.execute(node, "Delete it", "session-1", "", runId = "run-9").collect { output ->
                    when (output) {
                        is NodeOutput.State -> results.add(output.state)
                        is NodeOutput.Result -> results.add(output.result)
                        is NodeOutput.Console -> Unit
                    }
                }
            }
            advanceTimeBy(200L)
            advanceUntilIdle()

            assertTrue(results.last() is AgentOrchestratorState.SuspendedInBackground)
            coVerify {
                pendingInteractionRepository.save(
                    match {
                        it.runId == "run-9" &&
                            it.kind == PendingInteractionKind.APPROVAL &&
                            it.toolName == "delete_file" &&
                            it.toolArgs == """{"path":"reports/old.md"}""" &&
                            it.risk == ToolRisk.DESTRUCTIVE
                    },
                )
            }
            verify {
                approvalNotifier.sendPersistentApprovalRequest(
                    "run-9",
                    "session-1",
                    "delete_file",
                    """{"path":"reports/old.md"}""",
                    ToolRisk.DESTRUCTIVE,
                )
            }
            coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
            job.cancel()
        }

    @Test
    fun `given live timeout and park persistence fails when execute then falls back to timeout error`() = runTest {
        armSensitiveGate()
        coEvery { pendingInteractionRepository.save(any()) } returns false
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

        val results = mutableListOf<Any>()
        val job = launch {
            executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { output ->
                when (output) {
                    is NodeOutput.State -> results.add(output.state)
                    is NodeOutput.Result -> results.add(output.result)
                    is NodeOutput.Console -> Unit
                }
            }
        }
        advanceTimeBy(200L)
        advanceUntilIdle()

        val lastResult = results.filterIsInstance<NodeExecutionResult>().lastOrNull()
        assertNotNull(lastResult)
        assertTrue(lastResult!!.error!!.contains("timed out", ignoreCase = true))
        verify(exactly = 0) { approvalNotifier.sendPersistentApprovalRequest(any(), any(), any(), any(), any()) }
        job.cancel()
    }

    @Test
    fun `given recorded APPROVED decision with matching args when execute then runs without a new gate`() = runTest {
        armSensitiveGate()
        coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
            runId = "run-1",
            sessionId = "session-1",
            kind = PendingInteractionKind.APPROVAL,
            toolName = "MyTool",
            toolArgs = "args",
            risk = ToolRisk.SENSITIVE,
            decision = PendingDecision.APPROVED,
            requestedAt = 0L,
        )
        coEvery { toolRepository.executeTool("MyTool", "args", any()) } returns "ok"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

        val states = executor.execute(node, "Do", "session-1", "", runId = "run-1").toList().unwrap()

        val result = states.filterIsInstance<NodeExecutionResult>().last()
        assertEquals("ok", result.outputText)
        // No fresh gate was raised and the one-shot record was consumed.
        assertTrue(states.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().isEmpty())
        coVerify { pendingInteractionRepository.delete("run-1") }
        verify(exactly = 0) { approvalNotifier.sendApprovalRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `given parked approval but policy relaxed to NeverPrompt when execute then clears the record and runs`() =
        runTest {
            // Run parked under a stricter policy; the user then switched to
            // NeverPrompt before resuming, so no fresh approval is needed — but
            // the durable record must still be cleared, not orphaned.
            armSensitiveGate()
            every { settingsRepository.toolApprovalPolicy } returns flowOf(ToolApprovalPolicy.NeverPrompt)
            coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
                runId = "run-1",
                sessionId = "session-1",
                kind = PendingInteractionKind.APPROVAL,
                toolName = "MyTool",
                toolArgs = "args",
                risk = ToolRisk.SENSITIVE,
                decision = null,
                requestedAt = 0L,
            )
            coEvery { toolRepository.executeTool("MyTool", "args", any()) } returns "ok"
            val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

            val states = executor.execute(node, "Do", "session-1", "", runId = "run-1").toList().unwrap()

            val result = states.filterIsInstance<NodeExecutionResult>().last()
            assertEquals("ok", result.outputText)
            assertTrue(states.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().isEmpty())
            coVerify { pendingInteractionRepository.delete("run-1") }
        }

    @Test
    fun `given recorded DENIED decision with matching args when execute then denies without a new gate`() = runTest {
        armSensitiveGate()
        coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
            runId = "run-1",
            sessionId = "session-1",
            kind = PendingInteractionKind.APPROVAL,
            toolName = "MyTool",
            toolArgs = "args",
            risk = ToolRisk.SENSITIVE,
            decision = PendingDecision.DENIED,
            requestedAt = 0L,
        )
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

        val states = executor.execute(node, "Do", "session-1", "", runId = "run-1").toList().unwrap()

        val result = states.filterIsInstance<NodeExecutionResult>().last()
        assertEquals("Execution denied by user", result.outputText)
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        coVerify { pendingInteractionRepository.delete("run-1") }
    }

    @Test
    fun `given recorded decision but different resolved args when execute then raises a fresh gate`() = runTest {
        armSensitiveGate()
        // TOCTOU guard: the user approved "old-args", but the re-resolved call
        // carries "args" — the stale decision must not authorise it.
        coEvery { pendingInteractionRepository.getForRun("run-1") } returns PendingInteraction(
            runId = "run-1",
            sessionId = "session-1",
            kind = PendingInteractionKind.APPROVAL,
            toolName = "MyTool",
            toolArgs = "old-args",
            risk = ToolRisk.SENSITIVE,
            decision = PendingDecision.APPROVED,
            requestedAt = 0L,
        )
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "MyTool")

        val results = mutableListOf<Any>()
        val job = launch {
            executor.execute(node, "Do", "session-1", "", runId = "run-1").collect { output ->
                when (output) {
                    is NodeOutput.State -> results.add(output.state)
                    is NodeOutput.Result -> results.add(output.result)
                    is NodeOutput.Console -> Unit
                }
            }
        }
        runCurrent()

        // A fresh live gate was raised (stale record consumed, no auto-approve).
        assertTrue(results.filterIsInstance<AgentOrchestratorState.WaitingForApproval>().isNotEmpty())
        coVerify { pendingInteractionRepository.delete("run-1") }
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
        job.cancel()
    }

    // ─── Structured-output gate ─────────────────────────────────────────────

    @Test
    fun `given malformed then valid tool arguments when execute then repairs and runs the tool`() = runTest {
        val toolName = "MyTool"
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = toolName)
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool(toolName, "Desc", "Schema"))
        every { llmEngine.generateResponseStream(any(), any(), any()) } returnsMany listOf(
            flowOf("sorry, here is the json"), // not a JSON object → triggers repair
            flowOf("""{"tool": "MyTool", "arguments": "good_args"}"""),
        )
        coEvery { toolRepository.executeTool(toolName, "good_args", any()) } returns "Tool Success"

        val outputs = executor.execute(node, "Do something", "session-1", "").toList()

        assertEquals("Tool Success", outputs.lastResult().outputText)
        assertEquals(1, outputs.consoleEvents().count { it.type == ConsoleEventType.StructuredOutputRepair })
    }

    @Test
    fun `given auto-select reply never parses when execute then emits a parse error after repairs`() = runTest {
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(0)
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "auto")
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("ToolA", "DescA", "SchemaA"))
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("I cannot decide")

        val outputs = executor.execute(node, "Do", "session-1", "").toList()

        val result = outputs.lastResult()
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Failed to parse tool selection", ignoreCase = true))
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
    }

    @Test
    fun `given auto-select names a tool outside the catalogue when execute then it fails at selection`() = runTest {
        // The reply is well-formed JSON, so the structured-output gate is happy — the
        // name itself is the problem. Before this check the bad name travelled to the
        // HITL gate and first surfaced at `getRisk` as "Risk lookup failed …
        // Unknown tool", which reads like an internal fault rather than a bad pick.
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "auto")
        coEvery { toolRepository.getAvailableTools() } returns listOf(
            AgentTool("get-resource-alpha", "DescA", "SchemaA"),
            AgentTool("get-resource-beta", "DescB", "SchemaB"),
        )
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns
            flowOf("""{"tool": "get-resource", "arguments": "x"}""")

        val outputs = executor.execute(node, "Do", "session-1", "").toList()

        val result = outputs.lastResult()
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("get-resource"))
        assertTrue(result.error!!.contains("not found in available tools"))
        // The point of the fix: the rejection happens at the point of choice, so the
        // risk lookup and the execution are never reached at all.
        coVerify(exactly = 0) { toolRepository.getRisk(any(), any()) }
        coVerify(exactly = 0) { toolRepository.executeTool(any(), any(), any()) }
    }

    @Test
    fun `given auto-selected name is padded with whitespace when execute then the tool still runs`() = runTest {
        // Surrounding whitespace is model noise, not a different tool — normalising it
        // is safe. A near-miss is deliberately NOT repaired to the closest candidate:
        // running a different tool than the model named would move the call across the
        // HITL risk boundary.
        val node = NodeModel("1", NodeType.TOOL, 0f, 0f, toolName = "auto")
        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("ToolA", "DescA", "SchemaA"))
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns
            flowOf("""{"tool": "  ToolA  ", "arguments": "arg_a"}""")
        coEvery { toolRepository.executeTool("ToolA", "arg_a", any()) } returns "Tool A Success"

        val outputs = executor.execute(node, "Do A", "session-1", "").toList()

        val result = outputs.lastResult()
        assertNull(result.error)
        assertEquals("Tool A Success", result.outputText)
    }

    private companion object {
        const val LEAKED_KEY = "AIzaSyTESTKEY"
        const val LEAKING_PROVIDER_ERROR =
            "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini:streamGenerateContent?alt=sse&key=$LEAKED_KEY]"
    }
}
