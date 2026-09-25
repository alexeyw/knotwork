package app.knotwork.android.presentation.ui.splash

import android.content.Context
import app.knotwork.android.domain.models.InitFailureKind
import app.knotwork.android.domain.models.InitProgress
import app.knotwork.android.domain.models.InitStage
import app.knotwork.android.domain.usecases.AppInitializationUseCase
import app.knotwork.android.domain.usecases.ResetLockedDatabaseUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [SplashViewModel] folds [InitProgress] emissions into the
 * UI state correctly across the success path, the failed path, the retry
 * path, and the data-locked recovery flow (typed-confirm full reset).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appContext: Context
    private lateinit var appInitializationUseCase: AppInitializationUseCase
    private lateinit var resetLockedDatabaseUseCase: ResetLockedDatabaseUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = mockk()
        every { appContext.getString(any()) } returns RESET_KEYWORD
        appInitializationUseCase = mockk()
        resetLockedDatabaseUseCase = mockk()
        coEvery { resetLockedDatabaseUseCase() } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SplashViewModel =
        SplashViewModel(appContext, appInitializationUseCase, resetLockedDatabaseUseCase)

    private fun passphraseFailure(): InitProgress = InitProgress(
        stage = InitStage.Failed(
            cause = "Database passphrase unavailable",
            failedStage = InitStage.LoadingPipelines,
            failureKind = InitFailureKind.DB_PASSPHRASE_UNAVAILABLE,
        ),
        message = "Database passphrase unavailable",
        completedSteps = 0,
        totalSteps = 5,
    )

    @Test
    fun `given full successful run when collected then ends in done state`() = runTest {
        every { appInitializationUseCase() } returns flowOf(
            InitProgress(InitStage.Initializing, "Preparing…", 0, 5),
            InitProgress(InitStage.FindingModels, "Checking downloaded models…", 1, 5),
            InitProgress(InitStage.LoadingPipelines, "Reading pipelines…", 2, 5),
            InitProgress(InitStage.LoadingChats, "Reading chats…", 3, 5),
            InitProgress(InitStage.LoadingMemory, "Reading memory…", 4, 5),
            InitProgress(InitStage.Done, "Ready", 5, 5),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDone)
        assertEquals(1f, state.progressFraction, 0.001f)
        assertNull(state.errorMessage)
        assertEquals("Ready", state.message)
    }

    @Test
    fun `given mid-flight emission when collected then progressFraction reflects completed steps`() = runTest {
        every { appInitializationUseCase() } returns flowOf(
            InitProgress(InitStage.Initializing, "Preparing…", 0, 5),
            InitProgress(InitStage.LoadingPipelines, "Reading pipelines…", 2, 5),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDone)
        assertEquals(2f / 5f, state.progressFraction, 0.001f)
        assertEquals("Reading pipelines…", state.message)
        assertNull(state.errorMessage)
    }

    @Test
    fun `given Failed emission when collected then surfaces error message`() = runTest {
        every { appInitializationUseCase() } returns flowOf(
            InitProgress(InitStage.Initializing, "Preparing…", 0, 5),
            InitProgress(
                stage = InitStage.Failed("seed prompts failed", InitStage.Initializing),
                message = "seed prompts failed",
                completedSteps = 0,
                totalSteps = 5,
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDone)
        assertNotNull(state.errorMessage)
        assertEquals("seed prompts failed", state.errorMessage)
        assertFalse(state.isDataLocked)
    }

    @Test
    fun `given retry called after failure when invoked then re-runs the use case`() = runTest {
        // First run fails; second run succeeds.
        var invocationCount = 0
        every { appInitializationUseCase() } answers {
            invocationCount++
            if (invocationCount == 1) {
                flowOf(
                    InitProgress(
                        stage = InitStage.Failed("boom", InitStage.LoadingPipelines),
                        message = "boom",
                        completedSteps = 0,
                        totalSteps = 5,
                    ),
                )
            } else {
                flowOf(
                    InitProgress(InitStage.Done, "Ready", 5, 5),
                )
            }
        }

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDone)
        assertNull(state.errorMessage)
        assertEquals(2, invocationCount)
    }

    @Test
    fun `given retry called while idle when invoked then is no-op`() = runTest {
        // A flow that never terminates so the VM stays in flight.
        every { appInitializationUseCase() } returns MutableSharedFlow<InitProgress>()

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Retry while the run is in flight (errorMessage == null && !isDone)
        // must not start a second collection.
        viewModel.retry()
        advanceUntilIdle()

        // Use case still invoked exactly once (the implicit init call).
        io.mockk.verify(exactly = 1) { appInitializationUseCase() }
    }

    @Test
    fun `given passphrase failure when collected then state is data-locked`() = runTest {
        every { appInitializationUseCase() } returns flowOf(passphraseFailure())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDataLocked)
        assertNotNull(state.errorMessage)
        assertFalse(state.showResetDialog)
    }

    @Test
    fun `given data-locked state when reset requested and dismissed then dialog toggles`() = runTest {
        every { appInitializationUseCase() } returns flowOf(passphraseFailure())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestReset()
        assertTrue(viewModel.uiState.value.showResetDialog)

        viewModel.updateResetTypedInput("RES")
        assertEquals("RES", viewModel.uiState.value.resetTypedInput)

        viewModel.dismissResetDialog()
        assertFalse(viewModel.uiState.value.showResetDialog)
        assertEquals("", viewModel.uiState.value.resetTypedInput)
    }

    @Test
    fun `given non-matching typed input when confirmReset then wipe is not executed`() = runTest {
        every { appInitializationUseCase() } returns flowOf(passphraseFailure())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestReset()
        viewModel.updateResetTypedInput("nope")
        viewModel.confirmReset()
        advanceUntilIdle()

        coVerify(exactly = 0) { resetLockedDatabaseUseCase() }
        assertTrue(viewModel.uiState.value.isDataLocked)
    }

    @Test
    fun `given matching typed input when confirmReset then wipes and restarts initialization`() = runTest {
        var invocationCount = 0
        every { appInitializationUseCase() } answers {
            invocationCount++
            if (invocationCount == 1) {
                flowOf(passphraseFailure())
            } else {
                flowOf(InitProgress(InitStage.Done, "Ready", 5, 5))
            }
        }

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isDataLocked)

        viewModel.requestReset()
        viewModel.updateResetTypedInput(RESET_KEYWORD)
        viewModel.confirmReset()
        advanceUntilIdle()

        coVerify(exactly = 1) { resetLockedDatabaseUseCase() }
        val state = viewModel.uiState.value
        assertTrue(state.isDone)
        assertFalse(state.isDataLocked)
        assertEquals(2, invocationCount)
    }

    @Test
    fun `given keyword in different case when confirmReset then still wipes`() = runTest {
        var invocationCount = 0
        every { appInitializationUseCase() } answers {
            invocationCount++
            if (invocationCount == 1) {
                flowOf(passphraseFailure())
            } else {
                flowOf(InitProgress(InitStage.Done, "Ready", 5, 5))
            }
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestReset()
        viewModel.updateResetTypedInput(" reset ")
        viewModel.confirmReset()
        advanceUntilIdle()

        coVerify(exactly = 1) { resetLockedDatabaseUseCase() }
        assertTrue(viewModel.uiState.value.isDone)
    }

    @Test
    fun `given wipe in flight when retry tapped then is no-op until wipe restarts initialization`() = runTest {
        var invocationCount = 0
        every { appInitializationUseCase() } answers {
            invocationCount++
            if (invocationCount == 1) {
                flowOf(passphraseFailure())
            } else {
                flowOf(InitProgress(InitStage.Done, "Ready", 5, 5))
            }
        }
        // Hold the wipe open so retry() can race it.
        val wipeGate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { resetLockedDatabaseUseCase() } coAnswers { wipeGate.await() }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.requestReset()
        viewModel.updateResetTypedInput(RESET_KEYWORD)
        viewModel.confirmReset()
        testScheduler.runCurrent()

        // Wipe suspended; a racing Retry tap must not start a parallel init run.
        viewModel.retry()
        testScheduler.runCurrent()

        wipeGate.complete(true)
        advanceUntilIdle()

        // Exactly two runs: the implicit init + the post-wipe restart. No third from retry().
        assertEquals(2, invocationCount)
        assertTrue(viewModel.uiState.value.isDone)
    }

    @Test
    fun `given generic failure when confirmReset then is no-op`() = runTest {
        every { appInitializationUseCase() } returns flowOf(
            InitProgress(
                stage = InitStage.Failed("boom", InitStage.LoadingPipelines),
                message = "boom",
                completedSteps = 0,
                totalSteps = 5,
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateResetTypedInput(RESET_KEYWORD)
        viewModel.confirmReset()
        advanceUntilIdle()

        coVerify(exactly = 0) { resetLockedDatabaseUseCase() }
    }

    private companion object {
        const val RESET_KEYWORD = "RESET"
    }
}
