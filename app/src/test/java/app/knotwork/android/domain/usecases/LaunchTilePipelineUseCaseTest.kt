package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.services.TaskScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LaunchTilePipelineUseCase], covering the bound (enqueue a
 * background run) and unbound (no-op) branches.
 */
class LaunchTilePipelineUseCaseTest {

    private val resolveSurfacePipeline = mockk<ResolveLaunchableSurfacePipelineUseCase>()
    private val taskScheduler = mockk<TaskScheduler>(relaxed = true)
    private val useCase = LaunchTilePipelineUseCase(resolveSurfacePipeline, taskScheduler)

    @Test
    fun `given a bound tile pipeline when invoked then schedules a QUICK_TILE run`() = runTest {
        coEvery { resolveSurfacePipeline(EntrySurface.QUICK_TILE) } returns "duty-pipe"

        val result = useCase("Run")

        assertEquals(TileLaunchResult.Launched, result)
        coVerify {
            taskScheduler.scheduleOneTime(
                prompt = "Run",
                delayMinutes = 0,
                sessionId = null,
                constraints = any(),
                pipelineId = "duty-pipe",
                origin = RunOrigin.QUICK_TILE,
            )
        }
    }

    @Test
    fun `given no bound tile pipeline when invoked then reports NotConfigured and schedules nothing`() = runTest {
        coEvery { resolveSurfacePipeline(EntrySurface.QUICK_TILE) } returns null

        val result = useCase("Run")

        assertEquals(TileLaunchResult.NotConfigured, result)
        coVerify(exactly = 0) {
            taskScheduler.scheduleOneTime(any(), any(), any(), any(), any(), any())
        }
    }
}
