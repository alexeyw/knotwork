package app.knotwork.android.data.services

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.usecases.CleanupPipelineRunsUseCase
import app.knotwork.android.domain.usecases.CleanupTriggerJournalUseCase
import app.knotwork.android.domain.usecases.automation.CleanupExternalAutomationJournalUseCase
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
 * Robolectric coverage for the `@HiltWorker`-annotated [RunRetentionWorker].
 *
 * As with [MemoryCompactionWorkerTest], Hilt's assisted-inject machinery only
 * fires inside a real application runtime, so the test hands the mocked use
 * cases to the worker through a manual [WorkerFactory] and drives `doWork()`
 * via [TestListenableWorkerBuilder].
 */
@RunWith(RobolectricTestRunner::class)
class RunRetentionWorkerTest {

    private lateinit var context: Context
    private lateinit var runUseCase: CleanupPipelineRunsUseCase
    private lateinit var journalUseCase: CleanupTriggerJournalUseCase
    private lateinit var externalJournalUseCase: CleanupExternalAutomationJournalUseCase
    private lateinit var taskScheduler: TaskScheduler

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        runUseCase = mockk()
        journalUseCase = mockk()
        externalJournalUseCase = mockk()
        taskScheduler = mockk()
        coEvery { taskScheduler.pruneOrphanPrompts() } returns 0
    }

    private fun workerFactory(): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = RunRetentionWorker(
            appContext,
            workerParameters,
            runUseCase,
            journalUseCase,
            externalJournalUseCase,
            taskScheduler,
        )
    }

    private fun buildWorker(): RunRetentionWorker = TestListenableWorkerBuilder<RunRetentionWorker>(context)
        .setWorkerFactory(workerFactory())
        .build()

    @Test
    fun `given both passes complete when doWork runs then returns success and runs both`() = runTest {
        coEvery { runUseCase() } returns CleanupPipelineRunsUseCase.Outcome(
            deletedRuns = 3,
            deletedLegacyTraceRows = 1,
        )
        coEvery { journalUseCase(any()) } returns 7
        coEvery { externalJournalUseCase(any()) } returns 3

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { runUseCase() }
        coVerify(exactly = 1) { journalUseCase(any()) }
        coVerify(exactly = 1) { externalJournalUseCase(any()) }
        // The prompts of background runs cancelled before they ran go in the same pass.
        coVerify(exactly = 1) { taskScheduler.pruneOrphanPrompts() }
    }

    @Test
    fun `given the run pass throws when doWork runs then returns retry`() = runTest {
        coEvery { runUseCase() } throws IllegalStateException("store unavailable")

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `given the journal pass throws when doWork runs then returns retry`() = runTest {
        coEvery { runUseCase() } returns CleanupPipelineRunsUseCase.Outcome(
            deletedRuns = 0,
            deletedLegacyTraceRows = 0,
        )
        coEvery { journalUseCase(any()) } throws IllegalStateException("journal store unavailable")

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
