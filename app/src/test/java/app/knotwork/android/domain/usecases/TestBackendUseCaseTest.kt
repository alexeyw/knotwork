package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the fixed-prompt backend probe, including the caller-supplied model
 * path added for the onboarding acceleration check — where the model that was
 * just installed is not necessarily the active one yet, so resolving through
 * "active model" would measure the wrong thing (or nothing at all).
 */
class TestBackendUseCaseTest {

    private lateinit var localModelRepository: LocalModelRepository
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: TestBackendUseCase

    @Before
    fun setUp() {
        localModelRepository = mockk(relaxed = true)
        coEvery { localModelRepository.getActiveModel() } returns null
        loadModelUseCase = mockk()
        coEvery { loadModelUseCase.invoke(any(), any(), any()) } returns Result.Success(Unit)
        llmInferenceEngine = mockk()
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flowOf("o", "k")
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.lastTestProbeResult } returns flowOf(null)
        useCase = TestBackendUseCase(
            localModelRepository = localModelRepository,
            loadModelUseCase = loadModelUseCase,
            llmInferenceEngine = llmInferenceEngine,
            generationSettings = settingsRepository,
        )
    }

    @Test
    fun `given an explicit path when probing then that model is loaded without consulting the active one`() = runTest {
        val result = useCase("/data/fresh.litertlm")

        assertTrue(result.success)
        assertEquals(2, result.tokensGenerated)
        coVerify(exactly = 1) { loadModelUseCase.invoke("/data/fresh.litertlm", any(), any()) }
        coVerify(exactly = 0) { localModelRepository.getActiveModel() }
    }

    @Test
    fun `given no path when probing then the active model is used`() = runTest {
        coEvery { localModelRepository.getActiveModel() } returns LocalModel(
            id = 1L,
            name = "active",
            path = "/data/active.litertlm",
            size = 0L,
            isActive = true,
        )

        val result = useCase()

        assertTrue(result.success)
        coVerify(exactly = 1) { loadModelUseCase.invoke("/data/active.litertlm", any(), any()) }
    }

    @Test
    fun `given neither a path nor an active model when probing then it fails without loading`() = runTest {
        val result = useCase()

        assertFalse(result.success)
        assertEquals("No active model selected.", result.errorMessage)
        coVerify(exactly = 0) { loadModelUseCase.invoke(any(), any(), any()) }
    }

    @Test
    fun `given a failing load when probing then the load message is reported and persisted`() = runTest {
        coEvery { loadModelUseCase.invoke(any(), any(), any()) } returns Result.Error(
            error = object : AppError.System {},
            message = "no dispatch library",
        )
        val persisted = slot<TestProbeResult>()
        coEvery { settingsRepository.setLastTestProbeResult(capture(persisted)) } returns Unit

        val result = useCase("/data/fresh.litertlm")

        assertFalse(result.success)
        assertEquals("no dispatch library", result.errorMessage)
        assertFalse(persisted.captured.success)
    }

    @Test
    fun `given a stream that breaks mid-generation when probing then partial tokens are reported`() = runTest {
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns flow {
            emit("o")
            throw IllegalStateException("Can not find OpenCL library")
        }

        val result = useCase("/data/fresh.litertlm")

        // The documented GPU failure mode surfaces here, not at init — so the
        // probe has to report it as a failure even though the load succeeded.
        assertFalse(result.success)
        assertEquals("Can not find OpenCL library", result.errorMessage)
        assertEquals(1, result.tokensGenerated)
    }
}
