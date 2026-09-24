package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CheckImageAttachmentUseCase]: each of the three refusals, their
 * precedence, and the pass — the one decision both the composer and the share
 * target ask.
 */
class CheckImageAttachmentUseCaseTest {

    private val resolveEntryInference = mockk<ResolveEntryInferenceUseCase>()
    private val localModelRepository = mockk<LocalModelRepository>()
    private val useCase = CheckImageAttachmentUseCase(resolveEntryInference, localModelRepository)

    private fun activeModel(supportsVision: Boolean) =
        LocalModel(id = 1L, name = "m", path = "/m", size = 0L, isActive = true, supportsVision = supportsVision)

    @Test
    fun `given a local vision sink and a vision model when checked then the image may run`() = runTest {
        coEvery { resolveEntryInference("p") } returns EntryInferenceKind.LOCAL
        coEvery { localModelRepository.getActiveModel() } returns activeModel(supportsVision = true)

        assertNull(useCase("p"))
    }

    @Test
    fun `given a cloud-first pipeline when checked then CLOUD_ENTRY wins over the model check`() = runTest {
        coEvery { resolveEntryInference("p") } returns EntryInferenceKind.CLOUD
        coEvery { localModelRepository.getActiveModel() } returns activeModel(supportsVision = false)

        assertEquals(ImageAttachmentBlock.CLOUD_ENTRY, useCase("p"))
    }

    @Test
    fun `given a text-only active model when checked then MODEL_NO_VISION wins over a missing sink`() = runTest {
        coEvery { resolveEntryInference("p") } returns EntryInferenceKind.NONE
        coEvery { localModelRepository.getActiveModel() } returns activeModel(supportsVision = false)

        assertEquals(ImageAttachmentBlock.MODEL_NO_VISION, useCase("p"))
    }

    @Test
    fun `given no active model when checked then MODEL_NO_VISION`() = runTest {
        coEvery { resolveEntryInference(null) } returns EntryInferenceKind.LOCAL
        coEvery { localModelRepository.getActiveModel() } returns null

        assertEquals(ImageAttachmentBlock.MODEL_NO_VISION, useCase(null))
    }

    @Test
    fun `given a vision model but no reachable sink when checked then NO_VISION_STEP`() = runTest {
        coEvery { resolveEntryInference("p") } returns EntryInferenceKind.NONE
        coEvery { localModelRepository.getActiveModel() } returns activeModel(supportsVision = true)

        assertEquals(ImageAttachmentBlock.NO_VISION_STEP, useCase("p"))
    }
}
