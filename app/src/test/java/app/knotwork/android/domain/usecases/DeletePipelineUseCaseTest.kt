package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.PipelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeletePipelineUseCase].
 *
 * There is no "active pipeline" rule any more: every pipeline can be deleted, and
 * keeping the editor consistent is the ViewModel's job.
 */
class DeletePipelineUseCaseTest {

    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val useCase = DeletePipelineUseCase(pipelineRepository)

    @Test
    fun `given any pipeline when invoke then deletes it`() = runTest {
        val result = useCase(pipelineId = "p1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pipelineRepository.deletePipeline("p1") }
    }

    @Test
    fun `given delete throws when invoke then returns failure`() = runTest {
        coEvery { pipelineRepository.deletePipeline("p2") } throws RuntimeException("io error")

        val result = useCase(pipelineId = "p2")

        assertTrue(result.isFailure)
        assertEquals("io error", result.exceptionOrNull()?.message)
    }
}
