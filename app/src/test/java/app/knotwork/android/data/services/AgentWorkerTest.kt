package app.knotwork.android.data.services

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for the `@HiltWorker`-annotated [AgentWorker].
 *
 * Hilt's assisted-inject machinery only fires inside an actual application
 * runtime, so the test instantiates the worker through a manual [WorkerFactory]
 * that hands the mocked collaborators to the worker's constructor.
 * [TestListenableWorkerBuilder] then drives the worker through its `doWork()`
 * lifecycle (including the `setForeground` / `setProgress` plumbing) with the
 * same semantics WorkManager uses in production.
 */
@RunWith(RobolectricTestRunner::class)
class AgentWorkerTest {

    private lateinit var context: Context
    private lateinit var useCase: AgentOrchestratorUseCase
    private lateinit var chatRepository: ChatRepository
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var scheduledTaskNotifier: ScheduledTaskNotifier
    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var prompts: MutableMap<String, String>
    private lateinit var backgroundPrompts: BackgroundPromptRepository
    private lateinit var taskScheduler: WorkManagerTaskScheduler
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Robolectric classes share a classloader — a service started by another
        // test class would otherwise leak its process-wide flag into this one.
        AgentForegroundService.isRunning = false
        useCase = mockk()
        chatRepository = mockk()
        pipelineRunRepository = mockk()
        scheduledTaskNotifier = mockk()
        llmEngine = mockk()

