package app.knotwork.android.data.network

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.knotwork.android.data.services.DownloadForegroundBudget
import app.knotwork.android.data.services.DownloadInterruptionStore
import app.knotwork.android.data.services.ModelDownloadOutcomeNotifier
import app.knotwork.android.data.services.ModelDownloadWorker
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.ModelDownloadFailure
import app.knotwork.android.domain.repositories.ActiveDownload
import app.knotwork.android.domain.repositories.InterruptedDownload
import app.knotwork.android.domain.repositories.ModelDownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import javax.inject.Inject

/**
 * [ModelDownloadManager] that hands the transfer to [ModelDownloadWorker] and
 * reports its progress back as a [DownloadState] stream.
 *
 * The indirection is the point. A model bundle takes minutes to fetch, and the
 * user will leave the screen — to finish onboarding, to look around the app, or
 * simply because the phone locked. Running the transfer inside the collecting
 * coroutine tied its life to a ViewModel; running it as background work tied to
 * a foreground service does not.
 *
 * Work is keyed uniquely per target file with [ExistingWorkPolicy.KEEP], so
 * calling this again for a download already in flight **attaches** to it rather
 * than starting a second copy — which is exactly what re-entering the screen
 * should do.
 *
 * @property workManager Schedules and observes the download work.
 * @property foregroundBudget Foreground time downloads may spend before they
 *   pause; started over by [downloadModel].
 * @property interruptions Why a download stopped, kept until a screen shows it;
 *   starting or cancelling the download drops it.
 * @property outcomeNotifier The notice that a download stopped, removed with its record.
 */
