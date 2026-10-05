package app.knotwork.android.data.services

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.knotwork.android.domain.usecases.ComputeModelFileHashesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for the `@HiltWorker`-annotated [ModelFileHashWorker],
 * mirroring [MemoryReembedWorkerTest]: the mocked use case reaches the worker
 * through a manual [WorkerFactory].
 */
@RunWith(RobolectricTestRunner::class)
class ModelFileHashWorkerTest {

    private lateinit var context: Context
    private lateinit var computeModelFileHashes: ComputeModelFileHashesUseCase

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        computeModelFileHashes = mockk()
    }

    private fun buildWorker(): ModelFileHashWorker = TestListenableWorkerBuilder<ModelFileHashWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ModelFileHashWorker(appContext, workerParameters, computeModelFileHashes)
            },
        )
        .build()

    @Test
    fun `given the pass completes when the worker runs then it succeeds`() = runTest {
        coEvery { computeModelFileHashes() } returns 2

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        coVerify(exactly = 1) { computeModelFileHashes() }
    }

    @Test
    fun `given the registry cannot be read when the worker runs then it asks to retry`() = runTest {
        coEvery { computeModelFileHashes() } throws IllegalStateException("database unavailable")

        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
    }

    @Test
    fun `given the worker is stopped when the pass is cancelled then cancellation propagates`() = runTest {
        coEvery { computeModelFileHashes() } throws CancellationException("stopped")

        val thrown = runCatching { buildWorker().doWork() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
