package app.knotwork.android.data.engine

import app.knotwork.android.domain.engine.GraphExecutionEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.TaskPriority
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AttachmentStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskQueueManagerImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var chatRepository: ChatRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var graphExecutionEngine: GraphExecutionEngine
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var runTraceRepository: RunTraceRepository
    private lateinit var attachmentStore: AttachmentStore

    private lateinit var taskQueueManager: TaskQueueManagerImpl

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        chatRepository = mockk(relaxed = true)
        pipelineRepository = mockk()
        settingsRepository = mockk()
        graphExecutionEngine = mockk()
        pipelineRunRepository = mockk(relaxed = true)
        runTraceRepository = mockk(relaxed = true)
        attachmentStore = mockk(relaxed = true)

        // Baseline library: a single pipeline marked as the user default, so
        // tests that exercise unrelated behaviour (eviction, cancellation)
        // resolve a pipeline without caring about the resolution chain.
        val seedPipeline = PipelineGraph(id = "seed-id", name = "Seed")
        every { pipelineRepository.getAllPipelines() } returns flowOf(listOf(seedPipeline))
        every { settingsRepository.defaultPipelineId } returns flowOf("seed-id")

        taskQueueManager = TaskQueueManagerImpl(
            chatRepository = chatRepository,
            pipelineRepository = pipelineRepository,
            settingsRepository = settingsRepository,
            graphExecutionEngine = graphExecutionEngine,
            pipelineRunRepository = pipelineRunRepository,
            runTraceRepository = runTraceRepository,
            attachmentStore = attachmentStore,
        ).apply {
            dispatcher = testDispatcher
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given 21 terminal sessions when state flows created then oldest terminal is evicted`() {
        repeat(21) { i ->
            taskQueueManager.observeTaskState("session_$i")
        }
        // All sessions start as Idle (terminal), so session_0 must be evicted to make room for session_20
        assertEquals(20, taskQueueManager.sessionStates.size)
        assertTrue("session_0 should be evicted", !taskQueueManager.sessionStates.containsKey("session_0"))
        assertTrue("session_20 should be present", taskQueueManager.sessionStates.containsKey("session_20"))
    }

    @Test
    fun `given all sessions are active when at capacity then no session is evicted`() {
        repeat(20) { i ->
            taskQueueManager.observeTaskState("session_$i")
            // Push the session into a non-terminal state. The per-session
            // flow is a SharedFlow now (was a StateFlow); `tryEmit`
            // replaces the `.value =` setter while keeping the test free
            // of suspending calls.
            taskQueueManager.sessionStates["session_$i"]?.tryEmit(AgentOrchestratorState.Loading)
        }
        taskQueueManager.observeTaskState("session_20")
        // No terminal session to evict, so size grows beyond MAX_SESSION_STATES
        assertEquals(21, taskQueueManager.sessionStates.size)
        assertTrue("active session_0 must not be evicted", taskQueueManager.sessionStates.containsKey("session_0"))
    }

    /**
     * Tests that enqueuing a task processes it successfully and updates the session state
     * without race conditions or deadlocks.
     */
    @Test
    fun `enqueueTask processes task successfully`() = testScope.runTest {
        val sessionId = "session_1"
        val prompt = "Hello"

        val task = AgentTask(
            sessionId = sessionId,
            prompt = prompt,
            priority = TaskPriority.NORMAL,
        )

        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("Result"))

        taskQueueManager.enqueueTask(task)

        advanceUntilIdle() // Wait for the IO dispatcher to process the task without delay

        val state = taskQueueManager.observeTaskState(sessionId).first()
        println("Final state: $state")
        assertTrue(
            "Expected Completed or Idle after processing, got $state",
            state is AgentOrchestratorState.Idle || state is AgentOrchestratorState.Completed,
        )
    }

    @Test
    fun `enqueueTask with an image attachment passes a resolved image input to the engine`() = testScope.runTest {
        val attachment = MessageAttachment(path = "p.jpg", mimeType = "image/jpeg", width = 640, height = 480)
        every { attachmentStore.absolutePathFor("p.jpg") } returns "/abs/p.jpg"
        coEvery { attachmentStore.sizeBytes("p.jpg") } returns 2048L

        val imageSlot = slot<EngineImageInput>()
        every {
            // Named rather than positional: this stub used to count arguments,
            // and adding one to the engine's signature silently re-aimed the
            // capture at a different parameter. The compiler caught it that
            // time only because the types happened to disagree.
            graphExecutionEngine.invoke(
                sessionId = any(),
                userPrompt = any(),
                graph = any(),
                runId = any(),
                resume = any(),
                depth = any(),
                budget = any(),
                stuckDetector = any(),
                imageInput = capture(imageSlot),
            )
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s1", prompt = "hi", attachment = attachment))
        advanceUntilIdle()

        val captured = imageSlot.captured
        assertNotNull("Engine must receive a resolved image input", captured)
        assertEquals("/abs/p.jpg", captured.absolutePath)
        assertEquals(640, captured.width)
        assertEquals(480, captured.height)
        assertEquals(2048L, captured.sizeBytes)
    }

    @Test
    fun `enqueueTask without an attachment passes a null image input to the engine`() = testScope.runTest {
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any(), any(), any(), any(), isNull())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s1", prompt = "hi"))
        advanceUntilIdle()

        // A text-only run resolves no image input; the engine is invoked with null.
        verify { graphExecutionEngine.invoke(any(), any(), any(), any(), any(), any(), any(), isNull()) }
    }

    /**
     * When an [AgentTask] carries a `pipelineId`, the queue
     * must execute that specific pipeline rather than the global default
     * (the first pipeline in the repository).
     */
    @Test
    fun `enqueueTask honours per-task pipelineId`() = testScope.runTest {
        val defaultPipeline = PipelineGraph(id = "default-id", name = "Default")
        val boundPipeline = PipelineGraph(id = "bound-id", name = "Bound")
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(defaultPipeline, boundPipeline),
        )
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        val task = AgentTask(
            sessionId = "session-bound",
            prompt = "go",
            priority = TaskPriority.NORMAL,
            pipelineId = "bound-id",
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        verify {
            graphExecutionEngine.invoke(
                "session-bound",
                "go",
                match<PipelineGraph> { it.id == "bound-id" },
                any(),
            )
        }
    }

    /**
     * When the bound pipeline has been deleted while the task waited in
     * the queue, fall back to the user-marked default rather than failing
     * the task. The chat-level UI handles the rebind + Snackbar
     * notification separately. The default is deliberately not the first
     * element of the library to prove the resolution is order-independent.
     */
    @Test
    fun `enqueueTask falls back to user default when bound id is missing`() = testScope.runTest {
        val otherPipeline = PipelineGraph(id = "other-id", name = "Other")
        val defaultPipeline = PipelineGraph(id = "default-id", name = "Default")
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(otherPipeline, defaultPipeline),
        )
        every { settingsRepository.defaultPipelineId } returns flowOf("default-id")
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        val task = AgentTask(
            sessionId = "session-orphaned",
            prompt = "go",
            priority = TaskPriority.NORMAL,
            pipelineId = "missing-id",
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        verify {
            graphExecutionEngine.invoke(
                "session-orphaned",
                "go",
                match<PipelineGraph> { it.id == "default-id" },
                any(),
            )
        }
    }

    /**
     * When the task carries no `pipelineId`, the queue uses the
     * user-marked default from `SettingsRepository.defaultPipelineId` —
     * never "whatever the repository returned first". The default sits
     * last in the library to prove the order does not matter.
     */
    @Test
    fun `enqueueTask uses default pipeline when task has no pipelineId`() = testScope.runTest {
        val otherPipeline = PipelineGraph(id = "other-id", name = "Other")
        val defaultPipeline = PipelineGraph(id = "default-id", name = "Default")
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(otherPipeline, defaultPipeline),
        )
        every { settingsRepository.defaultPipelineId } returns flowOf("default-id")
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        val task = AgentTask(
            sessionId = "session-unbound",
            prompt = "go",
            priority = TaskPriority.NORMAL,
            pipelineId = null,
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        verify {
            graphExecutionEngine.invoke(
                "session-unbound",
                "go",
                match<PipelineGraph> { it.id == "default-id" },
                any(),
            )
        }
    }

    /**
     * No binding and no configured default must surface an explicit,
     * actionable `Error` — even though the library is non-empty, the
     * queue never executes an arbitrary pipeline.
     */
    @Test
    fun `enqueueTask errors when no binding and no default configured`() = testScope.runTest {
        val pipelines = listOf(
            PipelineGraph(id = "a-id", name = "A"),
            PipelineGraph(id = "b-id", name = "B"),
        )
        every { pipelineRepository.getAllPipelines() } returns flowOf(pipelines)
        every { settingsRepository.defaultPipelineId } returns flowOf(null)

        val task = AgentTask(
            sessionId = "session-no-default",
            prompt = "go",
            priority = TaskPriority.NORMAL,
            pipelineId = null,
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState("session-no-default").first()
        assertTrue("Expected Error, got $state", state is AgentOrchestratorState.Error)
        assertEquals(
            "No default pipeline configured. Set one in Settings or bind a pipeline to this chat.",
            (state as AgentOrchestratorState.Error).message,
        )
        verify(exactly = 0) { graphExecutionEngine.invoke(any(), any(), any(), any()) }
    }

    /**
     * A stale binding combined with a missing default has nothing left in
     * the resolution chain — the task fails with the same explicit error
     * instead of silently substituting a library pipeline.
     */
    @Test
    fun `enqueueTask errors when bound id is missing and no default configured`() = testScope.runTest {
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(PipelineGraph(id = "a-id", name = "A")),
        )
        every { settingsRepository.defaultPipelineId } returns flowOf(null)

        val task = AgentTask(
            sessionId = "session-orphaned-no-default",
            prompt = "go",
            priority = TaskPriority.NORMAL,
            pipelineId = "missing-id",
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState("session-orphaned-no-default").first()
        assertTrue("Expected Error, got $state", state is AgentOrchestratorState.Error)
        verify(exactly = 0) { graphExecutionEngine.invoke(any(), any(), any(), any()) }
    }

    /**
     * An empty pipeline library keeps its own, more specific error copy —
     * "Set one in Settings" would be misleading when there is nothing to
     * set a default to.
     */
    @Test
    fun `enqueueTask errors with create-one message when library is empty`() = testScope.runTest {
        every { pipelineRepository.getAllPipelines() } returns flowOf(emptyList())
        every { settingsRepository.defaultPipelineId } returns flowOf(null)

        val task = AgentTask(
            sessionId = "session-empty-library",
            prompt = "go",
            priority = TaskPriority.NORMAL,
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState("session-empty-library").first()
        assertTrue("Expected Error, got $state", state is AgentOrchestratorState.Error)
        assertEquals(
            "No active pipeline found. Please create one in the Visual Orchestrator.",
            (state as AgentOrchestratorState.Error).message,
        )
    }

    /**
     * Cancelling the worker scope while a task is mid-execution must never
     * surface as a user-facing `Error`: the `CancellationException` is
     * re-thrown (cooperative cancellation) and the `finally` block resets the
     * session to `Idle` under `NonCancellable`.
     */
    @Test
    fun `given running task when scope is cancelled then state resets to Idle not Error`() = testScope.runTest {
        val sessionId = "session_cancelled"
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }

        taskQueueManager.enqueueTask(
            AgentTask(sessionId = sessionId, prompt = "p", priority = TaskPriority.NORMAL),
        )
        // Task is now suspended inside the engine flow. `runCurrent` rather
        // than `advanceUntilIdle`: advancing virtual time would trip the
        // no-progress valve on a run that is deliberately silent, and this
        // test is about cancellation, not about the valve.
        runCurrent()

        taskQueueManager.scope.cancel()
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState(sessionId).first()
        assertTrue("Cancellation must not surface as Error, got $state", state !is AgentOrchestratorState.Error)
        assertEquals(AgentOrchestratorState.Idle, state)
        assertEquals(AgentOrchestratorState.Idle, taskQueueManager.globalState.value)
    }

    /**
     * A `CancellationException` escaping the engine flow itself (e.g. an
     * executor honouring a Stop) must propagate out of `processTask` instead
     * of being mapped to `Error`; the session still settles on `Idle`.
     */
    @Test
    fun `given engine flow throws CancellationException then state settles on Idle not Error`() = testScope.runTest {
        val sessionId = "session_engine_ce"
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            throw CancellationException("user stop")
        }

        taskQueueManager.enqueueTask(
            AgentTask(sessionId = sessionId, prompt = "p", priority = TaskPriority.NORMAL),
        )
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState(sessionId).first()
        assertTrue("Cancellation must not surface as Error, got $state", state !is AgentOrchestratorState.Error)
        assertEquals(AgentOrchestratorState.Idle, state)
    }

    /**
     * Counter-case locking the other side of the contract: a plain exception
     * from the engine flow is still mapped to a user-facing `Error` state.
     */
    @Test
    fun `given engine flow throws plain exception then state is Error`() = testScope.runTest {
        val sessionId = "session_engine_error"
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            throw IllegalStateException("engine blew up")
        }

        taskQueueManager.enqueueTask(
            AgentTask(sessionId = sessionId, prompt = "p", priority = TaskPriority.NORMAL),
        )
        advanceUntilIdle()

        val state = taskQueueManager.observeTaskState(sessionId).first()
        assertTrue("Expected Error, got $state", state is AgentOrchestratorState.Error)
        assertEquals("engine blew up", (state as AgentOrchestratorState.Error).message)
    }

    // region Persistent pipeline-run lifecycle

    /**
     * Enqueuing creates a persistent QUEUED run record keyed by the task id,
     * carrying the session, origin and the (yet unresolved) pipeline binding.
     */
    @Test
    fun `given task when enqueued then QUEUED run record is created with task id`() = testScope.runTest {
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        val task = AgentTask(sessionId = "session_run", prompt = "p", pipelineId = "bound-id")
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(PipelineGraph(id = "bound-id", name = "Bound")),
        )

        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.createRun(
                match<PipelineRun> {
                    it.id == task.id &&
                        it.sessionId == "session_run" &&
                        it.pipelineId == "bound-id" &&
                        it.origin == RunOrigin.CHAT &&
                        it.status == PipelineRunStatus.QUEUED &&
                        it.graphContentHash == null
                },
            )
        }
    }

    /**
     * A successfully processed task walks the full happy-path status chain:
     * QUEUED (createRun) → RUNNING (markRunning, with the resolved pipeline id
     * and its content hash) → COMPLETED (finishRun).
     */
    @Test
    fun `given successful run then record transitions QUEUED to RUNNING to COMPLETED`() = testScope.runTest {
        val pipeline = PipelineGraph(id = "seed-id", name = "Seed")
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        val task = AgentTask(sessionId = "session_happy", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify { pipelineRunRepository.markRunning(task.id, "seed-id", pipeline.contentHash()) }
        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.COMPLETED) }
        coVerify(exactly = 0) {
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.CANCELLED, any())
        }
        verify { graphExecutionEngine.invoke("session_happy", "p", any(), task.id) }
    }

    /**
     * An engine-emitted `Error` state settles the run record as FAILED with
     * the error message preserved.
     */
    @Test
    fun `given engine emits Error state then run record is FAILED with message`() = testScope.runTest {
        every {
            graphExecutionEngine.invoke(any(), any(), any(), any())
        } returns flowOf(AgentOrchestratorState.Error("node exploded"))

        val task = AgentTask(sessionId = "session_fail", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, "node exploded", null) }
    }

    @Test
    fun `given an engine error carrying a typed reason then it is settled with that reason`() = runTest {
        // The seam that carries the cause out of the engine. Verified with a
        // real reason, not only with null: settling a ceiling stop as an
        // untyped failure is exactly the misreading the vocabulary prevents,
        // and a test that only ever sees null would not notice.
        val reason = RunTerminationReason.StepCeiling(limit = 15, spent = 15)
        every { graphExecutionEngine.invoke(any(), any(), any(), any(), any()) } returns
            flowOf(AgentOrchestratorState.Error("over the cap", reason))

        val task = AgentTask(sessionId = "session_ceiling", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, "over the cap", reason)
        }
    }

    /**
     * A plain exception escaping the engine flow also settles the record as
     * FAILED — the same mapping the in-memory `Error` state gets.
     */
    @Test
    fun `given engine throws plain exception then run record is FAILED`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            throw IllegalStateException("engine blew up")
        }

        val task = AgentTask(sessionId = "session_throw", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, "engine blew up", null) }
    }

    @Test
    fun `given the engine throws with a credential in its message then neither the record nor the state carries it`() =
        testScope.runTest {
            // The run record's message is what the chat export and the trigger-journal
            // export share; the state is what the error banner shows.
            val leakedKey = "AIzaSyTESTKEY"
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
                emit(AgentOrchestratorState.Loading)
                throw IllegalStateException(
                    "Socket timeout [url=https://generativelanguage.googleapis.com/x?key=$leakedKey]",
                )
            }
            val recorded = slot<String>()
            coEvery { pipelineRunRepository.finishRun(any(), any(), capture(recorded), any()) } returns Unit

            val task = AgentTask(sessionId = "session_leak", prompt = "p")
            taskQueueManager.enqueueTask(task)
            advanceUntilIdle()

            val state = taskQueueManager.observeTaskState(task.sessionId).first() as AgentOrchestratorState.Error
            for (text in listOf(recorded.captured, state.message)) {
                assertFalse("key leaked: $text", text.contains(leakedKey))
                assertTrue("scrub marker missing: $text", text.contains("key=***"))
            }
        }

    /**
     * Pipeline-resolution failures (no binding, no default) never reach the
     * engine but still settle the run record as FAILED with the same message
     * surfaced to the UI.
     */
    @Test
    fun `given no pipeline resolvable then run record is FAILED before engine`() = testScope.runTest {
        every { pipelineRepository.getAllPipelines() } returns flowOf(
            listOf(PipelineGraph(id = "a-id", name = "A")),
        )
        every { settingsRepository.defaultPipelineId } returns flowOf(null)

        val task = AgentTask(sessionId = "session_unresolved", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.finishRun(
                task.id,
                PipelineRunStatus.FAILED,
                "No default pipeline configured. Set one in Settings or bind a pipeline to this chat.",
            )
        }
        coVerify(exactly = 0) { pipelineRunRepository.markRunning(any(), any(), any()) }
    }

    /**
     * User cancellation maps the run record to CANCELLED — never FAILED.
     * This extends the cancellation-is-not-an-error contract to the
     * persistence layer.
     */
    @Test
    fun `given running task when scope cancelled then run record is CANCELLED not FAILED`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }

        val task = AgentTask(sessionId = "session_run_cancel", prompt = "p")
        taskQueueManager.enqueueTask(task)
        // `runCurrent`, not `advanceUntilIdle`: the run is deliberately silent
        // here, and skipping virtual time forward would let the no-progress
        // valve settle it as FAILED before the cancellation under test lands.
        runCurrent()

        taskQueueManager.scope.cancel()
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.CANCELLED) }
        coVerify(exactly = 0) {
            pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, any())
        }
    }

    // endregion

    // region Checkpoint resume

    /** Interrupted-then-requeued run record matching the [graph] under resume. */
    private fun resumedRun(graph: PipelineGraph, runId: String, hadImage: Boolean = false): PipelineRun = PipelineRun(
        id = runId,
        sessionId = "session_resume",
        pipelineId = graph.id,
        origin = RunOrigin.CHAT,
        status = PipelineRunStatus.QUEUED,
        currentNodeId = null,
        startedAt = 0L,
        finishedAt = null,
        errorMessage = null,
        graphContentHash = graph.contentHash(),
        userPrompt = "original prompt",
        hadImage = hadImage,
    )

    /**
     * The resume branch must not repeat the fresh-task side effects: the user
     * message is already in the chat history, and the pipeline resolves by
     * the run's recorded id. The engine receives the checkpoint rebuilt from
     * the persisted trace — seq-ordered NodeIo prefix, latest memory
     * snapshot, and the next free seq.
     */
    @Test
    fun `given resume task then user message is not re-saved and engine gets the rebuilt checkpoint`() =
        testScope.runTest {
            val graph = PipelineGraph(id = "pipe-r", name = "Resume")
            val runId = "run-resume-1"
            coEvery { pipelineRunRepository.getRun(runId) } returns resumedRun(graph, runId)
            coEvery { pipelineRepository.getPipelineById("pipe-r") } returns graph
            val nodeIo = RunTraceRecord.NodeIo(
                runId = runId, sessionId = "session_resume", seq = 2L, timestamp = 0L,
                nodeId = "llm_1", nodeType = "LITE_RT", inputText = "in", outputText = "out",
                durationMs = 1L, tokenCount = null,
            )
            val memory = RunTraceRecord.MemorySnapshot(
                runId = runId,
                sessionId = "session_resume",
                seq = 3L,
                timestamp = 0L,
                entries = listOf(MemoryChunk(id = 1L, text = "fact", embedding = FloatArray(0), timestamp = 0L)),
            )
            val console = RunTraceRecord.ConsoleEntry(
                runId = runId,
                sessionId = "session_resume",
                seq = 5L,
                timestamp = 0L,
                type = ConsoleEventType.NodeExecution,
                message = "▶ LITE_RT",
            )
            coEvery { runTraceRepository.getTraceForRun(runId) } returns listOf(console, memory, nodeIo)
            val resumeSlot = slot<ResumeContext>()
            every {
                graphExecutionEngine.invoke(any(), any(), any(), any(), capture(resumeSlot))
            } returns flowOf(AgentOrchestratorState.Completed("ok"))

            taskQueueManager.enqueueTask(
                AgentTask(id = runId, sessionId = "session_resume", prompt = "original prompt", isResume = true),
            )
            advanceUntilIdle()

            coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
            coVerify { pipelineRunRepository.markRunning(runId, "pipe-r", graph.contentHash()) }
            assertEquals(listOf(nodeIo), resumeSlot.captured.records)
            assertEquals(listOf(1L), resumeSlot.captured.memorySnapshot?.map { it.id })
            assertEquals(6L, resumeSlot.captured.nextSeq)
            coVerify { pipelineRunRepository.finishRun(runId, PipelineRunStatus.COMPLETED) }
        }

    /**
     * A resumed run never re-delivers the image, but the *fact* that the run carried
     * one is persisted on the record (`hadImage`) and forwarded to the engine as
     * `runHadImage`, so an IF/router node executing live past the resume point can
     * still branch on image presence.
     */
    @Test
    fun `given resume task whose run had an image then engine gets runHadImage true`() = testScope.runTest {
        val graph = PipelineGraph(id = "pipe-r", name = "Resume")
        val runId = "run-resume-img"
        coEvery { pipelineRunRepository.getRun(runId) } returns resumedRun(graph, runId, hadImage = true)
        coEvery { pipelineRepository.getPipelineById("pipe-r") } returns graph
        coEvery { runTraceRepository.getTraceForRun(runId) } returns emptyList()

        val hadImageSlot = slot<Boolean>()
        every {
            // Named rather than positional, for the reason above.
            graphExecutionEngine.invoke(
                sessionId = any(),
                userPrompt = any(),
                graph = any(),
                runId = any(),
                resume = any(),
                depth = any(),
                budget = any(),
                stuckDetector = any(),
                imageInput = any(),
                imageDelivery = any(),
                runHadImage = capture(hadImageSlot),
            )
        } returns flowOf(AgentOrchestratorState.Completed("ok"))

        taskQueueManager.enqueueTask(
            AgentTask(id = runId, sessionId = "session_resume", prompt = "original prompt", isResume = true),
        )
        advanceUntilIdle()

        assertTrue("Resume must forward persisted image presence to the engine", hadImageSlot.captured)
    }

    /**
     * The graph hash is re-validated at worker pickup: an edit saved in the
     * validation→worker window settles the run back to INTERRUPTED with an
     * explicit message, and the engine is never invoked.
     */
    @Test
    fun `given resume task with edited graph then run settles back to INTERRUPTED before engine`() = testScope.runTest {
        val recordedGraph = PipelineGraph(id = "pipe-r", name = "Recorded")
        val editedGraph = PipelineGraph(
            id = "pipe-r",
            name = "Recorded",
            nodes = listOf(NodeModel("extra", NodeType.INPUT, 0f, 0f)),
        )
        val runId = "run-resume-2"
        coEvery { pipelineRunRepository.getRun(runId) } returns resumedRun(recordedGraph, runId)
        coEvery { pipelineRepository.getPipelineById("pipe-r") } returns editedGraph

        taskQueueManager.enqueueTask(
            AgentTask(id = runId, sessionId = "session_resume", prompt = "original prompt", isResume = true),
        )
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.finishRun(
                runId,
                PipelineRunStatus.INTERRUPTED,
                "Pipeline graph changed before resume could start. Restart the task instead.",
                RunTerminationReason.GraphChanged,
            )
        }
        verify(exactly = 0) { graphExecutionEngine.invoke(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }

        // And the cause reaches the surface, not only the record. Without it the
        // chat renders a refused resume as an ordinary failure with a Retry —
        // when the useful action is to run it again against the current graph.
        val state = taskQueueManager.observeTaskState("session_resume").first()
        assertTrue("Expected an Error state, got $state", state is AgentOrchestratorState.Error)
        assertEquals(RunTerminationReason.GraphChanged, (state as AgentOrchestratorState.Error).reason)
    }

    // endregion

    // region Parked (background-HITL) runs

    /**
     * A run that parks on its persistent waiting phase ends the engine flow
     * with `SuspendedInBackground` and no terminal state. The queue must NOT
     * stamp the record CANCELLED in its `finally` — the WAITING_* status is
     * the durable park marker — while the in-memory session state still
     * resets to Idle so the UI stops showing a live run.
     */
    @Test
    fun `given engine parks the run then record is neither CANCELLED nor FAILED and state is Idle`() =
        testScope.runTest {
            val sessionId = "session_parked"
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
                emit(AgentOrchestratorState.Loading)
                emit(
                    AgentOrchestratorState.SuspendedInBackground(PendingInteractionKind.APPROVAL),
                )
            }

            val task = AgentTask(sessionId = sessionId, prompt = "p")
            taskQueueManager.enqueueTask(task)
            advanceUntilIdle()

            coVerify(exactly = 0) { pipelineRunRepository.finishRun(task.id, any(), any()) }
            coVerify(exactly = 0) { pipelineRunRepository.finishRun(task.id, any()) }
            val state = taskQueueManager.observeTaskState(sessionId).first()
            assertTrue("Expected Idle after park, got $state", state is AgentOrchestratorState.Idle)
        }

    // endregion

    // region Stopping one run

    /**
     * The property the whole design turns on. The worker is a single serial
     * loop, so cancelling a run by cancelling the worker would not stop one
     * chat — it would freeze every chat in the app, permanently. The run dies;
     * the loop goes straight on to the next task.
     */
    @Test
    fun `given a running task when cancelled then it settles CANCELLED and the next task still runs`() =
        testScope.runTest {
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
                emit(AgentOrchestratorState.Loading)
                awaitCancellation()
            }
            val victim = AgentTask(sessionId = "session_cancel", prompt = "p")
            taskQueueManager.enqueueTask(victim)
            // `runCurrent`, not `advanceUntilIdle`: the run is deliberately
            // silent, and skipping virtual time forward lets the no-progress
            // valve settle it as FAILED before the cancel under test lands.
            runCurrent()

            taskQueueManager.cancelRun("session_cancel")
            runCurrent()

            coVerify { pipelineRunRepository.finishRun(victim.id, PipelineRunStatus.CANCELLED) }
            coVerify(exactly = 0) { pipelineRunRepository.finishRun(victim.id, PipelineRunStatus.FAILED, any()) }

            // The worker survived: a task enqueued afterwards is picked up.
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns
                flowOf(AgentOrchestratorState.Completed("ok"))
            val next = AgentTask(sessionId = "session_after_cancel", prompt = "p")
            taskQueueManager.enqueueTask(next)
            advanceUntilIdle()

            coVerify { pipelineRunRepository.finishRun(next.id, PipelineRunStatus.COMPLETED) }
        }

    @Test
    fun `given the worker is busy when another session enqueues then it reads Queued until picked up`() =
        testScope.runTest {
            // The session ahead holds the worker indefinitely, as a long trigger run would.
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
                emit(AgentOrchestratorState.Loading)
                awaitCancellation()
            }
            taskQueueManager.enqueueTask(AgentTask(sessionId = "session_ahead", prompt = "p"))
            runCurrent()

            val waiting = mutableListOf<AgentOrchestratorState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                taskQueueManager.observeTaskState("session_waiting").collect { waiting += it }
            }
            taskQueueManager.enqueueTask(AgentTask(sessionId = "session_waiting", prompt = "p"))
            runCurrent()

            // Before: the waiting session read Loading as if its own run were starting.
            assertEquals(AgentOrchestratorState.Queued, waiting.last())
            assertEquals(AgentOrchestratorState.Queued, taskQueueManager.activeSessionsState.value["session_waiting"])
            assertTrue(
                "the global state must keep describing the run that holds the worker",
                taskQueueManager.globalState.value !is AgentOrchestratorState.Queued,
            )

            // The run ahead ends; the worker picks the waiting task up and says so.
            taskQueueManager.cancelRun("session_ahead")
            runCurrent()

            assertEquals(AgentOrchestratorState.Loading, waiting.last())
            // The task monitor reads this snapshot: once running, the chat is no longer queued.
            assertEquals(AgentOrchestratorState.Loading, taskQueueManager.activeSessionsState.value["session_waiting"])
            taskQueueManager.cancelRun("session_waiting")
            runCurrent()
        }

    @Test
    fun `given an idle worker when a task is enqueued then it reads Loading, never Queued`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }
        val seen = mutableListOf<AgentOrchestratorState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            taskQueueManager.observeTaskState("session_alone").collect { seen += it }
        }

        taskQueueManager.enqueueTask(AgentTask(sessionId = "session_alone", prompt = "p"))
        runCurrent()

        assertTrue("an idle worker has nothing to wait behind: $seen", AgentOrchestratorState.Queued !in seen)
        taskQueueManager.cancelRun("session_alone")
        runCurrent()
    }

    @Test
    fun `given another session's run when cancelled then the running one is untouched`() = testScope.runTest {
        // Per-session rather than a scope teardown precisely so this holds: a
        // Stop in one chat must not end a background run in another.
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            emit(AgentOrchestratorState.Completed("ok"))
        }
        val task = AgentTask(sessionId = "session_a", prompt = "p")
        taskQueueManager.enqueueTask(task)
        runCurrent()

        taskQueueManager.cancelRun("session_b")
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.COMPLETED) }
        coVerify(exactly = 0) { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.CANCELLED) }
    }

    @Test
    fun `given a task still queued when its session is cancelled then it never runs and its record settles`() =
        testScope.runTest {
            // A task cancelled before it ever started still owns a QUEUED
            // record. Left behind it would sit there until the next launch
            // swept it as an orphan and blamed a dead process for something the
            // user did.
            every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
                emit(AgentOrchestratorState.Loading)
                awaitCancellation()
            }
            val running = AgentTask(sessionId = "session_q", prompt = "first")
            val queued = AgentTask(sessionId = "session_q", prompt = "second")
            taskQueueManager.enqueueTask(running)
            runCurrent()
            taskQueueManager.enqueueTask(queued)
            runCurrent()

            taskQueueManager.cancelRun("session_q")
            advanceUntilIdle()

            coVerify { pipelineRunRepository.finishRun(queued.id, PipelineRunStatus.CANCELLED) }
            verify(exactly = 0) {
                graphExecutionEngine.invoke("session_q", "second", any(), any())
            }
        }

    @Test
    fun `given a queued task is cancelled then the user's message is still in the thread`() = testScope.runTest {
        // Found on the device, not here. The message is written when a task
        // starts running, so a task cancelled while queued never wrote one —
        // and the composer had already cleared. The user's text simply vanished
        // and the line explaining the stop stood over no question at all.
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }
        taskQueueManager.enqueueTask(AgentTask(sessionId = "session_keep", prompt = "first"))
        runCurrent()
        val queued = AgentTask(sessionId = "session_keep", prompt = "the message I typed")
        taskQueueManager.enqueueTask(queued)
        runCurrent()

        taskQueueManager.cancelRun("session_keep")
        advanceUntilIdle()

        coVerify {
            chatRepository.saveMessage(
                match { it.sessionId == "session_keep" && it.content == "the message I typed" && it.role == Role.USER },
            )
        }
    }

    @Test
    fun `given a cancelled re-run then its message is not written a second time`() = testScope.runTest {
        // `persistUserMessage = false` marks a turn re-run after a failure: the
        // row survived the failed attempt, so writing it again would show the
        // same message twice. The cancel path has to honour that too.
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }
        taskQueueManager.enqueueTask(AgentTask(sessionId = "session_rerun", prompt = "first"))
        runCurrent()
        taskQueueManager.enqueueTask(
            AgentTask(sessionId = "session_rerun", prompt = "retried", persistUserMessage = false),
        )
        runCurrent()

        taskQueueManager.cancelRun("session_rerun")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.saveMessage(match { it.content == "retried" }) }
    }

    @Test
    fun `given nothing in flight when a session is cancelled then nothing is settled`() = testScope.runTest {
        // A Stop pressed as the last token lands must not resurrect a finished
        // run, nor write a second terminal record over it.
        taskQueueManager.cancelRun("session_idle")
        advanceUntilIdle()

        coVerify(exactly = 0) { pipelineRunRepository.finishRun(any(), PipelineRunStatus.CANCELLED) }
    }

    // endregion

    // region No-progress safety valve

    /**
     * The worker is a single serial loop, so a run that never emits again does
     * not merely lose its own chat — it stops every chat in the app. On the
     * device that showed up as a permanent, silent "Generating…" everywhere,
     * for 1.5 hours, after one MCP server accepted a call and never answered.
     *
     * The stalled run must settle as FAILED with an explanation, and — the
     * part that actually matters — the task queued behind it must still run.
     */
    @Test
    fun `given a run that stops emitting then it is failed and the next task still runs`() = testScope.runTest {
        val stalling = AgentTask(sessionId = "session_stalled", prompt = "p")
        val following = AgentTask(sessionId = "session_next", prompt = "p")
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        } andThen flowOf(AgentOrchestratorState.Completed("ok"))

        taskQueueManager.enqueueTask(stalling)
        advanceUntilIdle()
        taskQueueManager.enqueueTask(following)
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.finishRun(
                stalling.id,
                PipelineRunStatus.FAILED,
                TaskQueueManagerImpl.STALLED_MESSAGE,
                RunTerminationReason.RunStalled,
            )
        }
        coVerify { pipelineRunRepository.finishRun(following.id, PipelineRunStatus.COMPLETED) }
        // The chat must say what happened — the defect this replaces was a
        // permanent, wordless "Generating…".
        val state = taskQueueManager.observeTaskState("session_stalled").first()
        assertTrue("A stalled run must not end silently, got $state", state is AgentOrchestratorState.Error)
        val error = state as AgentOrchestratorState.Error
        assertEquals(TaskQueueManagerImpl.STALLED_MESSAGE, error.message)
        // The cause has to reach the SURFACE, not only the record above. The
        // record was already right while the emission dropped it, so the chat
        // put the raw message in the destructive tile under a Retry that would
        // stall all over again — the state this assertion exists to forbid.
        assertEquals(RunTerminationReason.RunStalled, error.reason)
    }

    /**
     * The counter-case that keeps the valve from becoming a wall-clock cap: a
     * run may take far longer than the window as long as it keeps emitting.
     * Generation streams a state per token, so this is the common case — a
     * total-duration limit would cut healthy long runs short.
     */
    @Test
    fun `given a slow run that keeps emitting then it is not failed`() = testScope.runTest {
        val window = taskQueueManager.silenceTimeoutMs
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            repeat(4) {
                delay(window - 1)
                emit(AgentOrchestratorState.Loading)
            }
            emit(AgentOrchestratorState.Completed("ok"))
        }

        val task = AgentTask(sessionId = "session_slow", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.COMPLETED) }
        coVerify(exactly = 0) { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, any()) }
    }

    /**
     * A run showing an approval prompt is not stalled — it is waiting for a
     * person, visibly, and the gate bounds that wait itself. Killing it would
     * turn a correctly configured long wait (a CLARIFICATION node's reply
     * window is user-configurable) into "the task stopped responding".
     */
    @Test
    fun `given a run waiting on an approval gate then the window does not apply`() = testScope.runTest {
        val window = taskQueueManager.silenceTimeoutMs
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.WaitingForApproval("echo", "{}", ToolRisk.SENSITIVE, "req-1"))
            delay(window * 3)
            emit(AgentOrchestratorState.Completed("approved and done"))
        }

        val task = AgentTask(sessionId = "session_gate", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.COMPLETED) }
        coVerify(exactly = 0) { pipelineRunRepository.finishRun(task.id, PipelineRunStatus.FAILED, any()) }
    }

    /**
     * The other half of that exemption: it lasts exactly until the next
     * emission. The gate emits `ExecutingTool` before running an approved tool,
     * so the hung call that follows an approval — the shape of the defect this
     * valve exists for — is guarded again.
     */
    @Test
    fun `given silence after an approved tool starts then the run is still failed`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.WaitingForApproval("echo", "{}", ToolRisk.SENSITIVE, "req-1"))
            emit(AgentOrchestratorState.ExecutingTool("echo", "{}"))
            awaitCancellation()
        }

        val task = AgentTask(sessionId = "session_gate_then_hang", prompt = "p")
        taskQueueManager.enqueueTask(task)
        advanceUntilIdle()

        coVerify {
            pipelineRunRepository.finishRun(
                task.id,
                PipelineRunStatus.FAILED,
                TaskQueueManagerImpl.STALLED_MESSAGE,
                RunTerminationReason.RunStalled,
            )
        }
    }

    // endregion

    // region Active-sessions snapshot
    //
    // The task monitor and the More tab read `activeSessionsState`. It used to be rebuilt
    // only at enqueue, so every status after that — a stage, a settle, a Stop — never
    // reached them: a finished chat read as running until a task arrived anywhere.

    private fun snapshotOf(sessionId: String): AgentOrchestratorState? =
        taskQueueManager.activeSessionsState.value[sessionId]

    @Test
    fun `given a run completes then the snapshot reads Completed`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns
            flowOf(AgentOrchestratorState.Completed("ok"))

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_done", prompt = "p"))
        advanceUntilIdle()

        // Before: Loading, the value captured at enqueue, for as long as nothing else was enqueued.
        assertEquals(AgentOrchestratorState.Completed("ok"), snapshotOf("s_done"))
    }

    @Test
    fun `given the engine fails then the snapshot reads Error`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            throw IllegalStateException("engine blew up")
        }

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_failed", prompt = "p"))
        advanceUntilIdle()

        assertEquals(AgentOrchestratorState.Error("engine blew up"), snapshotOf("s_failed"))
    }

    @Test
    fun `given a running task is stopped then the snapshot reads Idle`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }
        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_stopped", prompt = "p"))
        runCurrent()
        assertEquals(AgentOrchestratorState.Loading, snapshotOf("s_stopped"))

        taskQueueManager.cancelRun("s_stopped")
        runCurrent()

        assertEquals(AgentOrchestratorState.Idle, snapshotOf("s_stopped"))
    }

    @Test
    fun `given a queued task is stopped then the snapshot reads Idle`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Loading)
            awaitCancellation()
        }
        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_ahead", prompt = "p"))
        runCurrent()
        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_waiting", prompt = "p"))
        runCurrent()
        assertEquals(AgentOrchestratorState.Queued, snapshotOf("s_waiting"))

        taskQueueManager.cancelRun("s_waiting")
        runCurrent()

        assertEquals(AgentOrchestratorState.Idle, snapshotOf("s_waiting"))
        assertEquals(AgentOrchestratorState.Loading, snapshotOf("s_ahead"))
        taskQueueManager.cancelRun("s_ahead")
        runCurrent()
    }

    @Test
    fun `given a run reaches a stage then telemetry after it does not replace the stage`() = testScope.runTest {
        val stage = AgentOrchestratorState.PipelineStage(AgentOrchestratorState.PipelineStepInfo(1, 3, "Router"))
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(stage)
            emit(AgentOrchestratorState.ConsoleLog(events = emptyList()))
            awaitCancellation()
        }

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_stage", prompt = "p"))
        runCurrent()

        // The task monitor names the stage from this value; a console line is not a status.
        assertEquals(stage, snapshotOf("s_stage"))
        taskQueueManager.cancelRun("s_stage")
        runCurrent()
    }

    @Test
    fun `given a generation streams tokens then the snapshot changes once, not per token`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns flow {
            emit(AgentOrchestratorState.Thinking("a"))
            emit(AgentOrchestratorState.Thinking("ab"))
            emit(AgentOrchestratorState.Thinking("abc"))
            awaitCancellation()
        }
        val thinkingSnapshots = mutableListOf<AgentOrchestratorState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            taskQueueManager.activeSessionsState.collect { snapshot ->
                snapshot["s_stream"]?.takeIf { it is AgentOrchestratorState.Thinking }?.let { thinkingSnapshots += it }
            }
        }

        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_stream", prompt = "p"))
        runCurrent()

        // Every consumer recomposes on a new snapshot; a long answer must not cost one per token.
        assertEquals(listOf(AgentOrchestratorState.Thinking("")), thinkingSnapshots)
        taskQueueManager.cancelRun("s_stream")
        runCurrent()
    }

    @Test
    fun `given a settled session is evicted then the snapshot forgets it`() = testScope.runTest {
        every { graphExecutionEngine.invoke(any(), any(), any(), any()) } returns
            flowOf(AgentOrchestratorState.Completed("ok"))
        taskQueueManager.enqueueTask(AgentTask(sessionId = "s_oldest", prompt = "p"))
        advanceUntilIdle()
        assertNotNull(snapshotOf("s_oldest"))

        repeat(TaskQueueManagerImpl.MAX_SESSION_STATES) { i -> taskQueueManager.observeTaskState("s_new_$i") }

        assertTrue("the queue evicted it", !taskQueueManager.sessionStates.containsKey("s_oldest"))
        assertTrue("the lists must not keep a session the queue forgot", snapshotOf("s_oldest") == null)
    }

    // endregion
}
