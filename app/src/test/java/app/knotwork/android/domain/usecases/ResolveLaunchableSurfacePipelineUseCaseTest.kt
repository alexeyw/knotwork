package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ResolveLaunchableSurfacePipelineUseCase]: a surface runs only a
 * pipeline that exists. A dangling binding — left by "Erase data", which keeps
 * the settings — used to reach the run queue, which fell back to the default
 * pipeline.
 */
class ResolveLaunchableSurfacePipelineUseCaseTest {

    private val resolveSurfacePipeline = mockk<ResolveSurfacePipelineUseCase>()
    private val pipelineRepository = mockk<PipelineRepository>()
    private val useCase = ResolveLaunchableSurfacePipelineUseCase(resolveSurfacePipeline, pipelineRepository)

    @Test
    fun `given a binding to an existing pipeline when resolved then its id is returned`() = runTest {
        coEvery { resolveSurfacePipeline(EntrySurface.SHARE) } returns "pipe-1"
        coEvery { pipelineRepository.getPipelineById("pipe-1") } returns mockk<PipelineGraph>()

        assertEquals("pipe-1", useCase(EntrySurface.SHARE))
    }

    @Test
    fun `given a binding to a pipeline that no longer exists when resolved then the surface is inert`() = runTest {
        for (surface in listOf(EntrySurface.SHARE, EntrySurface.QUICK_TILE)) {
            coEvery { resolveSurfacePipeline(surface) } returns "gone"
            coEvery { pipelineRepository.getPipelineById("gone") } returns null

            assertNull("$surface ran a dangling binding", useCase(surface))
        }
    }

    @Test
    fun `given no binding when resolved then the repository is not asked`() = runTest {
        coEvery { resolveSurfacePipeline(EntrySurface.QUICK_TILE) } returns null

        assertNull(useCase(EntrySurface.QUICK_TILE))
        coVerify(exactly = 0) { pipelineRepository.getPipelineById(any()) }
    }
}
