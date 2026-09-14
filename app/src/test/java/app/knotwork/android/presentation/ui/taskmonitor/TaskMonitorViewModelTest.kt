package app.knotwork.android.presentation.ui.taskmonitor

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.domain.services.ScheduledTaskTag
import app.knotwork.android.domain.usecases.CancelScheduledTasksUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TaskMonitorViewModelTest {

    private lateinit var viewModel: TaskMonitorViewModel
    private val chatRepository: ChatRepository = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val taskQueueManager: TaskQueueManager = mockk(relaxed = true)
    private val cancelScheduledTasks: CancelScheduledTasksUseCase = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val sessions = listOf(
            ChatSession("session1", "First Session", 1000L),
            ChatSession("session2", "Second Session", 2000L),
        )

        val workInfo1 = mockk<WorkInfo>(relaxed = true) {
            every { id } returns UUID.randomUUID()
            every { tags } returns setOf("AgentWorker")
            every { state } returns WorkInfo.State.RUNNING
        }
        val workInfo2 = mockk<WorkInfo>(relaxed = true) {
            every { id } returns UUID.randomUUID()
            every { tags } returns setOf("OtherWorker")
            every { state } returns WorkInfo.State.SUCCEEDED
        }

        every { chatRepository.getSessionsFlow(includeArchived = true) } returns flowOf(sessions)
        every { workManager.getWorkInfosFlow(any()) } returns flowOf(listOf(workInfo1, workInfo2))

        val activeMap = mapOf(
            "session1" to AgentOrchestratorState.PipelineStage(
                AgentOrchestratorState.PipelineStepInfo(1, 3, "LITE_RT"),
            ),
            "session2" to AgentOrchestratorState.Idle,
        )
        every { taskQueueManager.activeSessionsState } returns kotlinx.coroutines.flow.MutableStateFlow(activeMap)

        viewModel = TaskMonitorViewModel(
            chatRepository,
            workManager,
            settingsRepository,
            taskQueueManager,
            cancelScheduledTasks,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state shows active tasks by default`() = runTest(testDispatcher) {
        val uiState = viewModel.uiState.first { !it.isLoading }

        assertEquals(TaskFilterType.ACTIVE, uiState.filter)
        assertEquals(2, uiState.tasks.size) // 1 active session + 1 running task

        val sessions = uiState.tasks.filter { it.type == TaskType.SESSION }
        assertEquals(1, sessions.size)
        assertTrue(sessions.all { it.status == TaskStatus.RUNNING })

        val backgroundTasks = uiState.tasks.filter { it.type == TaskType.BACKGROUND_WORK }
        assertEquals(1, backgroundTasks.size)
        assertTrue(backgroundTasks.all { it.status == TaskStatus.RUNNING })
    }

    @Test
    fun `filter ACTIVE returns only running tasks and sessions`() = runTest(testDispatcher) {
        viewModel.onFilterChanged(TaskFilterType.ACTIVE)
        val uiState = viewModel.uiState.first { it.filter == TaskFilterType.ACTIVE }

        assertEquals(2, uiState.tasks.size) // 1 active session + 1 running task
        assertTrue(uiState.tasks.all { it.status == TaskStatus.RUNNING })
    }

    @Test
    fun `given a chat queued behind another run when the default filter shows then it is listed as queued`() =
        runTest(testDispatcher) {
            every { taskQueueManager.activeSessionsState } returns kotlinx.coroutines.flow.MutableStateFlow(
                mapOf("session1" to AgentOrchestratorState.Queued, "session2" to AgentOrchestratorState.Idle),
            )
            val queuedViewModel = TaskMonitorViewModel(
                chatRepository,
                workManager,
                settingsRepository,
                taskQueueManager,
                cancelScheduledTasks,
            )

            val uiState = queuedViewModel.uiState.first { !it.isLoading }

            assertEquals(TaskFilterType.ACTIVE, uiState.filter)
            val sessions = uiState.tasks.filter { it.type == TaskType.SESSION }
            assertEquals(listOf("session1" to TaskStatus.QUEUED), sessions.map { it.id to it.status })
        }

    @Test
    fun `filter COMPLETED returns only completed tasks`() = runTest(testDispatcher) {
        viewModel.onFilterChanged(TaskFilterType.COMPLETED)
        val uiState = viewModel.uiState.first { it.filter == TaskFilterType.COMPLETED }

        assertEquals(2, uiState.tasks.size) // 1 idle session + 1 succeeded task
        assertTrue(uiState.tasks.all { it.status == TaskStatus.COMPLETED })
    }

    @Test
    fun `cancel task invokes work manager`() {
        val uuid = UUID.randomUUID()
        viewModel.onCancelTaskClicked(uuid.toString())

        verify { workManager.cancelWorkById(uuid) }
    }

    // --- Scheduled tasks ----------------------------------------------------

    /** A queued scheduled task, tagged the way the scheduler tags one. */
    private fun scheduledWorkInfo(
        state: WorkInfo.State = WorkInfo.State.ENQUEUED,
        kind: ScheduledTaskKind = ScheduledTaskKind.PERIODIC,
        intervalHours: Long = 6,
        sessionId: String? = "session1",
        prompt: String = "write the evening journal entry",
    ): WorkInfo = mockk(relaxed = true) {
        every { id } returns UUID.randomUUID()
        every { tags } returns setOf(
            ScheduledTaskTag.MARKER,
            ScheduledTaskTag.encode(kind, intervalHours, sessionId, prompt),
            WORKER_CLASS_TAG,
        )
        every { this@mockk.state } returns state
    }

    @Test
    fun `given a tagged scheduled task when observed then the row carries its label and bound chat`() =
        runTest(testDispatcher) {
            every { workManager.getWorkInfosFlow(any()) } returns flowOf(listOf(scheduledWorkInfo()))
            viewModel = TaskMonitorViewModel(
                chatRepository,
                workManager,
                settingsRepository,
                taskQueueManager,
                cancelScheduledTasks,
            )
            viewModel.onFilterChanged(TaskFilterType.BACKGROUND)

            val task = viewModel.uiState.first { !it.isLoading }.tasks
                .single { it.type == TaskType.BACKGROUND_WORK }

            // Without this the row reads "Background Task (AgentWorker)" — the
            // same for every task, so cancelling the right one is guesswork.
            assertEquals(ScheduledTaskKind.PERIODIC, task.scheduled?.kind)
            assertEquals(6L, task.scheduled?.intervalHours)
            assertEquals("write the evening journal entry", task.scheduled?.promptPreview)
            assertEquals("First Session", task.boundSessionName)
        }

    @Test
    fun `given unfinished and finished scheduled tasks when observed then only the unfinished ones are counted`() =
        runTest(testDispatcher) {
            every { workManager.getWorkInfosFlow(any()) } returns flowOf(
                listOf(
                    scheduledWorkInfo(state = WorkInfo.State.ENQUEUED),
                    scheduledWorkInfo(state = WorkInfo.State.RUNNING),
                    scheduledWorkInfo(state = WorkInfo.State.SUCCEEDED),
                    scheduledWorkInfo(state = WorkInfo.State.CANCELLED),
                ),
            )
            viewModel = TaskMonitorViewModel(
                chatRepository,
                workManager,
                settingsRepository,
                taskQueueManager,
                cancelScheduledTasks,
            )

            // Offering to stop tasks that already finished would be a lie.
            assertEquals(2, viewModel.uiState.first { !it.isLoading }.scheduledTaskCount)
        }

    @Test
    fun `given untagged background work when observed then it is not counted as scheduled`() = runTest(testDispatcher) {
        // The default fixture's work carries no scheduled marker: trigger and
        // tile runs must stay out of the bulk-cancel's blast radius.
        assertEquals(0, viewModel.uiState.first { !it.isLoading }.scheduledTaskCount)
    }

    @Test
    fun `given the confirmation is staged when confirmed then every scheduled task is cancelled`() =
        runTest(testDispatcher) {
            every { workManager.getWorkInfosFlow(any()) } returns flowOf(listOf(scheduledWorkInfo()))
            viewModel = TaskMonitorViewModel(
                chatRepository,
                workManager,
                settingsRepository,
                taskQueueManager,
                cancelScheduledTasks,
            )
            viewModel.onCancelAllScheduledClicked()
            assertTrue(viewModel.uiState.first { it.confirmingCancelAll }.confirmingCancelAll)

            viewModel.onCancelAllScheduledConfirmed()

            verify(exactly = 1) { cancelScheduledTasks() }
            // Await the cleared state rather than reading the cached one: the
            // combine has not recomputed yet on a StandardTestDispatcher.
            assertTrue(!viewModel.uiState.first { !it.confirmingCancelAll }.confirmingCancelAll)
        }

    @Test
    fun `given no scheduled task is left when the confirmation is open then it closes itself`() =
        runTest(testDispatcher) {
            // The default fixture has no scheduled work: a confirmation staged
            // against nothing must not ask about a premise that is gone.
            viewModel.onCancelAllScheduledClicked()

            assertTrue(!viewModel.uiState.first { !it.isLoading }.confirmingCancelAll)
        }

    @Test
    fun `given the confirmation is staged when dismissed then nothing is cancelled`() = runTest(testDispatcher) {
        every { workManager.getWorkInfosFlow(any()) } returns flowOf(listOf(scheduledWorkInfo()))
        viewModel = TaskMonitorViewModel(
            chatRepository,
            workManager,
            settingsRepository,
            taskQueueManager,
            cancelScheduledTasks,
        )
        viewModel.onCancelAllScheduledClicked()

        viewModel.onCancelAllScheduledDismissed()

        verify(exactly = 0) { cancelScheduledTasks() }
        assertTrue(!viewModel.uiState.first { !it.confirmingCancelAll }.confirmingCancelAll)
    }

    private companion object {
        /**
         * Stand-in for the tag the background runtime adds by itself (the worker
         * class name). Only its presence matters here: an unrelated tag must not
         * be mistaken for a label.
         */
        const val WORKER_CLASS_TAG = "AgentWorker"
    }
}