        coJustRun { scheduledTaskNotifier.notifyCompleted(any(), any()) }
        coJustRun { scheduledTaskNotifier.notifyFailed(any(), any()) }
        every { pipelineRunRepository.observeActiveRunSessionIds() } returns flowOf(emptySet())
        every { llmEngine.isInitialized } returns false
        prompts = mutableMapOf()
        backgroundPrompts = mockk()
        coEvery { backgroundPrompts.get(any()) } answers { prompts[firstArg<String>()] }
        coEvery { backgroundPrompts.delete(any()) } answers { prompts.remove(firstArg<String>()) }
        taskScheduler = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
    }

    private fun workerFactory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = AgentWorker(
            appContext,
            workerParameters,
            useCase,
            chatRepository,
            pipelineRunRepository,
            scheduledTaskNotifier,
            llmEngine,
            backgroundPrompts,
            taskScheduler,
            workManager,
        )
    }

    private fun buildWorker(input: Data = Data.EMPTY, tags: List<String> = emptyList()): AgentWorker =
        TestListenableWorkerBuilder<AgentWorker>(context)
            .setInputData(input)
            .setTags(tags)
            .setWorkerFactory(workerFactory())
            .build()

    /**
     * Input data as the scheduler writes it now: the prompt is stored under
     * [PROMPT_ID] and the request carries only the id.
     */
    private fun inputData(prompt: String? = "hello", sessionId: String? = SESSION_ID, reused: Boolean = false): Data {
        val builder = Data.Builder()
        if (prompt != null) {
            prompts[PROMPT_ID] = prompt
            builder.putString(AgentWorker.KEY_PROMPT_ID, PROMPT_ID)
            builder.putBoolean(AgentWorker.KEY_PROMPT_REUSED, reused)
        }
        if (sessionId != null) builder.putString(AgentWorker.KEY_SESSION_ID, sessionId)
        return builder.build()
    }

    /** Input data as a release before the prompt left the runtime wrote it: the prompt itself. */
    private fun legacyInputData(prompt: String, sessionId: String? = SESSION_ID): Data {
        val builder = Data.Builder().putString(AgentWorker.KEY_PROMPT, prompt)
        if (sessionId != null) builder.putString(AgentWorker.KEY_SESSION_ID, sessionId)
        return builder.build()
    }

    private fun run(
        status: PipelineRunStatus,
        sessionId: String = SESSION_ID,
        errorMessage: String? = null,
    ): PipelineRun = PipelineRun(
        id = RUN_ID,
        sessionId = sessionId,
        pipelineId = "pipeline-1",
        origin = RunOrigin.SCHEDULER,
        status = status,
        currentNodeId = "node-output",
        startedAt = 1L,
        finishedAt = 2L,
        errorMessage = errorMessage,
        graphContentHash = "hash",
    )

    /** Wires the standard happy-path collaborators around the given terminal [terminalRun]. */
    private fun stubRunLifecycle(terminalRun: PipelineRun, finalAnswer: String? = "Final answer.") {
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true
        every { useCase.enqueueScheduled(any(), any()) } returns RUN_ID
        every { pipelineRunRepository.observeRunsForSession(terminalRun.sessionId) } returns
            flowOf(listOf(terminalRun))
        val messages = buildList {
            add(ChatMessage(sessionId = terminalRun.sessionId, role = Role.USER, content = "hello", timestamp = 1L))
            if (finalAnswer != null) {
                add(
                    ChatMessage(
                        sessionId = terminalRun.sessionId,
                        role = Role.AGENT,
                        content = finalAnswer,
                        timestamp = 2L,
                    ),
                )
            }
        }
        every { chatRepository.getMessagesForSession(terminalRun.sessionId) } returns flowOf(messages)
    }

    @Test
    fun `given no prompt input when doWork runs then returns failure`() = runTest {
        val worker = buildWorker(Data.EMPTY)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `given blank prompt input when doWork runs then returns failure`() = runTest {
        val worker = buildWorker(inputData(prompt = "   "))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `given a prompt id whose prompt is no longer stored when doWork runs then fails and enqueues nothing`() =
        runTest {
            // Erased data, or a request cancelled and pruned: nothing left to run.
            val worker = buildWorker(Data.Builder().putString(AgentWorker.KEY_PROMPT_ID, "gone").build())

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            coVerify(exactly = 0) { useCase.enqueueScheduled(any(), any()) }
        }

    @Test
    fun `given a one-time run's stored prompt when the run is enqueued then the stored copy is dropped`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))

        buildWorker(inputData()).doWork()

        // The prompt now lives in the run's user message, in the chat.
        assertTrue(PROMPT_ID !in prompts)
    }

    @Test
    fun `given a recurring task's stored prompt when a run is enqueued then the stored copy stays for the next`() =
        runTest {
            stubRunLifecycle(run(PipelineRunStatus.COMPLETED))

            buildWorker(inputData(reused = true)).doWork()

            assertEquals("hello", prompts[PROMPT_ID])
        }

    @Test
    fun `given the enqueue fails when doWork runs then the stored prompt stays for the retry`() = runTest {
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true
        every { useCase.enqueueScheduled(any(), any()) } throws RuntimeException("boom")

        val result = buildWorker(inputData()).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals("hello", prompts[PROMPT_ID])
    }

    @Test
    fun `given a legacy recurring request when it runs then its prompt moves to the store and it retires`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))
        val worker = buildWorker(legacyInputData("hello"), tags = listOf("kst1|PERIODIC|6|$SESSION_ID|hello"))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { useCase.enqueueScheduled(SESSION_ID, "hello") }
        coVerify(exactly = 1) { taskScheduler.migrateLegacyPeriodic("hello", 6, SESSION_ID) }
        verify(exactly = 1) { workManager.cancelWorkById(worker.id) }
    }

    @Test
    fun `given a legacy one-time request when it runs then it runs as before and nothing is re-enqueued`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))

        buildWorker(legacyInputData("hello")).doWork()

        coVerify(exactly = 1) { useCase.enqueueScheduled(SESSION_ID, "hello") }
        coVerify(exactly = 0) { taskScheduler.migrateLegacyPeriodic(any(), any(), any()) }
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
    }

    @Test
    fun `given existing session when run completes then enqueues into it and notifies with preview`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED), finalAnswer = "First line.\nSecond line.")
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { useCase.enqueueScheduled(SESSION_ID, "hello") }
        coVerify(exactly = 1) { scheduledTaskNotifier.notifyCompleted(SESSION_ID, "First line.") }
        coVerify(exactly = 0) { chatRepository.saveSession(any()) }
    }

    @Test
    fun `given pipeline, origin and run id in input data when doWork runs then threads them to enqueueScheduled`() =
        runTest {
            stubRunLifecycle(run(PipelineRunStatus.COMPLETED))
            // Override the enqueue stub to accept the fully-specified call a trigger
            // fire produces (bound pipeline, TRIGGER origin, pre-minted run id).
            every { useCase.enqueueScheduled(any(), any(), any(), any(), any()) } returns RUN_ID
            val data = Data.Builder()
                .putString(AgentWorker.KEY_PROMPT, "hello")
                .putString(AgentWorker.KEY_SESSION_ID, SESSION_ID)
                .putString(AgentWorker.KEY_PIPELINE_ID, "pipe-9")
                .putString(AgentWorker.KEY_ORIGIN, RunOrigin.TRIGGER.name)
                .putString(AgentWorker.KEY_RUN_ID, RUN_ID)
                .build()

            val result = buildWorker(data).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) {
                useCase.enqueueScheduled(
                    sessionId = SESSION_ID,
                    userPrompt = "hello",
                    pipelineId = "pipe-9",
                    origin = RunOrigin.TRIGGER,
                    runId = RUN_ID,
                )
            }
        }

    @Test
    fun `given session was deleted when doWork runs then recreates it under the requested id and rebinds the run`() =
        runTest {
            coEvery { chatRepository.sessionExists(SESSION_ID) } returns false
            val savedSession = slot<ChatSession>()
            coEvery { chatRepository.saveSession(capture(savedSession)) } just Runs
            val enqueuedSession = slot<String>()
            every { useCase.enqueueScheduled(capture(enqueuedSession), any()) } returns RUN_ID
            every { pipelineRunRepository.observeRunsForSession(any()) } answers {
                flowOf(listOf(run(PipelineRunStatus.COMPLETED, sessionId = enqueuedSession.captured)))
            }
            every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
            val worker = buildWorker(inputData(prompt = "summarize my mail every morning"))

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            // The replacement keeps the requested id so an already-posted deep-link
            // or trigger binding that points at it stays valid.
            assertEquals(SESSION_ID, savedSession.captured.id)
            assertEquals(savedSession.captured.id, enqueuedSession.captured)
            assertTrue(savedSession.captured.name.startsWith("Scheduled: "))
            assertTrue(savedSession.captured.name.contains("summarize my mail every morning".take(40)))
        }

    @Test
    fun `given legacy work without session key when doWork runs then creates a fresh session`() = runTest {
        val savedSession = slot<ChatSession>()
        coEvery { chatRepository.saveSession(capture(savedSession)) } just Runs
        every { useCase.enqueueScheduled(any(), any()) } returns RUN_ID
        every { pipelineRunRepository.observeRunsForSession(any()) } answers {
            flowOf(listOf(run(PipelineRunStatus.COMPLETED, sessionId = savedSession.captured.id)))
        }
        every { chatRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
        val worker = buildWorker(inputData(sessionId = null))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { useCase.enqueueScheduled(savedSession.captured.id, "hello") }
    }

    @Test
    fun `given a later run already answered in the shared session then the preview is scoped to this run`() = runTest {
        // A trigger's recurring fires share one bound session, so a newer run's
        // final answer can already sit in the session when this run's completion
        // notification is built. The preview must pick THIS run's answer (by the
        // run's finishedAt = 2L), not the later one.
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true
        every { useCase.enqueueScheduled(any(), any()) } returns RUN_ID
        every { pipelineRunRepository.observeRunsForSession(SESSION_ID) } returns
            flowOf(listOf(run(PipelineRunStatus.COMPLETED)))
        every { chatRepository.getMessagesForSession(SESSION_ID) } returns flowOf(
            listOf(
                ChatMessage(sessionId = SESSION_ID, role = Role.AGENT, content = "this run", timestamp = 2L),
                ChatMessage(sessionId = SESSION_ID, role = Role.AGENT, content = "a later run", timestamp = 5L),
            ),
        )
        val worker = buildWorker(inputData())

        worker.doWork()

        coVerify(exactly = 1) { scheduledTaskNotifier.notifyCompleted(SESSION_ID, "this run") }
    }

    @Test
    fun `given run fails when doWork runs then notifies failure with recorded reason`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.FAILED, errorMessage = "Pipeline node exploded"))
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { scheduledTaskNotifier.notifyFailed(SESSION_ID, "Pipeline node exploded") }
        coVerify(exactly = 0) { scheduledTaskNotifier.notifyCompleted(any(), any()) }
    }

    @Test
    fun `given run is cancelled when doWork runs then posts no notification`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.CANCELLED))
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { scheduledTaskNotifier.notifyCompleted(any(), any()) }
        coVerify(exactly = 0) { scheduledTaskNotifier.notifyFailed(any(), any()) }
    }

    @Test
    fun `given completed run without final message when doWork runs then notifies with empty preview`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED), finalAnswer = null)
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { scheduledTaskNotifier.notifyCompleted(SESSION_ID, "") }
    }

    @Test
    fun `given the only answer in the chat was imported from a file then the preview does not show it`() = runTest {
        // A background run that produced no answer used to preview the latest final
        // AGENT row of the chat — which, in an imported chat, is a file's text shown in
        // a notification as if this run had said it.
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED), finalAnswer = null)
        every { chatRepository.getMessagesForSession(SESSION_ID) } returns flowOf(
            listOf(
                ChatMessage(
                    sessionId = SESSION_ID,
                    role = Role.AGENT,
                    content = "Text from someone else's chat file",
                    timestamp = 1L,
                    imported = true,
                ),
            ),
        )
        val worker = buildWorker(inputData())

        worker.doWork()

        coVerify(exactly = 1) { scheduledTaskNotifier.notifyCompleted(SESSION_ID, "") }
    }

    @Test
    fun `given enqueue throws when doWork runs then returns retry`() = runTest {
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true
        every { useCase.enqueueScheduled(any(), any()) } throws RuntimeException("boom")
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `given tracking throws after enqueue when doWork runs then still returns success`() = runTest {
        // Post-enqueue failures must never map to retry(): the run is already
        // executing in the singleton queue and the user message is persisted —
        // a worker retry would duplicate both.
        coEvery { chatRepository.sessionExists(SESSION_ID) } returns true
        every { useCase.enqueueScheduled(any(), any()) } returns RUN_ID
        every { pipelineRunRepository.observeRunsForSession(any()) } throws IllegalStateException("db gone")
        val worker = buildWorker(inputData())

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `given no foreground service and idle queue when run completes then unloads the engine`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))
        every { llmEngine.isInitialized } returns true
        coEvery { llmEngine.unload() } just Runs
        val worker = buildWorker(inputData())

        worker.doWork()

        coVerify(exactly = 1) { llmEngine.unload() }
    }

    @Test
    fun `given other sessions still active when run completes then keeps the engine loaded`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))
        every { pipelineRunRepository.observeActiveRunSessionIds() } returns flowOf(setOf("other-session"))
        every { llmEngine.isInitialized } returns true
        val worker = buildWorker(inputData())

        worker.doWork()

        coVerify(exactly = 0) { llmEngine.unload() }
    }

    @Test
    fun `given foreground service running when run completes then leaves unload to its idle manager`() = runTest {
        stubRunLifecycle(run(PipelineRunStatus.COMPLETED))
        AgentForegroundService.isRunning = true
        every { llmEngine.isInitialized } returns true
        val worker = buildWorker(inputData())

        worker.doWork()

        coVerify(exactly = 0) { llmEngine.unload() }
    }

    @Test
    fun `given input contains the canonical keys when fetched then matches the public contract`() {
        // The public keys are the wire-level contract between the worker and
        // `WorkManagerTaskScheduler` (the sole writer of the input data) — a
        // typo here breaks the integration silently.
        assertEquals("agent_prompt_id", AgentWorker.KEY_PROMPT_ID)
        assertEquals("agent_prompt_reused", AgentWorker.KEY_PROMPT_REUSED)
        // Read only from a request an earlier release queued.
        assertEquals("agent_prompt", AgentWorker.KEY_PROMPT)
        assertEquals("agent_session_id", AgentWorker.KEY_SESSION_ID)
        assertEquals("current_stage", AgentWorker.KEY_CURRENT_STAGE)
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val RUN_ID = "run-1"
        const val PROMPT_ID = "prompt-1"
    }
}
