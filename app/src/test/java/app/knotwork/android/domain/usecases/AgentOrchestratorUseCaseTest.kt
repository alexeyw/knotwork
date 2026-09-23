package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.TaskPriority
import app.knotwork.android.domain.models.ToolRisk
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentOrchestratorUseCaseTest {

    private lateinit var taskQueueManager: TaskQueueManager
    private lateinit var useCase: AgentOrchestratorUseCase

    private val sessionId = "test-session"

    @Before
    fun setup() {
        taskQueueManager = mockk(relaxed = true)
        useCase = AgentOrchestratorUseCase(taskQueueManager)
    }

    @Test
    fun `invoke enqueues task and returns state flow`() = runTest {
        val userPrompt = "Hello"

        every { taskQueueManager.observeTaskState(sessionId) } returns flowOf(
            AgentOrchestratorState.Loading,
            AgentOrchestratorState.Completed("Done"),
        )

        val states = useCase(sessionId, userPrompt).toList()

        verify { taskQueueManager.enqueueTask(match { it.sessionId == sessionId && it.prompt == userPrompt }) }

        assertTrue(states[0] is AgentOrchestratorState.Loading)
        assertTrue(states[1] is AgentOrchestratorState.Completed)
    }

    @Test
    fun `observe subscribes to the session state without enqueueing a task`() = runTest {
        every { taskQueueManager.observeTaskState(sessionId) } returns flowOf(
            AgentOrchestratorState.Thinking("working"),
        )

        val states = useCase.observe(sessionId).toList()

        assertTrue(states.single() is AgentOrchestratorState.Thinking)
        verify(exactly = 0) { taskQueueManager.enqueueTask(any()) }
    }

    @Test
    fun `pendingApprovalFor delegates to TaskQueueManager`() {
        val pending = AgentOrchestratorState.WaitingForApproval(
            toolName = "fs.delete_file",
            arguments = "{}",
            risk = ToolRisk.SENSITIVE,
            requestId = "req-1",
        )
        every { taskQueueManager.pendingApproval(sessionId) } returns pending

        assertTrue(useCase.pendingApprovalFor(sessionId) === pending)
        verify { taskQueueManager.pendingApproval(sessionId) }
    }

    /**
     * The use case must surface the per-chat `pipelineId` on the
     * enqueued [AgentTask] so the orchestrator runs the chat-bound pipeline,
     * not the global default.
     */
    @Test
    fun `invoke captures pipelineId on the enqueued task`() = runTest {
        every { taskQueueManager.observeTaskState(sessionId) } returns flowOf(
            AgentOrchestratorState.Completed("done"),
        )

        useCase(sessionId, "hi", pipelineId = "pipeline-42").toList()

        verify {
            taskQueueManager.enqueueTask(
                match { it.sessionId == sessionId && it.prompt == "hi" && it.pipelineId == "pipeline-42" },
            )
        }
    }

    @Test
    fun `invoke without pipelineId enqueues task with null pipelineId`() = runTest {
        every { taskQueueManager.observeTaskState(sessionId) } returns flowOf(
            AgentOrchestratorState.Completed("done"),
        )

        useCase(sessionId, "hi").toList()

        verify { taskQueueManager.enqueueTask(match { it.pipelineId == null }) }
    }

    @Test
    fun `enqueueScheduled enqueues a SCHEDULER-origin NORMAL-priority task and returns its id`() {
        val enqueued = slot<AgentTask>()
        every { taskQueueManager.enqueueTask(capture(enqueued)) } returns Unit

        val runId = useCase.enqueueScheduled(sessionId, "morning summary")

        assertEquals(enqueued.captured.id, runId)
        assertEquals(sessionId, enqueued.captured.sessionId)
        assertEquals("morning summary", enqueued.captured.prompt)
        assertEquals(RunOrigin.SCHEDULER, enqueued.captured.origin)
        assertEquals(TaskPriority.NORMAL, enqueued.captured.priority)
    }

    @Test
    fun `enqueueScheduled never subscribes to the replaying session state flow`() {
        every { taskQueueManager.enqueueTask(any()) } returns Unit

        useCase.enqueueScheduled(sessionId, "morning summary")

        // Background callers track completion via the persistent run record;
        // touching the replaying flow here would reintroduce the stale-state race.
        verify(exactly = 0) { taskQueueManager.observeTaskState(any()) }
    }

    @Test
    fun `enqueueScheduled honours a pre-minted run id verbatim as the task id`() {
        val enqueued = slot<AgentTask>()
        every { taskQueueManager.enqueueTask(capture(enqueued)) } returns Unit

        // A trigger fire pre-mints the id it already wrote onto its journal row,
        // so the run and the journal entry must share identity end-to-end.
        val runId = useCase.enqueueScheduled(sessionId, "evening journal", runId = "trigger-run-1")

        assertEquals("trigger-run-1", runId)
        assertEquals("trigger-run-1", enqueued.captured.id)
    }

    @Test
    fun `enqueueScheduled mints a fresh id when none is supplied`() {
        val enqueued = slot<AgentTask>()
        every { taskQueueManager.enqueueTask(capture(enqueued)) } returns Unit

        val runId = useCase.enqueueScheduled(sessionId, "morning summary")

        // The default path (every non-trigger caller) still gets a fresh id.
        assertTrue(runId.isNotBlank())
        assertEquals(enqueued.captured.id, runId)
    }
}
