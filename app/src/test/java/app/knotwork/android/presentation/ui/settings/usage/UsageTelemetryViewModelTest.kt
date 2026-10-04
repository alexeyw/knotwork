package app.knotwork.android.presentation.ui.settings.usage

import app.knotwork.android.domain.models.OnboardingJourney
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.PipelineRunTally
import app.knotwork.android.domain.models.UsageTelemetrySummary
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.usecases.BuildUsageTelemetryExportUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies the [UsageTelemetryViewModel] state aggregation, opt-in, export and reset wiring. */
class UsageTelemetryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var usageTelemetry: UsageTelemetryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var viewModel: UsageTelemetryViewModel

    private val summary = UsageTelemetrySummary(
        runsByPipeline = listOf(PipelineRunTally("pipe-1", 3)),
        runsByOutcome = mapOf(PipelineRunStatus.COMPLETED to 3),
        triggerFiresByKind = mapOf("CHARGING" to 2),
        activeDays = 1,
        firstActiveDay = "2026-06-25",
        lastActiveDay = "2026-06-25",
        onboarding = OnboardingJourney.EMPTY,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        usageTelemetry = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        pipelineRepository = mockk()
        every { usageTelemetry.summary } returns flowOf(summary)
        every { settingsRepository.usageTelemetryEnabled } returns flowOf(true)
        every { pipelineRepository.observePipelineNames() } returns flowOf(mapOf("pipe-1" to "Daily digest"))
        viewModel = UsageTelemetryViewModel(
            usageTelemetry = usageTelemetry,
            privacySettings = settingsRepository,
            pipelineRepository = pipelineRepository,
            buildExport = BuildUsageTelemetryExportUseCase(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given sources when observed then uiState combines summary, opt-in and pipeline names`() = runTest {
        val state = viewModel.uiState.first { !it.loading }

        assertTrue(state.recordingEnabled)
        assertEquals(3, state.summary.totalRuns)
        assertEquals("Daily digest", state.pipelineNames["pipe-1"])
    }

    @Test
    fun `given a toggle when set then the opt-in is persisted`() = runTest {
        viewModel.setRecordingEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepository.setUsageTelemetryEnabled(false) }
    }

    @Test
    fun `given reset when invoked then the repository clears the statistics`() = runTest {
        viewModel.reset()
        advanceUntilIdle()

        coVerify(exactly = 1) { usageTelemetry.reset() }
    }

    @Test
    fun `given share when invoked then a populated text export is emitted`() = runTest {
        // Keep a subscriber alive so the stateIn value stays hot, then await it.
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.uiState.first { !it.loading }

        // Capture the one-shot event with a subscriber registered before the emit.
        val event = async { viewModel.events.first() }
        runCurrent()
        viewModel.shareAsText()

        val text = (event.await() as UsageTelemetryEvent.ShareText).text
        assertTrue(text.contains("Daily digest: 3"))
        assertTrue(text.contains("stored only on this device"))
    }
}
