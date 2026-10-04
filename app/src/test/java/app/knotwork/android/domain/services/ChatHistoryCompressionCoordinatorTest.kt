package app.knotwork.android.domain.services

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.CompressChatHistoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatHistoryCompressionCoordinator].
 *
 * The coordinator's scope is replaced with the test scope so the 30 s debounce
 * resolves against the test scheduler's virtual clock. Mirrors
 * [MemoryAutoExtractionCoordinatorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryCompressionCoordinatorTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var compressChatHistoryUseCase: CompressChatHistoryUseCase
    private lateinit var taskQueueManager: TaskQueueManager
    private lateinit var globalState: MutableStateFlow<AgentOrchestratorState>
    private lateinit var coordinator: ChatHistoryCompressionCoordinator

    private val sessionId = "session-1"

    @Before
    fun setup() {
        settingsRepository = mockk()
        compressChatHistoryUseCase = mockk()
        taskQueueManager = mockk()
        globalState = MutableStateFlow(AgentOrchestratorState.Idle)

        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(true)
        every { taskQueueManager.globalState } returns globalState
        coEvery { compressChatHistoryUseCase.invoke(any(), any()) } returns
            CompressChatHistoryUseCase.CompressionOutcome.SKIPPED

        coordinator = ChatHistoryCompressionCoordinator(
            memorySettings = settingsRepository,
            compressChatHistoryUseCase = compressChatHistoryUseCase,
            taskQueueManager = taskQueueManager,
        )
    }

    @Test
    fun `given enabled when completed then compresses after the debounce window`() = runTest {
        coordinator.scope = this

        coordinator.onPipelineCompleted(sessionId)
        advanceTimeBy(10_000)
        coVerify(exactly = 0) { compressChatHistoryUseCase.invoke(any(), any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { compressChatHistoryUseCase.invoke(sessionId, any()) }
    }

    @Test
    fun `given disabled when completed then never compresses`() = runTest {
        every { settingsRepository.chatHistoryCompressionEnabled } returns flowOf(false)
        coordinator.scope = this

        coordinator.onPipelineCompleted(sessionId)
        advanceUntilIdle()

        coVerify(exactly = 0) { compressChatHistoryUseCase.invoke(any(), any()) }
    }

    @Test
    fun `given rapid completions when within debounce then coalesces into a single compression`() = runTest {
        coordinator.scope = this

        coordinator.onPipelineCompleted(sessionId)
        advanceTimeBy(5_000)
        coordinator.onPipelineCompleted(sessionId)
        advanceTimeBy(5_000)
        coordinator.onPipelineCompleted(sessionId)
        advanceUntilIdle()

        coVerify(exactly = 1) { compressChatHistoryUseCase.invoke(sessionId, any()) }
    }

    @Test
    fun `given blank session id when completed then no-op`() = runTest {
        coordinator.scope = this

        coordinator.onPipelineCompleted("")
        advanceUntilIdle()

        coVerify(exactly = 0) { compressChatHistoryUseCase.invoke(any(), any()) }
    }

    @Test
    fun `given agent busy when debounce elapses then defers compression until idle`() = runTest {
        coordinator.scope = this
        globalState.value = AgentOrchestratorState.Thinking("…")

        coordinator.onPipelineCompleted(sessionId)
        advanceTimeBy(31_000)
        coVerify(exactly = 0) { compressChatHistoryUseCase.invoke(any(), any()) }

        globalState.value = AgentOrchestratorState.Completed("done")
        advanceUntilIdle()
        coVerify(exactly = 1) { compressChatHistoryUseCase.invoke(sessionId, any()) }
    }
}
