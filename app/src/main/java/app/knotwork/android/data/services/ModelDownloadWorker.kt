package app.knotwork.android.data.services

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
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
import app.knotwork.android.domain.models.ModelDownloadFailure
import app.knotwork.android.domain.repositories.GenerationSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

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
 * Installation happens here too, for the same reason the transfer does: a
 * download that survives the screen must leave behind a model the app knows
 * about, not an anonymous file on disk. [DownloadedModelInstaller] checks the
 * finished file against the SHA-256 its source published first; a file that
 * fails is deleted, never registered, and the failure says why.
 *
 * **Android's daily limit.** A `dataSync` foreground service may run six hours
 * a day while the app is out of sight, and one still running when that ends
 * crashes the app. The worker therefore spends at most what
 * [DownloadForegroundBudget] allows and then pauses, reporting a failure the
 * screen shows; starting the download again resumes it from the partial file.
 *
 * **Telling a stop nobody watched.** A pause comes after hours out of sight, and
 * a checksum mismatch at the end of a transfer the user has usually left. For
 * both, the worker records why it stopped ([DownloadInterruptionStore]) — a
 * screen opened later shows it — and posts a notice
 * ([ModelDownloadOutcomeNotifier]) before it returns.
 *
 * @property downloader Performs the resumable transfer.
 * @property generationSettings Source of the stored Hugging Face token.
 * @property installer Checks the finished file against its source's hash and
 *   installs it in the local model store.
 * @property foregroundBudget Foreground time the download may still spend
 *   before it pauses, so it never reaches Android's `dataSync` limit.
 * @property interruptions Keeps why a download stopped until a screen shows it.
 * @property outcomeNotifier Posts the notice that a download stopped.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloader: ResumableFileDownloader,
    private val generationSettings: GenerationSettings,
    private val installer: DownloadedModelInstaller,
    private val foregroundBudget: DownloadForegroundBudget,
    private val interruptions: DownloadInterruptionStore,
    private val outcomeNotifier: ModelDownloadOutcomeNotifier,
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
     *   [Result.failure] carrying the message the UI shows — including the pause
     *   and a file that failed its checksum.
     */
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val fileName = inputData.getString(KEY_FILE_NAME)
        if (url.isNullOrBlank() || fileName.isNullOrBlank()) {
            return Result.failure(
                workDataOf(KEY_ERROR to applicationContext.getString(R.string.model_download_failed_request)),
            )
        }
        if (foregroundBudget.isExhausted()) {
            return pausedByTimeLimit(url, fileName)
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
     * Promotes the worker to the foreground, transfers the file and installs it,
     * booking the foreground time on [meter] as progress arrives.
     *
     * The budget bounds the transfer only. The check that follows reads the file
     * once — seconds — and its time is booked by the caller's last reading of
     * [meter]; stopping it at the budget would leave a complete file that the
     * next attempt has to read again anyway.
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
        } ?: return pausedByTimeLimit(url, fileName)

        return when (outcome) {
            is ResumableFileDownloader.Outcome.Downloaded -> install(outcome, fileName, url)
            is ResumableFileDownloader.Outcome.Failure -> failOrRetry(outcome)
        }
    }

    /**
     * Installs a complete download, or reports why it was not installed.
     *
     * A file that failed its checksum is final: it was deleted, and downloading
     * it again from the same source is the user's call. A file that could not be
     * read or moved is still on disk and gets another attempt: from Hugging Face
     * that attempt finds it complete and only repeats the check; from a host that
     * names no length it downloads the file again. That attempt is decided by the
     * same rule as a transport failure. A failed check counts the bytes this
     * attempt fetched — a transfer that just finished is never given up on its
     * first failed check, however many runs the transfer took, and the attempt
     * after it finds the part complete and fetches nothing. A failed rename counts
     * none: from a host that names no length every attempt downloads the file
     * again, and crediting those bytes would repeat a multi-gigabyte download for
     * as long as the rename keeps failing.
     */
    private suspend fun install(
        downloaded: ResumableFileDownloader.Outcome.Downloaded,
        fileName: String,
        url: String,
    ): Result = when (val installed = installer.install(downloaded, fileName = fileName, sourceUrl = url)) {
        is DownloadedModelInstaller.Result.Installed -> Result.success(workDataOf(KEY_OUTPUT_PATH to installed.path))
        is DownloadedModelInstaller.Result.Rejected -> stopped(
            url = url,
            fileName = fileName,
            failure = ModelDownloadFailure.CHECKSUM_MISMATCH,
            message = applicationContext.getString(R.string.model_download_checksum_mismatch),
        )
        is DownloadedModelInstaller.Result.NotInstalled ->
            failOrRetry(
                ResumableFileDownloader.Outcome.Failure(
                    message = applicationContext.getString(notInstalledText(installed.cause)),
                    bytesTransferred = when (installed.cause) {
                        DownloadedModelInstaller.Result.Cause.UNREADABLE -> downloaded.bytesTransferred
                        DownloadedModelInstaller.Result.Cause.NOT_MOVED -> 0L
                    },
                ),
            )
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
                    KEY_ERROR to failureText(failure),
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

    /** The words for a downloaded file the installer could not install, by the step that stopped it. */
    @StringRes
    private fun notInstalledText(cause: DownloadedModelInstaller.Result.Cause): Int = when (cause) {
        DownloadedModelInstaller.Result.Cause.UNREADABLE -> R.string.model_download_failed_unreadable
        DownloadedModelInstaller.Result.Cause.NOT_MOVED -> R.string.model_download_failed_not_moved
    }

    /**
     * What the screens show for a download that failed for good, in the interface language
     * of the moment: a server's answer by its status, anything else as the failure reads — the
     * transport's own text — or as an unknown failure when it reads nothing.
     */
    private fun failureText(failure: ResumableFileDownloader.Outcome.Failure): String = when {
        failure.httpCode != null -> applicationContext.getString(R.string.model_download_failed_http, failure.httpCode)
        failure.message.isNotBlank() -> failure.message
        else -> applicationContext.getString(R.string.model_download_failed_unknown)
    }

    /**
     * Ends the attempt as paused: the foreground time budget is spent. Reported
     * as a failure so the screen shows why the download stopped; starting it
     * again resets the budget and resumes from the partial file.
     *
     * @param url Direct URL of the model file, which starting it again needs.
     * @param fileName Target file name.
     * @return A failure carrying the pause message and no HTTP status.
     */
    private fun pausedByTimeLimit(url: String, fileName: String): Result {
        Timber.i("Model download paused: foreground time budget spent")
        return stopped(
            url = url,
            fileName = fileName,
            failure = ModelDownloadFailure.PAUSED_TIME_LIMIT,
            message = applicationContext.getString(R.string.model_download_paused_time_limit),
        )
    }

    /**
     * Ends the attempt for a reason the user has to be told even if no screen watches: records
     * it for the screen opened next, posts the notice, and fails with it.
     *
     * Recorded before the failure is returned, so a screen that is watching finds the record by
     * the time it sees the failure.
     *
     * @param url Direct URL of the model file.
     * @param fileName Target file name.
     * @param failure Why the download stopped.
     * @param message The sentence a screen that words no failure of its own shows.
     * @return A failure carrying [message], [failure] and no HTTP status.
     */
    private fun stopped(url: String, fileName: String, failure: ModelDownloadFailure, message: String): Result {
        interruptions.record(fileName = fileName, url = url, failure = failure, message = message)
        outcomeNotifier.notifyStopped(fileName, failure)
        return Result.failure(
            workDataOf(
                KEY_ERROR to message,
                KEY_ERROR_CODE to NO_HTTP_CODE,
                KEY_FAILURE to failure.name,
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
        ModelDownloadChannel.ensure(applicationContext)
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

        /**
         * Output: the [ModelDownloadFailure] name of a failure a screen words on its
         * own (a pause, a checksum mismatch); absent for any other failure.
         */
        const val KEY_FAILURE = "failure"

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
