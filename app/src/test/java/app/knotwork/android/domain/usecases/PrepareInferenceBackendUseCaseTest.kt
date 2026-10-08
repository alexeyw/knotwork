package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.HardwareAccelerationProbe
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Covers the first-install backend decision: who may change it, what evidence
 * is required before the GPU is trusted, and how a wrong guess is undone.
 *
 * The failure paths carry the weight here — a device that ships OpenCL but
 * cannot actually run the model must end the flow on CPU with a working handle,
 * not on a backend that just proved itself broken.
 */
class PrepareInferenceBackendUseCaseTest {

    private val modelPath = "/data/model.litertlm"

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var accelerationProbe: HardwareAccelerationProbe
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var testBackendUseCase: TestBackendUseCase
    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var useCase: PrepareInferenceBackendUseCase

    @Before
    fun setUp() {
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.localModelBackendPreference } returns flowOf(null)
        accelerationProbe = mockk()
        loadModelUseCase = mockk()
        coEvery { loadModelUseCase.invoke(any(), any(), any()) } returns Result.Success(Unit)
        testBackendUseCase = mockk()
        llmInferenceEngine = mockk(relaxed = true)
        useCase = PrepareInferenceBackendUseCase(
            generationSettings = settingsRepository,
            accelerationProbe = accelerationProbe,
            loadModelUseCase = loadModelUseCase,
            testBackendUseCase = testBackendUseCase,
            llmInferenceEngine = llmInferenceEngine,
        )
    }

    @Test
    fun `given a stored preference when preparing then the choice is honoured and nothing is probed`() = runTest {
        every { settingsRepository.localModelBackendPreference } returns flowOf(LocalBackend.CPU.key)

        val outcome = useCase(modelPath)

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        // An explicit CPU choice must look nothing like "never chosen": no
        // probe, no write, no verification generation.
        verify(exactly = 0) { accelerationProbe.isGpuAvailable() }
        coVerify(exactly = 0) { settingsRepository.setLocalModelBackend(any()) }
        coVerify(exactly = 0) { testBackendUseCase.invoke(any()) }
        coVerify(exactly = 1) { loadModelUseCase.invoke(modelPath, any(), any()) }
    }

    @Test
    fun `given an NPU choice stored by an earlier version when preparing then it warms up on CPU`() = runTest {
        every { settingsRepository.localModelBackendPreference } returns flowOf(LocalBackend.NPU.key)

        val outcome = useCase(modelPath)

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        verify(exactly = 0) { accelerationProbe.isGpuAvailable() }
    }

    @Test
    fun `given an unreadable stored key when preparing then it is treated as an explicit choice`() = runTest {
        every { settingsRepository.localModelBackendPreference } returns flowOf("QPU")

        val outcome = useCase(modelPath)

        // A value we cannot parse is still a value the user (or a future build)
        // wrote — falling back to CPU is safe, silently re-probing is not.
        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        verify(exactly = 0) { accelerationProbe.isGpuAvailable() }
    }

    @Test
    fun `given no preference and no GPU when preparing then CPU is recorded and warmed`() = runTest {
        every { accelerationProbe.isGpuAvailable() } returns false
        var checkAnnounced = false

        val outcome = useCase(modelPath) { checkAnnounced = true }

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        // Recorded, so the probe never runs again on this device.
        coVerify(exactly = 1) { settingsRepository.setLocalModelBackend(LocalBackend.CPU.key) }
        coVerify(exactly = 0) { testBackendUseCase.invoke(any()) }
        coVerify(exactly = 1) { loadModelUseCase.invoke(modelPath, any(), any()) }
        // Nothing was checked, so nothing is announced to the user.
        assertEquals(false, checkAnnounced)
    }

    @Test
    fun `given a GPU that generates when preparing then GPU is kept and no extra load happens`() = runTest {
        every { accelerationProbe.isGpuAvailable() } returns true
        coEvery { testBackendUseCase.invoke(modelPath) } returns probeResult(success = true)
        var checkAnnounced = false

        val outcome = useCase(modelPath) { checkAnnounced = true }

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.GPU), outcome)
        assertEquals(true, checkAnnounced)
        coVerify(exactly = 1) { settingsRepository.setLocalModelBackend(LocalBackend.GPU.key) }
        coVerify(exactly = 0) { settingsRepository.setLocalModelBackend(LocalBackend.CPU.key) }
        // The verification generation *is* the warm-up — a second load would
        // just be a redundant round-trip through the engine.
        coVerify(exactly = 0) { loadModelUseCase.invoke(any(), any(), any()) }
        coVerify(exactly = 0) { llmInferenceEngine.unload() }
    }

    @Test
    fun `given a GPU that fails to generate when preparing then it falls back to CPU after unloading`() = runTest {
        every { accelerationProbe.isGpuAvailable() } returns true
        coEvery { testBackendUseCase.invoke(modelPath) } returns probeResult(
            success = false,
            errorMessage = "Can not find OpenCL library",
        )

        val outcome = useCase(modelPath)

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        // Order matters: the engine may hold a live GPU handle for this exact
        // model, and its reuse check ignores the backend — without the teardown
        // between the write and the reload, the retry would silently stay on GPU.
        coVerifyOrder {
            settingsRepository.setLocalModelBackend(LocalBackend.GPU.key)
            testBackendUseCase.invoke(modelPath)
            settingsRepository.setLocalModelBackend(LocalBackend.CPU.key)
            llmInferenceEngine.unload()
            loadModelUseCase.invoke(modelPath, any(), any())
        }
    }

    @Test
    fun `given a settings write that fails when preparing then the handle is still warmed on CPU`() = runTest {
        every { accelerationProbe.isGpuAvailable() } returns true
        coEvery { settingsRepository.setLocalModelBackend(any()) } throws IOException("no space left on device")

        val outcome = useCase(modelPath)

        // Failing to record the decision must not cost the user a working
        // handle — this runs inside onboarding, where an escaping exception
        // would break the first run entirely.
        assertEquals(PrepareInferenceBackendUseCase.Outcome.Warmed(LocalBackend.CPU), outcome)
        coVerify(exactly = 1) { loadModelUseCase.invoke(modelPath, any(), any()) }
    }

    @Test
    fun `given the CPU fallback also fails when preparing then the failure surfaces to the caller`() = runTest {
        every { accelerationProbe.isGpuAvailable() } returns false
        coEvery { loadModelUseCase.invoke(any(), any(), any()) } returns Result.Error(
            error = object : AppError.System {},
            message = "model file is gone",
        )

        val outcome = useCase(modelPath)

        assertEquals(PrepareInferenceBackendUseCase.Outcome.Failed("model file is gone"), outcome)
    }

    private fun probeResult(success: Boolean, errorMessage: String? = null) = TestProbeResult(
        tokensGenerated = if (success) 3 else 0,
        durationMs = 660L,
        timestampMs = 1_000L,
        success = success,
        errorMessage = errorMessage,
    )
}
