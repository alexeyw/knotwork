package app.knotwork.android.data.services

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.knotwork.android.R
import app.knotwork.android.data.network.ResumableFileDownloader
import app.knotwork.android.domain.constants.NotificationChannels
import app.knotwork.android.domain.constants.NotificationIds
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.usecases.RegisterDownloadedModelUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * Downloads one model file, outside the lifetime of whatever screen asked for
 * it.
 *
 * This is what makes a multi-gigabyte download survive the things that used to
 * kill it: leaving the app, finishing onboarding, or the system reclaiming the
 * screen. The transfer runs as a long-running worker promoted to a foreground
 * service, so it keeps its network connection while the app is in the
 * background, and its notification is what tells the user (and the system) that
 * the work is theirs.
 *
 * The bytes themselves are [ResumableFileDownloader]'s job — including resuming
 * a partial file, which is what makes the retry policy here worth having: a
 * transport failure is retried and continues from where it stopped, while an
 * HTTP status (`401`, `404`, …) fails immediately because retrying it would
 * only waste the user's battery on the same answer.
 *
 * **The auth token is deliberately not an input.** WorkManager persists worker
 * input in its own database in the clear; a Hugging Face token belongs in the
 * Keystore-backed store, so the worker is told only *whether* to authenticate
 * and reads the secret itself.
 *
 * Registration happens here too, for the same reason the transfer does: a
 * download that survives the screen must leave behind a model the app knows
 * about, not an anonymous file on disk.
 *
 * **Android's daily limit.** A `dataSync` foreground service may run six hours
 * a day while the app is out of sight, and one still running when that ends
 * crashes the app. The worker therefore spends at most what
 * [DownloadForegroundBudget] allows and then pauses, reporting a failure the
 * screen shows; starting the download again resumes it from the partial file.
 *
 * @property downloader Performs the resumable transfer.
 * @property generationSettings Source of the stored Hugging Face token.
 * @property registerDownloadedModel Records the finished file in the local
 *   model store.
 * @property foregroundBudget Foreground time the download may still spend
 *   before it pauses, so it never reaches Android's `dataSync` limit.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloader: ResumableFileDownloader,
    private val generationSettings: GenerationSettings,
    private val registerDownloadedModel: RegisterDownloadedModelUseCase,
    private val foregroundBudget: DownloadForegroundBudget,
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Runs one download attempt, within the foreground time [foregroundBudget]
     * still allows.
     *
     * A spent budget pauses the download before it enters the foreground, and a
     * budget that runs out mid-transfer stops it between two chunks; either way
     * the bytes so far stay in the partial file. If Android stops the worker for
     * its own time limit regardless, the budget is marked spent so the re-run
     * WorkManager schedules pauses at once instead of trying the foreground again.
     *
     * @return [Result.success] carrying the final path, [Result.retry] for a
     *   transport failure that resuming can still recover from, or
     *   [Result.failure] carrying the message the UI shows — including the pause.
     */
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val fileName = inputData.getString(KEY_FILE_NAME)
        if (url.isNullOrBlank() || fileName.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_ERROR to "Download request was missing its URL or file name."))
        }
        if (foregroundBudget.isExhausted()) {
            return pausedByTimeLimit()
        }

        val meter = foregroundBudget.startMeter()
        return try {
            transfer(url, fileName, meter)
        } finally {
            meter.record()
            // Read in `finally`, not in a catch: the cancellation must leave
            // untouched, and a worker that was not stopped reports
            // STOP_REASON_NOT_STOPPED here.
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                foregroundBudget.exhaust()
                Timber.w("Model download stopped by the foreground time limit; paused until the user starts it again")
            }
        }
    }

    /**
     * Promotes the worker to the foreground and transfers the file, booking the
     * foreground time on [meter] as progress arrives.
     *
     * @param url Direct URL of the model file.
     * @param fileName Target file name.
     * @param meter Books the foreground time against [foregroundBudget].
     * @return The attempt's result; the pause when the budget runs out first.
     */
    private suspend fun transfer(url: String, fileName: String, meter: DownloadForegroundBudget.Meter): Result {
        promoteToForeground(fileName, progress = 0)
        val token = if (inputData.getBoolean(KEY_USE_STORED_AUTH, false)) {
            generationSettings.huggingFaceAuthToken.first()
        } else {
            null
        }

        val outcome = withTimeoutOrNull(foregroundBudget.remainingMs()) {
            downloader.download(url = url, fileName = fileName, authToken = token) { percent ->
                meter.record()
                setProgress(workDataOf(KEY_PROGRESS to percent))
                promoteToForeground(fileName, percent)
            }
        } ?: return pausedByTimeLimit()

        return when (outcome) {
            is ResumableFileDownloader.Outcome.Success -> {
                registerDownloadedModel(
                    fileName = fileName,
                    path = outcome.path,
                    sizeBytes = File(outcome.path).length(),
                )
                Result.success(workDataOf(KEY_OUTPUT_PATH to outcome.path))
            }

            is ResumableFileDownloader.Outcome.Failure -> failOrRetry(outcome)
        }
    }

    /**
     * Decides between another attempt and giving up.
     *
     * Only transport failures are retried: an HTTP status is the server's
     * considered answer and will not change on its own.
     *
     * An attempt that moved bytes always earns another one, regardless of the
     * attempt count. The count is not a reliable measure of trouble here — the
     * system re-runs this worker whenever the network constraint lapses, so a
     * user riding a train can burn the budget on a transfer that is visibly
     * progressing. The rule still terminates: a genuinely stalled download
     * transfers nothing, and those attempts are counted.
     */
    private fun failOrRetry(failure: ResumableFileDownloader.Outcome.Failure): Result {
        val exhausted = runAttemptCount >= MAX_ATTEMPTS - 1 && failure.bytesTransferred == 0L
        return if (failure.httpCode != null || exhausted) {
            Timber.w("Model download failed permanently: %s", failure.message)
            Result.failure(
                workDataOf(
                    KEY_ERROR to failure.message,
                    // -1 rather than an absent key: the UI branches on the status
                    // (a gated 401/403 offers the token flow), and losing it here
                    // would silently downgrade that to a generic error.
                    KEY_ERROR_CODE to (failure.httpCode ?: NO_HTTP_CODE),
                ),
            )
        } else {
            Timber.i("Model download attempt %d failed (%s); retrying.", runAttemptCount + 1, failure.message)
            Result.retry()
        }
    }

    /**
     * Ends the attempt as paused: the foreground time budget is spent. Reported
     * as a failure so the screen shows why the download stopped; starting it
     * again resets the budget and resumes from the partial file.
     *
     * @return A failure carrying the pause message and no HTTP status.
     */
    private fun pausedByTimeLimit(): Result {
        Timber.i("Model download paused: foreground time budget spent")
        return Result.failure(
            workDataOf(
                KEY_ERROR to applicationContext.getString(R.string.model_download_paused_time_limit),
                KEY_ERROR_CODE to NO_HTTP_CODE,
            ),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
        fileName = inputData.getString(KEY_FILE_NAME).orEmpty(),
        progress = 0,
    )

    /**
     * Promotes the worker to a foreground service, or updates the already-shown
     * notification with [progress].
     *
     * Best-effort by design: when the app is deep in the background the system
     * may forbid the foreground-service start
     * (`ForegroundServiceStartNotAllowedException`, an [IllegalStateException]).
     * The download then continues within the ordinary background quota rather
     * than failing outright — degraded, but not dead.
     */
    private suspend fun promoteToForeground(fileName: String, progress: Int) {
        try {
            setForeground(foregroundInfo(fileName, progress))
        } catch (e: CancellationException) {
            // CancellationException is an IllegalStateException subtype — it has
            // to leave before the broad catch, or a worker stop would be eaten.
            throw e
        } catch (e: IllegalStateException) {
            Timber.w(e, "Model download could not enter foreground; continuing within background quota.")
        }
    }

    /**
     * Builds the ongoing download notification: determinate progress, a Cancel
     * action wired to WorkManager's own cancellation intent, and the `dataSync`
     * service type — the type the platform defines for exactly this work.
     */
    private fun foregroundInfo(fileName: String, progress: Int): ForegroundInfo {
        ensureChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.MODEL_DOWNLOAD)
            .setContentTitle(applicationContext.getString(R.string.notifications_model_download_title))
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT_MAX, progress, false)
            .setContentIntent(AgentForegroundNotification.launchContentIntent(applicationContext))
            .addAction(
                0,
                applicationContext.getString(R.string.notifications_model_download_cancel),
                cancelIntent,
            )
            .build()
        return ForegroundInfo(
            NotificationIds.MODEL_DOWNLOAD,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** Registers the download channel; `createNotificationChannel` is idempotent. */
    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(
            NotificationChannels.MODEL_DOWNLOAD,
            NotificationManager.IMPORTANCE_LOW,
        )
            .setName(applicationContext.getString(R.string.notifications_model_download_channel_name))
            .build()
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
    }

    companion object {
        /** Input: direct URL of the model file. */
        const val KEY_URL = "url"

        /** Input: desired local file name. */
        const val KEY_FILE_NAME = "file_name"

        /**
         * Input: whether to send the stored Hugging Face token. The token
         * itself never travels through worker input — see the class KDoc.
         */
        const val KEY_USE_STORED_AUTH = "use_stored_auth"

        /** Progress: whole percent, 0..100. */
        const val KEY_PROGRESS = "progress"

        /** Output: absolute path of the finished file. */
        const val KEY_OUTPUT_PATH = "output_path"

        /** Output: failure message surfaced to the user. */
        const val KEY_ERROR = "error"

        /** Output: HTTP status behind the failure, or [NO_HTTP_CODE]. */
        const val KEY_ERROR_CODE = "error_code"

        /** Sentinel for "the failure carried no HTTP status" (a transport error). */
        const val NO_HTTP_CODE = -1

        /**
         * Attempts (including the first) before a transport failure that moved
         * no bytes is final.
         */
        private const val MAX_ATTEMPTS = 3

        private const val PERCENT_MAX = 100
    }
}
