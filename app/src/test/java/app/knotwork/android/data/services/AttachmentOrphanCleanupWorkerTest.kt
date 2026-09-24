package app.knotwork.android.data.services

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.knotwork.android.domain.services.TransientCacheSweeper
import app.knotwork.android.domain.usecases.CleanupOrphanAttachmentsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for the daily file-maintenance [AttachmentOrphanCleanupWorker]:
 * both passes run, and a failure asks WorkManager to retry. Hilt's assisted
 * injection only fires in a real application, so the mocks arrive through a
 * manual [WorkerFactory], as in [RunRetentionWorkerTest].
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentOrphanCleanupWorkerTest {

    private lateinit var context: Context
    private lateinit var orphanCleanup: CleanupOrphanAttachmentsUseCase
    private lateinit var sweeper: TransientCacheSweeper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        orphanCleanup = mockk()
        sweeper = mockk()
    }

    private fun buildWorker(): AttachmentOrphanCleanupWorker =
        TestListenableWorkerBuilder<AttachmentOrphanCleanupWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        AttachmentOrphanCleanupWorker(appContext, workerParameters, orphanCleanup, sweeper)
                },
            )
            .build()

    @Test
    fun `given both passes complete when doWork runs then both ran and it succeeds`() = runTest {
        coEvery { orphanCleanup() } returns 2
        coEvery { sweeper.sweepExpired() } returns 5

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { orphanCleanup() }
        coVerify(exactly = 1) { sweeper.sweepExpired() }
    }

    @Test
    fun `given the orphan pass throws when doWork runs then the sweep still runs and it asks to retry`() = runTest {
        coEvery { orphanCleanup() } throws IllegalStateException("database locked")
        coEvery { sweeper.sweepExpired() } returns 3

        val result = buildWorker().doWork()

        // The passes are independent: a database problem must not keep the camera
        // captures and share copies in the cache.
        coVerify(exactly = 1) { sweeper.sweepExpired() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `given the sweep throws when doWork runs then it asks to retry`() = runTest {
        coEvery { orphanCleanup() } returns 0
        coEvery { sweeper.sweepExpired() } throws IllegalStateException("cache unavailable")

        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
    }
}
