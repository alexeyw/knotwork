package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.services.TaskScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [CancelScheduledTasksUseCase] — the escape hatch from a task
 * that keeps re-scheduling itself.
 */
class CancelScheduledTasksUseCaseTest {

    private val taskScheduler = mockk<TaskScheduler>()
    private val useCase = CancelScheduledTasksUseCase(taskScheduler)

    @Test
    fun `when invoked then it cancels every scheduled task through the port`() {
        coEvery { taskScheduler.cancelAllScheduled() } just Runs

        useCase()

        coVerify(exactly = 1) { taskScheduler.cancelAllScheduled() }
    }

    @Test
    fun `when invoked then it schedules nothing of its own`() {
        coEvery { taskScheduler.cancelAllScheduled() } just Runs

        useCase()

        // A recovery action that re-armed anything would defeat its purpose.
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any()) }
        coVerify(exactly = 0) { taskScheduler.schedulePeriodic(any(), any(), any(), any()) }
    }
}
