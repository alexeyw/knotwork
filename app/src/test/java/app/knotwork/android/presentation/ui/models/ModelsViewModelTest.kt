package app.knotwork.android.presentation.ui.models

import app.knotwork.android.data.network.AndroidModelDownloadManager
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelPerformanceSummary
import app.knotwork.android.domain.repositories.ActiveDownload
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.BenchmarkOutcome
import app.knotwork.android.domain.usecases.BenchmarkReport
import app.knotwork.android.domain.usecases.GetModelPerformanceUseCase
import app.knotwork.android.domain.usecases.RunBenchmarkUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ModelsViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    private val localModelRepository: LocalModelRepository = mockk(relaxed = true)
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val getModelPerformanceUseCase: GetModelPerformanceUseCase = mockk()
    private val runBenchmarkUseCase: RunBenchmarkUseCase = mockk()
    private val taskQueueManager: TaskQueueManager = mockk()

    private lateinit var viewModel: ModelsViewModel

    private val testDispatcher = StandardTestDispatcher()

    private fun createViewModel(): ModelsViewModel = ModelsViewModel(
        localModelRepository,
        downloadManager,
        settingsRepository,
        getModelPerformanceUseCase,
        runBenchmarkUseCase,
        taskQueueManager,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Default mocks for initial state
        every { localModelRepository.getAllModels() } returns flowOf(emptyList())
        every { getModelPerformanceUseCase(any()) } returns flowOf(null)
        every { taskQueueManager.globalState } returns MutableStateFlow(AgentOrchestratorState.Idle)
        every { downloadManager.observeActiveDownload() } returns flowOf(null)

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads downloaded models and active model`() = runTest {
        val models = listOf(
            LocalModel(id = 1, name = "Model 1", path = "/path", size = 100, isActive = false),
            LocalModel(id = 2, name = "Model 2", path = "/path", size = 100, isActive = true),
        )

        every { localModelRepository.getAllModels() } returns flowOf(models)

        // Re-initialize to pick up the new flow
        viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(models, state.downloadedModels)
        assertEquals(models[1], state.activeModel)
    }

    @Test
    fun `onCustomUrlChanged updates state`() {
        val newUrl = "http://example.com/model.bin"
        viewModel.onCustomUrlChanged(newUrl)

        assertEquals(newUrl, viewModel.uiState.value.customUrlInput)
        assertEquals(null, viewModel.uiState.value.downloadError)
    }

    @Test
    fun `given a Hugging Face link when submitted then it downloads under the name without the query`() = runTest {
        val url = "https://huggingface.co/org/repo/resolve/main/model.litertlm?download=true"
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf()
        val refusals = collectRefusals()
        viewModel.onCustomUrlChanged(url)

        viewModel.submitCustomUrl()
        advanceUntilIdle()

        verify(exactly = 1) { downloadManager.downloadModel(url, "model.litertlm", any()) }
        assertEquals(0, refusals.size)
    }

    @Test
    fun `given a link to another model format when submitted then nothing downloads and the screen is told`() =
        runTest {
            val refusals = collectRefusals()
            viewModel.onCustomUrlChanged("https://example.com/models/gemma.task")

            viewModel.submitCustomUrl()
            advanceUntilIdle()

            verify(exactly = 0) { downloadManager.downloadModel(any(), any(), any()) }
            assertEquals(false, viewModel.uiState.value.isDownloading)
            assertEquals(1, refusals.size)
        }

    @Test
    fun `given an empty field when submitted then nothing happens`() = runTest {
        val refusals = collectRefusals()
        viewModel.onCustomUrlChanged("   ")

        viewModel.submitCustomUrl()
        advanceUntilIdle()

        verify(exactly = 0) { downloadManager.downloadModel(any(), any(), any()) }
        assertEquals(0, refusals.size)
    }

    /** Records every refusal the ViewModel emits for the rest of the test. */
    private fun TestScope.collectRefusals(): List<Unit> {
        val refusals = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.customUrlRefusedEvents.collect { refusals += it }
        }
        return refusals
    }

    @Test
    fun `observeAuthToken sets initial auth token`() = runTest {
        every { settingsRepository.huggingFaceAuthToken } returns flowOf("test-token-123")
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("test-token-123", viewModel.uiState.value.authTokenInput)
    }

    @Test
    fun `setAudioSupport delegates to the repository`() = runTest {
        viewModel.setAudioSupport(modelId = 7L, enabled = true)
        advanceUntilIdle()

        coVerify { localModelRepository.setAudioSupport(7L, true) }
    }

    @Test
    fun `onAuthTokenChanged updates state and saves to repository`() = runTest {
        viewModel.onAuthTokenChanged("new-token-456")
        advanceUntilIdle()

        assertEquals("new-token-456", viewModel.uiState.value.authTokenInput)
        coVerify { settingsRepository.setHuggingFaceAuthToken("new-token-456") }
    }

    @Test
    fun `onAuthTokenChanged with blank string saves null to repository`() = runTest {
        viewModel.onAuthTokenChanged("   ")
        advanceUntilIdle()

        assertEquals("   ", viewModel.uiState.value.authTokenInput)
        coVerify { settingsRepository.setHuggingFaceAuthToken(null) }
    }

    @Test
    fun `startDownload updates state through download lifecycle`() = runTest {
        val url = "http://example.com/model.bin"
        val fileName = "model.bin"

        every { downloadManager.downloadModel(url, fileName) } returns flowOf(
            DownloadState.Pending,
            DownloadState.Downloading(50),
            DownloadState.Success("/local/path"),
        )

        viewModel.startDownload(url, fileName)

        // Assert initial downloading state
        var state = viewModel.uiState.value
        assertEquals(true, state.isDownloading)

        advanceUntilIdle()

        // Assert final state after success
        state = viewModel.uiState.value
        assertEquals(false, state.isDownloading)
        assertEquals(null, state.downloadProgress)
    }

    @Test
    fun `startDownload handles error state`() = runTest {
        val url = "http://example.com/model.bin"
        val fileName = "model.bin"
        val error = AndroidModelDownloadManager.DownloadError("Network failed")

        every { downloadManager.downloadModel(url, fileName) } returns flowOf(
            DownloadState.Error(error),
        )

        viewModel.startDownload(url, fileName)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isDownloading)
        assertEquals(error, state.downloadError)
    }

    @Test
    fun `given a download already running when the screen opens then it re-attaches`() = runTest {
        // The transfer outlives the ViewModel, so a screen that was not around
        // when it started still has to find it — otherwise the shade shows
        // progress while the screen looks idle.
        every { downloadManager.observeActiveDownload() } returns flowOf(
            ActiveDownload(fileName = "model.bin", state = DownloadState.Downloading(64)),
        )
        every { downloadManager.observeDownload("model.bin") } returns kotlinx.coroutines.flow.flow {
            emit(DownloadState.Downloading(64))
            emit(DownloadState.Downloading(70))
            kotlinx.coroutines.awaitCancellation()
        }

        val reopened = createViewModel()
        advanceUntilIdle()

        assertEquals("model.bin", reopened.uiState.value.activeDownloadFileName)
        assertEquals(70, reopened.uiState.value.downloadProgress)
        // Re-attaching must never enqueue: opening a screen is not a request to
        // download anything.
        verify(exactly = 0) { downloadManager.downloadModel(any(), any(), any()) }
    }

    @Test
    fun `cancelDownload stops the background work, not just the observation`() = runTest {
        every { downloadManager.downloadModel(any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            emit(DownloadState.Downloading(20))
            kotlinx.coroutines.awaitCancellation()
        }
        viewModel.startDownload("http://example.com/model.bin", "model.bin")
        advanceUntilIdle()

        viewModel.cancelDownload()
        advanceUntilIdle()

        // The transfer outlives this screen now — dropping the collection alone
        // would leave it running with nothing showing it.
        verify { downloadManager.cancelDownload("model.bin") }
        assertEquals(false, viewModel.uiState.value.isDownloading)
    }

    @Test
    fun `a download cancelled elsewhere releases the in-flight state`() = runTest {
        // The notification's Cancel action ends the stream with no terminal
        // state; the screen must not keep showing a download that is gone.
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf(
            DownloadState.Downloading(20),
        )

        viewModel.startDownload("http://example.com/model.bin", "model.bin")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isDownloading)
        assertEquals(null, state.downloadProgress)
        assertEquals(null, state.activeDownloadFileName)
    }

    @Test
    fun `setActiveModel calls repository`() = runTest {
        viewModel.setActiveModel(1L)
        advanceUntilIdle()
        coVerify { localModelRepository.setActiveModel(1L) }
    }

    @Test
    fun `clearError sets downloadError to null`() = runTest {
        val url = "http://example.com/model.bin"
        val fileName = "model.bin"
        val error = AndroidModelDownloadManager.DownloadError("Network failed")

        every { downloadManager.downloadModel(url, fileName) } returns flowOf(
            DownloadState.Error(error),
        )

        viewModel.startDownload(url, fileName)
        advanceUntilIdle()

        assertEquals(error, viewModel.uiState.value.downloadError)

        viewModel.clearError()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.downloadError)
    }

    @Test
    fun `engine busy global state surfaces as the engineBusy flag`() = runTest {
        every { taskQueueManager.globalState } returns MutableStateFlow(AgentOrchestratorState.Loading)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.engineBusy)
    }

    @Test
    fun `active model performance summary is observed for the active model`() = runTest {
        val models = listOf(
            LocalModel(id = 2, name = "Model 2", path = "/active", size = 100, isActive = true),
        )
        every { localModelRepository.getAllModels() } returns flowOf(models)
        every { getModelPerformanceUseCase("/active") } returns flowOf(
            ModelPerformanceSummary(
                sampleCount = 3,
                avgTtftMs = 420,
                avgDecodeTokensPerSec = 12f,
                peakNativeHeapBytes = 1,
            ),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.performanceSummary?.sampleCount)
    }

    @Test
    fun `onRunBenchmark drives the card to the result state on success`() = runTest {
        val report = BenchmarkReport("m", 100, 5f, 1_000, 2_048)
        coEvery { runBenchmarkUseCase(any()) } returns BenchmarkOutcome.Success(report)

        viewModel.onRunBenchmark()
        advanceUntilIdle()

        val state = viewModel.uiState.value.benchmark
        assertTrue(state is BenchmarkUiState.Result)
        assertEquals(report, (state as BenchmarkUiState.Result).report)
    }

    @Test
    fun `onRunBenchmark surfaces a failure and resets the card`() = runTest {
        coEvery { runBenchmarkUseCase(any()) } returns BenchmarkOutcome.Failed("boom")

        viewModel.onRunBenchmark()
        advanceUntilIdle()

        assertEquals(BenchmarkUiState.Idle, viewModel.uiState.value.benchmark)
    }

    @Test
    fun `onDismissBenchmark returns the card to idle`() = runTest {
        val report = BenchmarkReport("m", 100, 5f, 1_000, 2_048)
        coEvery { runBenchmarkUseCase(any()) } returns BenchmarkOutcome.Success(report)
        viewModel.onRunBenchmark()
        advanceUntilIdle()

        viewModel.onDismissBenchmark()

        assertEquals(BenchmarkUiState.Idle, viewModel.uiState.value.benchmark)
    }
}
