package app.knotwork.android.data.services

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.knotwork.android.R
import app.knotwork.android.data.local.StreamingModelFileHasher
import app.knotwork.android.data.network.ResumableFileDownloader
import app.knotwork.android.domain.models.ModelDownloadFailure
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.RegisterDownloadedModelUseCase
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Covers the download worker's policy decisions: what it does with the token,
 * what it does once the bytes are on disk, and — the part that decides whether
 * a flaky network costs the user their progress — when a failure is worth
 * another attempt.
 *
 * Also the guard of the download check: a file whose source published a hash is
 * never registered unless it matches (run through the real installer and hasher).
 */
@RunWith(RobolectricTestRunner::class)
class ModelDownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var downloader: ResumableFileDownloader
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var registerDownloadedModel: RegisterDownloadedModelUseCase
    private lateinit var installer: DownloadedModelInstaller
    private lateinit var interruptions: DownloadInterruptionStore
    private lateinit var notifier: ModelDownloadOutcomeNotifier

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        downloader = mockk()
        settingsRepository = mockk()
        every { settingsRepository.huggingFaceAuthToken } returns flowOf("hf_stored")
        registerDownloadedModel = mockk(relaxed = true)
        coEvery { registerDownloadedModel(any(), any(), any(), any(), any(), any()) } returns 1L
        interruptions = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        installer = mockk()
        coEvery { installer.install(any(), any(), any()) } returns
            DownloadedModelInstaller.Result.Installed(path = "/models/m.bin", verified = false)
    }

    @Test
    fun `given a completed download when it finishes then the file is installed with its source`() = runTest {
        val downloaded = downloadedPart(published = null)
        coEvery { downloader.download(any(), any(), any(), any()) } returns downloaded

        val result = worker().doWork()

        assertEquals(
            "/models/m.bin",
            (result as ListenableWorker.Result.Success).outputData.getString(ModelDownloadWorker.KEY_OUTPUT_PATH),
        )
        // Installation lives here because the transfer outlives every screen —
        // a survived download must leave a model the app knows about.
        // The address goes with it, so a restored phone can fetch the model again.
        coVerify(exactly = 1) {
            installer.install(downloaded, fileName = "m.bin", sourceUrl = "http://example.com/m.bin")
        }
    }

    @Test
    fun `given a file whose hash differs from the published one when it finishes then it fails for good`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns downloadedPart(published = PUBLISHED)
        coEvery { installer.install(any(), any(), any()) } returns
            DownloadedModelInstaller.Result.Rejected(publishedSha256 = PUBLISHED, actualSha256 = "0".repeat(64))

        val result = worker().doWork()

        // Final, not retried: the file was deleted, and the same source would
        // hand over the same file again.
        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            context.getString(R.string.model_download_checksum_mismatch),
            failure.outputData.getString(ModelDownloadWorker.KEY_ERROR),
        )
        assertEquals("CHECKSUM_MISMATCH", failure.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
        assertEquals(
            ModelDownloadWorker.NO_HTTP_CODE,
            failure.outputData.getInt(ModelDownloadWorker.KEY_ERROR_CODE, 0),
        )
        // Usually nobody is watching at the end of a transfer: the reason waits for the next
        // screen, and the shade says it now.
        val message = context.getString(R.string.model_download_checksum_mismatch)
        verifyOrder {
            interruptions.record("m.bin", "http://example.com/m.bin", ModelDownloadFailure.CHECKSUM_MISMATCH, message)
            notifier.notifyStopped("m.bin", ModelDownloadFailure.CHECKSUM_MISMATCH)
        }
    }

    @Test
    fun `given a file that could not be checked when it finishes then another attempt is scheduled`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns downloadedPart(published = PUBLISHED)
        coEvery { installer.install(any(), any(), any()) } returns
            DownloadedModelInstaller.Result.NotInstalled(DownloadedModelInstaller.Result.Cause.UNREADABLE)

        val result = worker().doWork()

        // The complete part is still on disk; the next attempt finds it whole and
        // only repeats the check.
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `given a check that fails after a transfer that took several runs when it fails then it still retries`() =
        runTest {
            // Every lapse of the network constraint is a run of its own, so a long transfer
            // can reach its first check on the third run. The bytes it fetched earn another try.
            coEvery { downloader.download(any(), any(), any(), any()) } returns
                downloadedPart(published = PUBLISHED).copy(bytesTransferred = 4_096L)
            coEvery { installer.install(any(), any(), any()) } returns
                DownloadedModelInstaller.Result.NotInstalled(DownloadedModelInstaller.Result.Cause.UNREADABLE)

            val result = worker(runAttemptCount = 2).doWork()

            assertTrue(result is ListenableWorker.Result.Retry)
        }

    @Test
    fun `given a rename that fails after a transfer when the attempts are spent then it fails for good`() = runTest {
        // From a host that names no length every attempt downloads the file again; crediting
        // those bytes would repeat a multi-gigabyte download for as long as the rename fails.
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            downloadedPart(published = null).copy(bytesTransferred = 4_096L)
        coEvery { installer.install(any(), any(), any()) } returns
            DownloadedModelInstaller.Result.NotInstalled(DownloadedModelInstaller.Result.Cause.NOT_MOVED)

        val result = worker(runAttemptCount = 2).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `given a check that keeps failing on a complete part when the attempts are spent then it fails for good`() =
        runTest {
            // A run that found the part complete fetched nothing: a check failing again
            // there counts against the attempts, so an unreadable file cannot loop forever.
            coEvery { downloader.download(any(), any(), any(), any()) } returns downloadedPart(published = PUBLISHED)
            coEvery { installer.install(any(), any(), any()) } returns
                DownloadedModelInstaller.Result.NotInstalled(DownloadedModelInstaller.Result.Cause.UNREADABLE)

            val result = worker(runAttemptCount = 2).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun `given a published hash the file does not match when the real installer runs then nothing is registered`() =
        runTest {
            // The guard of the download check, end to end through the worker: the real
            // installer, the real streaming hasher, a file that arrived with other bytes.
            val part = File(context.cacheDir, "m.bin.cafe.part").apply { writeText("tampered") }
            val target = File(context.cacheDir, "m.bin").apply { delete() }
            coEvery { downloader.download(any(), any(), any(), any()) } returns
                ResumableFileDownloader.Outcome.Downloaded(part.absolutePath, target.absolutePath, sha256Of("weights"))
            installer =
                DownloadedModelInstaller(StreamingModelFileHasher(Dispatchers.Unconfined), registerDownloadedModel)

            val result = worker().doWork()

            assertEquals(
                "CHECKSUM_MISMATCH",
                (result as ListenableWorker.Result.Failure).outputData.getString(ModelDownloadWorker.KEY_FAILURE),
            )
            coVerify(exactly = 0) { registerDownloadedModel(any(), any(), any(), any(), any(), any()) }
            assertFalse("the file must not reach a model's name", target.exists())
            assertFalse("the part must not be resumed into the same wrong file", part.exists())
        }

    @Test
    fun `given a published hash the file matches when the real installer runs then it is registered as checked`() =
        runTest {
            val part = File(context.cacheDir, "m.bin.cafe.part").apply { writeText("weights") }
            val target = File(context.cacheDir, "m.bin").apply { delete() }
            coEvery { downloader.download(any(), any(), any(), any()) } returns
                ResumableFileDownloader.Outcome.Downloaded(part.absolutePath, target.absolutePath, sha256Of("weights"))
            installer =
                DownloadedModelInstaller(StreamingModelFileHasher(Dispatchers.Unconfined), registerDownloadedModel)

            val result = worker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("weights", target.readText())
            coVerify(exactly = 1) {
                registerDownloadedModel(
                    fileName = "m.bin",
                    path = target.absolutePath,
                    sizeBytes = "weights".length.toLong(),
                    sourceUrl = "http://example.com/m.bin",
                    sourceSha256 = sha256Of("weights"),
                    fileHash = any(),
                )
            }
        }

    @Test
    fun `given no stored-auth flag when downloading then no token is read or sent`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns downloadedPart(published = null)

        worker(useStoredAuth = false).doWork()

        coVerify { downloader.download(any(), any(), null, any()) }
    }

    @Test
    fun `given the stored-auth flag when downloading then the token comes from the encrypted store`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns downloadedPart(published = null)

        worker(useStoredAuth = true).doWork()

        // The secret is read here, not carried through worker input — that
        // input is persisted by WorkManager in the clear.
        coVerify { downloader.download(any(), any(), "hf_stored", any()) }
    }

    @Test
    fun `given a transport failure on the first attempt when it fails then another attempt is scheduled`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Network timeout", httpCode = null)

        val result = worker().doWork()

        // Retrying resumes rather than restarts, so it costs the user nothing.
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `given an HTTP status when it fails then it gives up immediately`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Server returned code: 404", httpCode = 404)

        val result = worker().doWork()

        // The server's answer will not change on a retry — spending the user's
        // battery to hear it again is the wrong trade.
        val failure = result as ListenableWorker.Result.Failure
        assertEquals(404, failure.outputData.getInt(ModelDownloadWorker.KEY_ERROR_CODE, 0))
        assertEquals("Server returned code: 404", failure.outputData.getString(ModelDownloadWorker.KEY_ERROR))
        // An HTTP refusal comes at once, while the user is looking: nothing to keep or announce.
        verify(exactly = 0) { interruptions.record(any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.notifyStopped(any(), any()) }
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when the server refuses the download then the failure reads in Russian`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Server returned code: 404", httpCode = 404)

        val failure = worker().doWork() as ListenableWorker.Result.Failure

        assertEquals("Сервер вернул код 404", failure.outputData.getString(ModelDownloadWorker.KEY_ERROR))
    }

    @Test
    fun `given a transport failure without text when it fails for good then it reads as unknown`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("", bytesTransferred = 0L)

        val failure = worker(runAttemptCount = 2).doWork() as ListenableWorker.Result.Failure

        assertEquals("Unknown download error", failure.outputData.getString(ModelDownloadWorker.KEY_ERROR))
    }

    @Test
    fun `given the attempt budget is spent when a transport failure repeats then it fails for good`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Network timeout", httpCode = null, bytesTransferred = 0L)

        val result = worker(runAttemptCount = 2).doWork()

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            ModelDownloadWorker.NO_HTTP_CODE,
            failure.outputData.getInt(ModelDownloadWorker.KEY_ERROR_CODE, 0),
        )
    }

    @Test
    fun `given a stalled transfer past the budget when it fails then it stops retrying`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Network timeout", httpCode = null, bytesTransferred = 0L)

        val result = worker(runAttemptCount = 2).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `given a failing attempt that moved bytes when the budget is spent then it still retries`() = runTest {
        coEvery { downloader.download(any(), any(), any(), any()) } returns
            ResumableFileDownloader.Outcome.Failure("Network timeout", httpCode = null, bytesTransferred = 4_096L)

        val result = worker(runAttemptCount = 5).doWork()

        // The system re-runs this worker every time the network constraint
        // lapses, so the attempt count is not evidence of trouble — a file that
        // keeps growing is evidence of the opposite.
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `given a request without a URL when run then it fails instead of downloading`() = runTest {
        val result = worker(url = null).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 0) { downloader.download(any(), any(), any(), any()) }
    }

    @Test
    fun `given the foreground budget is spent when the download starts then it pauses without the foreground`() =
        runTest {
            val budget = DownloadForegroundBudget(context).apply { exhaust() }
            val foreground = RecordingForegroundUpdater()

            val result = worker(budget = budget, foreground = foreground).doWork()

            // Entering the foreground now would be refused by Android until the
            // user returns — or, worse, restart the clock that ends in a crash.
            val failure = result as ListenableWorker.Result.Failure
            assertEquals(
                context.getString(R.string.model_download_paused_time_limit),
                failure.outputData.getString(ModelDownloadWorker.KEY_ERROR),
            )
            assertEquals("PAUSED_TIME_LIMIT", failure.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
            assertEquals(0, foreground.calls)
            coVerify(exactly = 0) { downloader.download(any(), any(), any(), any()) }
            // This run is WorkManager's, after Android stopped the last one: nobody started it.
            verify(exactly = 1) {
                interruptions.record(
                    "m.bin",
                    "http://example.com/m.bin",
                    ModelDownloadFailure.PAUSED_TIME_LIMIT,
                    context.getString(R.string.model_download_paused_time_limit),
                )
            }
            verify(exactly = 1) { notifier.notifyStopped("m.bin", ModelDownloadFailure.PAUSED_TIME_LIMIT) }
        }

    @Test
    fun `given the budget runs out mid-transfer when downloading then it pauses instead of finishing`() = runTest {
        val budget = virtualBudget()
        spend(budget, DownloadForegroundBudget.BUDGET_MS - TEN_MINUTES_MS)
        coEvery { downloader.download(any(), any(), any(), any()) } coAnswers {
            delay(2 * TEN_MINUTES_MS)
            downloadedPart(published = null)
        }

        val result = worker(budget = budget).doWork()

        // The transfer is stopped between two chunks and its bytes stay in the
        // partial file, so tapping Download again continues from there.
        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            context.getString(R.string.model_download_paused_time_limit),
            failure.outputData.getString(ModelDownloadWorker.KEY_ERROR),
        )
        assertTrue(budget.isExhausted())
        assertEquals("PAUSED_TIME_LIMIT", failure.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
        coVerify(exactly = 0) { installer.install(any(), any(), any()) }
        verify(exactly = 1) {
            interruptions.record("m.bin", "http://example.com/m.bin", ModelDownloadFailure.PAUSED_TIME_LIMIT, any())
        }
        verify(exactly = 1) { notifier.notifyStopped("m.bin", ModelDownloadFailure.PAUSED_TIME_LIMIT) }
    }

    @Test
    fun `given time in the foreground when the download runs then it is booked against the budget`() = runTest {
        val budget = virtualBudget()
        var leftAtProgress = -1L
        coEvery { downloader.download(any(), any(), any(), any()) } coAnswers {
            val onProgress = arg<suspend (Int) -> Unit>(3)
            delay(TEN_MINUTES_MS)
            onProgress(50)
            leftAtProgress = budget.remainingMs()
            delay(TEN_MINUTES_MS)
            downloadedPart(published = null)
        }

        worker(budget = budget).doWork()

        // Booked as progress arrives, not only at the end: a process killed
        // mid-transfer loses at most the stretch since the last percent.
        assertEquals(DownloadForegroundBudget.BUDGET_MS - TEN_MINUTES_MS, leftAtProgress)
        assertEquals(DownloadForegroundBudget.BUDGET_MS - 2 * TEN_MINUTES_MS, budget.remainingMs())
    }

    @Test
    fun `given Android stops the download for its time limit when it is cancelled then the budget is spent`() =
        runTest {
            val budget = virtualBudget()
            coEvery { downloader.download(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            val subject = worker(budget = budget)
            val run = launch { subject.doWork() }
            runCurrent()

            // WorkManager records the reason and then cancels the work; `stop` is
            // its own (library-restricted) entry point for exactly that.
            subject.stop(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
            run.cancel()
            run.join()

            // The count fell short of Android's, so the next run must pause at
            // once rather than try the foreground again.
            assertTrue(budget.isExhausted())
        }

    @Test
    fun `given the download is stopped for another reason when it is cancelled then the budget keeps its time`() =
        runTest {
            val budget = virtualBudget()
            coEvery { downloader.download(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            val subject = worker(budget = budget)
            val run = launch { subject.doWork() }
            runCurrent()
            advanceTimeBy(TEN_MINUTES_MS)

            subject.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            run.cancel()
            run.join()

            assertEquals(DownloadForegroundBudget.BUDGET_MS - TEN_MINUTES_MS, budget.remainingMs())
        }

    private fun worker(
        url: String? = "http://example.com/m.bin",
        fileName: String = "m.bin",
        useStoredAuth: Boolean = false,
        runAttemptCount: Int = 0,
        budget: DownloadForegroundBudget = DownloadForegroundBudget(context),
        foreground: RecordingForegroundUpdater = RecordingForegroundUpdater(),
    ): ModelDownloadWorker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
        .setInputData(
            workDataOf(
                ModelDownloadWorker.KEY_URL to url,
                ModelDownloadWorker.KEY_FILE_NAME to fileName,
                ModelDownloadWorker.KEY_USE_STORED_AUTH to useStoredAuth,
            ),
        )
        .setRunAttemptCount(runAttemptCount)
        .setForegroundUpdater(foreground)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ModelDownloadWorker(
                    appContext,
                    workerParameters,
                    downloader,
                    settingsRepository,
                    installer,
                    budget,
                    interruptions,
                    notifier,
                )
            },
        )
        .build()

    /** A complete download of `m.bin`, as the downloader hands it over. */
    private fun downloadedPart(published: String?) = ResumableFileDownloader.Outcome.Downloaded(
        partPath = "/models/m.bin.cafe.part",
        targetPath = "/models/m.bin",
        publishedSha256 = published,
    )

    /** Lowercase hex SHA-256 of [text]'s UTF-8 bytes. */
    private fun sha256Of(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * A budget measured on the test's virtual clock, so a download's foreground
     * time is exactly the virtual time it spent.
     */
    private fun TestScope.virtualBudget(): DownloadForegroundBudget = DownloadForegroundBudget(
        preferences = { context.getSharedPreferences("virtual-budget", Context.MODE_PRIVATE) },
        clock = { testScheduler.currentTime },
    )

    /** Books [spentMs] of foreground time against [budget] on the virtual clock. */
    private fun TestScope.spend(budget: DownloadForegroundBudget, spentMs: Long) {
        val meter = budget.startMeter()
        advanceTimeBy(spentMs)
        meter.record()
    }

    /** Counts the worker's requests to enter or update the foreground. */
    private class RecordingForegroundUpdater : ForegroundUpdater {
        /** Requests received so far. */
        var calls = 0

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: ForegroundInfo,
        ): ListenableFuture<Void> {
            calls += 1
            return CompletedFuture
        }
    }

    /** A future that is already complete, as a granted foreground request is. */
    private object CompletedFuture : ListenableFuture<Void> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): Void? = null

        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }

    private companion object {
        /** Ten minutes of virtual time, in milliseconds. */
        const val TEN_MINUTES_MS = 10L * 60L * 1_000L

        /** The message of a file that could not be read for its check. */
        const val UNREADABLE = "The downloaded file could not be read to check it."

        /** A hash a source published. */
        const val PUBLISHED = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
