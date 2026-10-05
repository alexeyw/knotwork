package app.knotwork.android.data.services

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkManagerModelFileHashScheduler]: a registration chains a pass, and the
 * start-up re-arm schedules one only for unhashed files without ever crashing start-up.
 */
class WorkManagerModelFileHashSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val repository = mockk<LocalModelRepository>()
    private val scheduler = WorkManagerModelFileHashScheduler(workManager, repository)

    @Test
    fun `given a registration when scheduled then a pass is chained after any running one`() {
        val name = slot<String>()
        val policy = slot<ExistingWorkPolicy>()
        every { workManager.enqueueUniqueWork(capture(name), capture(policy), any<OneTimeWorkRequest>()) } returns
            mockk(relaxed = true)

        scheduler.schedule()

        assertEquals(ModelFileHashWorker.UNIQUE_NAME, name.captured)
        // APPEND_OR_REPLACE, not KEEP: a pass already running listed its files before
        // this model existed, and KEEP would drop the request that covers it.
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, policy.captured)
    }

    @Test
    fun `given an unhashed model at start-up when re-armed then a pass is scheduled`() = runTest {
        coEvery { repository.modelsNeedingFileHash() } returns
            listOf(LocalModel(id = 1L, name = "m", path = "/m", size = 1L, isActive = false))

        scheduler.rearmIfPending()

        verifyEnqueued(times = 1)
    }

    @Test
    fun `given every model hashed when re-armed then nothing is scheduled`() = runTest {
        coEvery { repository.modelsNeedingFileHash() } returns emptyList()

        scheduler.rearmIfPending()

        verifyEnqueued(times = 0)
    }

    @Test
    fun `given the registry cannot be opened when re-armed then start-up is not crashed`() = runTest {
        // The splash recovery screen handles a database whose key is lost; a throw from
        // this fire-and-forget step would kill the process before it could show.
        coEvery { repository.modelsNeedingFileHash() } throws IllegalStateException("database unavailable")

        scheduler.rearmIfPending()

        verifyEnqueued(times = 0)
    }

    @Test
    fun `given start-up is cancelled when re-armed then cancellation propagates`() = runTest {
        coEvery { repository.modelsNeedingFileHash() } throws CancellationException("activity destroyed")

        val thrown = runCatching { scheduler.rearmIfPending() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    /** Asserts how many hashing passes were handed to WorkManager. */
    private fun verifyEnqueued(times: Int) {
        verify(exactly = times) {
            workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }
}
