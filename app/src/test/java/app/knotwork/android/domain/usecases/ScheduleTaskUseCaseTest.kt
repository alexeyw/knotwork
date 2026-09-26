package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.TaskScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScheduleTaskUseCaseTest {

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var pipelineRunRepository: PipelineRunRepository
    private lateinit var scheduleTaskUseCase: ScheduleTaskUseCase

    @Before
    fun setup() {
        taskScheduler = mockk()
        pipelineRunRepository = mockk()
        coEvery { taskScheduler.scheduleOneTime(any(), any(), any(), any()) } just Runs
        coEvery { taskScheduler.schedulePeriodic(any(), any(), any(), any()) } just Runs
        // Quiet history by default; the runaway-guard tests override it.
        coEvery { pipelineRunRepository.countRootRunsByOriginSince(any(), any()) } returns 0
        scheduleTaskUseCase = ScheduleTaskUseCase(taskScheduler, pipelineRunRepository)
    }

    @Test
    fun `given positive interval when invoked then delegates to periodic scheduling`() = runTest {
        val result = scheduleTaskUseCase("check emails", intervalHours = 2, delayMinutes = 0)

        coVerify {
            taskScheduler.schedulePeriodic(
                "check emails",
                2L,
                null,
                ScheduledTaskConstraints(requiresBatteryNotLow = true),
            )
        }
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any()) }
        assertTrue(result.contains("every 2 hours"))
    }

    @Test
    fun `given zero interval when invoked then delegates to one-time scheduling`() = runTest {
        val result = scheduleTaskUseCase("check emails once", intervalHours = 0, delayMinutes = 10)

        coVerify {
            taskScheduler.scheduleOneTime(
                "check emails once",
                10L,
                null,
                ScheduledTaskConstraints(requiresBatteryNotLow = true),
            )
        }
        coVerify(exactly = 0) { taskScheduler.schedulePeriodic(any(), any(), any(), any()) }
        assertTrue(result.contains("10 minutes delay"))
    }

    @Test
    fun `given session id when one-time then forwards session id to port`() = runTest {
        val sessionSlot = slot<String?>()
        coEvery { taskScheduler.scheduleOneTime(any(), any(), captureNullable(sessionSlot), any()) } just Runs

        scheduleTaskUseCase("check emails once", sessionId = "session-7")

        assertEquals("session-7", sessionSlot.captured)
    }

    @Test
    fun `given session id when periodic then forwards session id to port`() = runTest {
        val sessionSlot = slot<String?>()
        coEvery { taskScheduler.schedulePeriodic(any(), any(), captureNullable(sessionSlot), any()) } just Runs

        scheduleTaskUseCase("check emails", intervalHours = 2, sessionId = "session-7")

        assertEquals("session-7", sessionSlot.captured)
    }

    @Test
    fun `given no session id when invoked then forwards null to port`() = runTest {
        val sessionSlot = slot<String?>()
        coEvery { taskScheduler.scheduleOneTime(any(), any(), captureNullable(sessionSlot), any()) } just Runs

        scheduleTaskUseCase("check emails once")

        assertNull(sessionSlot.captured)
    }

    @Test
    fun `given scheduler throws when invoked then returns failure message`() = runTest {
        coEvery {
            taskScheduler.scheduleOneTime(any(), any(), any(), any())
        } throws IllegalStateException("queue full")

        val result = scheduleTaskUseCase("check emails once")

        assertTrue(result.startsWith("Failed to schedule task:"))
        assertTrue(result.contains("queue full"))
    }

    // --- Runaway guard ------------------------------------------------------

    @Test
    fun `given the hourly limit is already reached when invoked then nothing is scheduled`() = runTest {
        coEvery { pipelineRunRepository.countRootRunsByOriginSince(any(), any()) } returns
            ScheduleTaskUseCase.MAX_SCHEDULED_RUNS_PER_HOUR

        val result = scheduleTaskUseCase("keep going forever")

        assertEquals(ScheduleTaskUseCase.REFUSAL_MESSAGE, result)
        coVerify(exactly = 0) { taskScheduler.scheduleOneTime(any(), any(), any(), any()) }
        coVerify(exactly = 0) { taskScheduler.schedulePeriodic(any(), any(), any(), any()) }
    }

    @Test
    fun `given the limit is reached when invoked then the periodic path is refused too`() = runTest {
        // A chain can hide behind either schedule kind; refusing only one would
        // leave the loop an obvious way around the guard.
        coEvery { pipelineRunRepository.countRootRunsByOriginSince(any(), any()) } returns 99

        val result = scheduleTaskUseCase("keep going forever", intervalHours = 1)

        assertEquals(ScheduleTaskUseCase.REFUSAL_MESSAGE, result)
        coVerify(exactly = 0) { taskScheduler.schedulePeriodic(any(), any(), any(), any()) }
    }

    @Test
    fun `given one run below the limit when invoked then it still schedules`() = runTest {
        coEvery { pipelineRunRepository.countRootRunsByOriginSince(any(), any()) } returns
            ScheduleTaskUseCase.MAX_SCHEDULED_RUNS_PER_HOUR - 1

        scheduleTaskUseCase("legitimate follow-up")

        coVerify(exactly = 1) { taskScheduler.scheduleOneTime(any(), any(), any(), any()) }
    }

    @Test
    fun `when invoked then the guard counts scheduler runs of the last hour only`() = runTest {
        val now = 10_000_000L

        scheduleTaskUseCase("check emails once", nowMillis = now)

        // Counting every scheduled run ever would refuse a perfectly healthy
        // schedule after a few busy days.
        coVerify(exactly = 1) {
            pipelineRunRepository.countRootRunsByOriginSince(RunOrigin.SCHEDULER, now - 3_600_000L)
        }
    }

    @Test
    fun `given the run-history read fails when invoked then scheduling is still allowed`() = runTest {
        // The port degrades to 0 on a storage error; the guard must fail open —
        // a diagnostic count is never a reason to block a legitimate task.
        coEvery { pipelineRunRepository.countRootRunsByOriginSince(any(), any()) } returns 0

        scheduleTaskUseCase("legitimate task")

        coVerify(exactly = 1) { taskScheduler.scheduleOneTime(any(), any(), any(), any()) }
    }
}