class AndroidModelDownloadManager @Inject constructor(
    private val workManager: WorkManager,
    private val foregroundBudget: DownloadForegroundBudget,
    private val interruptions: DownloadInterruptionStore,
    private val outcomeNotifier: ModelDownloadOutcomeNotifier,
) : ModelDownloadManager {

    override fun downloadModel(url: String, fileName: String, useStoredAuth: Boolean): Flow<DownloadState> = flow {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to url,
                    ModelDownloadWorker.KEY_FILE_NAME to fileName,
                    ModelDownloadWorker.KEY_USE_STORED_AUTH to useStoredAuth,
                ),
            )
            // Retries resume rather than restart, so waiting for any connection
            // is strictly better than failing on a momentary drop.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Tagged so a screen that was not around when the download started
            // can still find it — the file name rides along, because `WorkInfo`
            // exposes tags but not input data.
            .addTag(TAG_MODEL_DOWNLOAD)
            .addTag(fileNameTag(fileName))
            .build()
        // Every caller is a user action on a visible screen, and Android resets
        // its own daily `dataSync` count while the app is on screen — so the
        // download's foreground budget starts over here too. This is also how a
        // paused download continues: starting it again lands here.
        foregroundBudget.reset()
        // Starting it again answers why it stopped: the pause it continues, or the
        // mismatch it downloads past.
        forgetInterruption(fileName)
        workManager.enqueueUniqueWork(uniqueWorkName(fileName), ExistingWorkPolicy.KEEP, request)

        emitAll(observeUniqueWork(fileName, requireLive = false))
    }

    override fun observeDownload(fileName: String): Flow<DownloadState> =
        observeUniqueWork(fileName, requireLive = true)

    override fun observeActiveDownload(): Flow<ActiveDownload?> {
        var reachedPercent = 0
        return workManager.getWorkInfosByTagFlow(TAG_MODEL_DOWNLOAD)
            .map { infos ->
                val live = infos.firstOrNull { !it.state.isFinished } ?: return@map null
                val fileName = live.fileNameFromTags() ?: return@map null
                val state = live.toDownloadState(reachedPercent) ?: return@map null
                if (state is DownloadState.Downloading) reachedPercent = state.progress
                ActiveDownload(fileName = fileName, state = state)
            }
            .distinctUntilChanged()
    }

    override fun cancelDownload(fileName: String) {
        workManager.cancelUniqueWork(uniqueWorkName(fileName))
        forgetInterruption(fileName)
    }

    override fun observeInterruptedDownloads(): Flow<List<InterruptedDownload>> = interruptions.observe()

    override fun acknowledgeInterruption(fileName: String) = forgetInterruption(fileName)

    /** Drops why the download of [fileName] stopped, and its notice. */
    private fun forgetInterruption(fileName: String) {
        interruptions.clear(fileName)
        outcomeNotifier.dismiss(fileName)
    }

    /**
     * Streams the state of the work registered under [fileName].
     *
     * @param requireLive `true` for the observe-only entry points: if no
     *   unfinished work exists at subscription time the flow completes without
     *   emitting, so restoring a screen never resurrects a download that already
     *   ended — and never invents one that never started.
     */
    private fun observeUniqueWork(fileName: String, requireLive: Boolean): Flow<DownloadState> = flow {
        // Highest percent seen so far. WorkManager clears a worker's progress
        // every time it stops it — losing the network does exactly that — and the
        // bytes are still on disk waiting to be resumed, so rewinding the bar to
        // zero would report a loss that did not happen.
        var reachedPercent = 0
        var attached = !requireLive

        emitAll(
            workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(fileName))
                .transformWhile { infos ->
                    // A live run wins over any finished one still attached to
                    // the name: the list order is unspecified, and reading a
                    // previous download's Success would end this stream on the
                    // spot with the wrong answer.
                    val live = infos.firstOrNull { !it.state.isFinished }
                    if (!attached) {
                        if (live == null) return@transformWhile false
                        attached = true
                    }
                    val info = live ?: infos.lastOrNull()
                    val state = info?.toDownloadState(reachedPercent)
                    if (state is DownloadState.Downloading) reachedPercent = state.progress
                    state?.let { emit(it) }
                    // A missing WorkInfo means the observation raced the enqueue;
                    // keep listening rather than reporting a finished download.
                    info == null || !info.state.isFinished
                }
                .distinctUntilChanged(),
        )
    }

    /** Recovers the download's target file name from the tag it was given. */
    private fun WorkInfo.fileNameFromTags(): String? = tags
        .firstOrNull { it.startsWith(FILE_NAME_TAG_PREFIX) }
        ?.removePrefix(FILE_NAME_TAG_PREFIX)

    /**
     * Projects a [WorkInfo] onto the download state the UI speaks.
     *
     * @param reachedPercent Highest percent reported so far for this download.
     *   It is what a re-queued or freshly restarted worker is measured against:
     *   a transfer that lost the network keeps its bytes, so the figure the user
     *   sees must not fall back while the work waits or reconnects.
     * @return The state to emit, or `null` for a cancelled download — the
     *   collector simply stops, because the user who cancelled needs neither an
     *   error nor a success.
     */
    private fun WorkInfo.toDownloadState(reachedPercent: Int): DownloadState? = when (state) {
        // Enqueued *after* progress exists is a retry waiting on its network
        // constraint, not a download about to start from nothing.
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            if (reachedPercent > 0) DownloadState.Downloading(reachedPercent) else DownloadState.Pending
        // A running worker that has not reported a percent yet is either
        // connecting or resuming; either way the bar holds where it was.
        WorkInfo.State.RUNNING -> DownloadState.Downloading(
            maxOf(progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0), reachedPercent),
        )
        WorkInfo.State.SUCCEEDED -> outputData.getString(ModelDownloadWorker.KEY_OUTPUT_PATH)
            ?.let { DownloadState.Success(it) }
            // Blank: a screen words a failure without text in the interface language.
            ?: DownloadState.Error(DownloadError(""))
        WorkInfo.State.FAILED -> DownloadState.Error(
            DownloadError(
                message = outputData.getString(ModelDownloadWorker.KEY_ERROR).orEmpty(),
                code = outputData
                    .getInt(ModelDownloadWorker.KEY_ERROR_CODE, ModelDownloadWorker.NO_HTTP_CODE)
                    .takeIf { it != ModelDownloadWorker.NO_HTTP_CODE },
                failure = outputData.getString(ModelDownloadWorker.KEY_FAILURE)
                    .let { name -> ModelDownloadFailure.entries.firstOrNull { it.name == name } }
                    ?: ModelDownloadFailure.OTHER,
            ),
        )
        WorkInfo.State.CANCELLED -> null
    }

    /**
     * A simple implementation of [AppError.Network] for download failures.
     *
     * @property message The failure as the download worker worded it, in the interface
     *   language of the moment, or blank when nothing worded it — a screen then shows its
     *   own sentence for an unknown failure.
     * @property code The HTTP status code when the failure was a non-2xx
     *   response, or `null` for transport/IO failures. Carried as a typed
     *   field so consumers can branch on the status (e.g. an access-gated
     *   401/403) without parsing it back out of [message].
     * @property failure Why the download ended, where a screen words it on its
     *   own — a pause or a file that failed its checksum; [ModelDownloadFailure.OTHER]
     *   for everything [code] and [message] already describe.
     */
    data class DownloadError(
        val message: String,
        val code: Int? = null,
        val failure: ModelDownloadFailure = ModelDownloadFailure.OTHER,
    ) : AppError.Network {

        /**
         * Whether the source refused the download for access — HTTP 401 or 403, which is how an
         * access-gated Hugging Face repository answers a request without a token, or with one whose
         * account has not accepted the licence. A screen words this as "accept the licence and add a
         * token" rather than as a connection problem.
         */
        val accessRefused: Boolean
            get() = code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN

        /**
         * Whether the download worker recorded this stop before failing — a pause at the foreground
         * time limit, or a file that failed its checksum. A screen shows such a stop from that record,
         * once, so the screen that watched the download does not word it a second time.
         */
        val recordedInterruption: Boolean
            get() = failure == ModelDownloadFailure.PAUSED_TIME_LIMIT ||
                failure == ModelDownloadFailure.CHECKSUM_MISMATCH

        private companion object {
            /** Missing or invalid credentials. */
            const val HTTP_UNAUTHORIZED = 401

            /** Credentials accepted, access not granted — the licence not accepted. */
            const val HTTP_FORBIDDEN = 403
        }
    }

    private companion object {

        /** Tag every model download carries, so live ones can be enumerated. */
        const val TAG_MODEL_DOWNLOAD = "model-download"

        /** Prefix of the tag carrying the target file name. */
        const val FILE_NAME_TAG_PREFIX = "model-download-file:"

        /**
         * Unique-work name for a target file. Derived from the file name (not
         * the URL) because the file is what a second request would collide on.
         */
        fun uniqueWorkName(fileName: String): String = "model-download-$fileName"

        /** Tag carrying [fileName] through to `WorkInfo`, which drops input data. */
        fun fileNameTag(fileName: String): String = "$FILE_NAME_TAG_PREFIX$fileName"
    }
}
