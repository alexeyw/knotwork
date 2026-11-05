package app.knotwork.android.presentation.ui.settings.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.data.network.AndroidModelDownloadManager.DownloadError
import app.knotwork.android.domain.backup.BackupRestorer
import app.knotwork.android.domain.backup.RestoreReport
import app.knotwork.android.domain.constants.ModelDiscoveryConstants
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.models.ModelDownloadFailure
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.design.screens.settings.backup.AfterRestoreDownloadFailure
import app.knotwork.design.screens.settings.backup.AfterRestoreModel
import app.knotwork.design.screens.settings.backup.AfterRestoreState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * What the app tells the user the first time it opens after a restore: a sheet over the chat with the models to
 * download again, the keys to enter again — when the report has neither [RestoreReport.secretsApplied] nor
 * [RestoreReport.secretsOnPhone] — and the tasks that were not carried; or, when nothing is left to do, the snackbar
 * *Restored*.
 *
 * The report is read once and dismissed on *Done*; until then the sheet shows each time the chat is resumed
 * ([onResumed]) and hides when it pauses ([onPaused]). A back gesture that previews the chat starts it without
 * resuming it, so no sheet appears under the gesture to take it over. Each time the chat is resumed, the models
 * now on the phone — installed meanwhile from Discover or the Models screen — leave the list. A download started
 * here is the download worker's, as on the Models screen: it goes on after the sheet closes, and the worker
 * registers the file. One runs at a time. It sends the Hugging Face token when one is saved on this phone, as
 * Discover does, so a model from an access-gated repository downloads again. A download that stops without a model
 * says why on its row: a failure from the stream of a download started here, and a pause or a checksum mismatch from
 * the record the download keeps of it — read, never marked shown, so its notice and the Models screen still tell it,
 * and the row's reason leaves with the record: when the download starts again anywhere, or another screen has shown
 * it.
 *
 * @property restorer Reads and dismisses the report of the restore applied last.
 * @property downloads Starts, follows and cancels a model's download.
 * @property generationSettings Tells whether a Hugging Face token is saved on this phone.
 * @property localModels Tells which of the report's models are on the phone now.
 * @property relay Shows *Restored* in the app's snackbar.
 * @property appContext Reads the snackbar's text.
 * @property formatDate Formats when the backup was made; the device's medium date and short time by default.
 */
@HiltViewModel
class AfterRestoreViewModel internal constructor(
    private val restorer: BackupRestorer,
    private val downloads: ModelDownloadManager,
    private val generationSettings: GenerationSettings,
    private val localModels: LocalModelRepository,
    private val relay: TransientMessageRelay,
    private val appContext: Context,
    private val formatDate: (Long) -> String,
) : ViewModel() {

    /** Formats dates for the device's locale and time zone. */
    @Inject
    constructor(
        restorer: BackupRestorer,
        downloads: ModelDownloadManager,
        generationSettings: GenerationSettings,
        localModels: LocalModelRepository,
        relay: TransientMessageRelay,
        @ApplicationContext appContext: Context,
    ) : this(restorer, downloads, generationSettings, localModels, relay, appContext, ::deviceDate)

    private val _state = MutableStateFlow<AfterRestoreState?>(null)

    /** The sheet, or `null` when there is none to show. */
    val state: StateFlow<AfterRestoreState?> = _state.asStateFlow()

    private var report: RestoreReport? = null
    private var shown = false

    /** Counts resumes and pauses: a resume's check shows the sheet only if nothing came after it. */
    private var visibility = 0

    /** The report's models downloaded from this sheet; their rows leave for good. */
    private var downloadedHere = emptySet<String>()

    /**
     * The report's models found on the phone at the last check — installed meanwhile from Discover or the Models
     * screen. Replaced whole by each check, so a model deleted again comes back to the list.
     */
    private var foundOnPhone = emptySet<String>()
    private var running: Pair<String, Int>? = null
    private var downloadJob: Job? = null

    /**
     * Why the last download of each model started here failed, from its stream; a model leaves it when its next
     * download starts here, when a record of a later stop arrives, or when it is found on the phone.
     */
    private var failures = emptyMap<String, AfterRestoreDownloadFailure>()

    /** The pauses and checksum mismatches the downloads keep a record of, by file name — while the report is open. */
    private var recordedStops = emptyMap<String, AfterRestoreDownloadFailure>()
    private var recordedStopsJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    /**
     * *Download* on a model whose source the backup recorded. The Hugging Face token goes with it when one is saved
     * on this phone — the downloader sends it only to `huggingface.co`.
     *
     * @param name The model's file name.
     */
    fun onDownload(name: String) {
        val entry = report?.modelsToDownload?.firstOrNull { it.name == name } ?: return
        val url = entry.sourceUrl ?: return
        if (downloadJob?.isActive == true) return
        running = name to 0
        failures = failures - name
        render()
        downloadJob = viewModelScope.launch {
            downloads.downloadModel(url, name, useStoredAuth = tokenSaved())
                .onEach { state ->
                    when (state) {
                        DownloadState.Pending -> running = name to 0
                        is DownloadState.Downloading -> running = name to state.progress
                        is DownloadState.Success -> {
                            running = null
                            downloadedHere = downloadedHere + name
                        }
                        is DownloadState.Error -> {
                            running = null
                            failureOf(state, url)?.let { failures = failures + (name to it) }
                        }
                    }
                    render()
                }
                // A download cancelled from its notification ends with no last state: the row is free again.
                .onCompletion {
                    if (running?.first == name) {
                        running = null
                        render()
                    }
                }
                .collect()
        }
    }

    /**
     * Cancels the running download of a model.
     *
     * @param name The model's file name.
     */
    fun onCancelDownload(name: String) {
        if (running?.first != name) return
        downloads.cancelDownload(name)
        downloadJob?.cancel()
        downloadJob = null
        running = null
        render()
    }

    /**
     * The chat is resumed: the sheet shows, without the models installed since it last showed — checked before it
     * shows, so a model just installed from Discover is not listed again for a moment.
     */
    fun onResumed() {
        val resume = ++visibility
        viewModelScope.launch {
            report?.let { checkModelsOnPhone(it) }
            if (resume != visibility) return@launch
            shown = true
            render()
        }
    }

    /** The chat pauses — left for another screen, or behind a back gesture's preview: the sheet hides. */
    fun onPaused() {
        visibility++
        shown = false
        render()
    }

    /** *Done*, or the sheet dismissed: the report is not shown again. A running download goes on. */
    fun onDone() {
        _state.value = null
        report = null
        recordedStopsJob?.cancel()
        viewModelScope.launch { restorer.dismissReport() }
    }

    private suspend fun load() {
        val loaded = try {
            restorer.report()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unreadable report is not worth the chat: nothing is shown.
            Timber.w(e, "The restore report could not be read")
            null
        } ?: return
        // A model installed in an earlier process, while this report waited for *Done*, is not listed again.
        checkModelsOnPhone(loaded)
        val leftToDo = loaded.modelsToDownload.any { it.name !in foundOnPhone } ||
            keysToEnter(loaded) ||
            loaded.scheduledTasksLeftBehind > 0
        if (!leftToDo) {
            relay.post(UiText.Dynamic(appContext.getString(R.string.backup_snack_restored)))
            restorer.dismissReport()
            return
        }
        report = loaded
        followRecordedStops()
        render()
    }

    /**
     * Reads the downloads' records of a pause or a checksum mismatch while the report is open — read only: marking one
     * shown is left to the screens that show it once, so the record, and with it the row's reason, lasts until the
     * download starts again or one of those screens has shown it.
     */
    private fun followRecordedStops() {
        recordedStopsJob = downloads.observeInterruptedDownloads()
            .onEach { stops ->
                recordedStops = stops.mapNotNull { stop ->
                    when (stop.failure) {
                        ModelDownloadFailure.PAUSED_TIME_LIMIT -> stop.fileName to AfterRestoreDownloadFailure.Paused
                        ModelDownloadFailure.CHECKSUM_MISMATCH ->
                            stop.fileName to AfterRestoreDownloadFailure.ChecksumMismatch
                        ModelDownloadFailure.OTHER -> null
                    }
                }.toMap()
                // A record is newer than any failure this sheet's stream reported for the same model.
                failures = failures - recordedStops.keys
                render()
            }
            .launchIn(viewModelScope)
    }

    /**
     * Replaces [foundOnPhone] with the report's models on the phone now. A model downloaded here is left to
     * [downloadedHere], which this never writes — a download that ends while this reads stays off the list — and a
     * model whose download is running keeps its row.
     */
    private suspend fun checkModelsOnPhone(current: RestoreReport) {
        foundOnPhone = current.modelsToDownload
            .map { it.name }
            .filter { name -> name !in downloadedHere && running?.first != name && isOnPhone(name) }
            .toSet()
        // A model installed elsewhere leaves with its reason: deleted again, its row comes back clean.
        failures = failures - foundOnPhone
    }

    /**
     * Whether a Hugging Face token is saved on this phone — only whether: the downloader reads the token itself, so
     * its value never travels through the download's input. A token that cannot be read counts as none.
     */
    private suspend fun tokenSaved(): Boolean = try {
        !generationSettings.huggingFaceAuthToken.first().isNullOrBlank()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Whether a Hugging Face token is saved could not be read")
        false
    }

    /** A model's reason on its row: none while it downloads here, else its recorded stop, else its stream's failure. */
    private fun failureShown(name: String): AfterRestoreDownloadFailure? =
        if (running?.first == name) null else recordedStops[name] ?: failures[name]

    private suspend fun isOnPhone(name: String): Boolean = try {
        localModels.isInstalled(name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unknown counts as absent: the row stays, and its button still leads to the model.
        Timber.w(e, "Whether a restored model is on the phone could not be read")
        false
    }

    private fun render() {
        val current = report ?: return
        if (!shown) {
            _state.value = null
            return
        }
        _state.value = AfterRestoreState(
            madeOnLabel = current.backupCreatedAtEpochMillis?.let(formatDate),
            models = current.modelsToDownload
                .filterNot { it.name in downloadedHere || it.name in foundOnPhone }
                .map { model ->
                    AfterRestoreModel(
                        name = model.name,
                        sizeBytes = model.sizeBytes,
                        sourceKnown = model.sourceUrl != null,
                        progress = running?.takeIf { it.first == model.name }?.second,
                        canDownload = running == null,
                        failure = failureShown(model.name),
                    )
                },
            enterKeysAgain = keysToEnter(current),
            tasksLeftBehind = current.scheduledTasksLeftBehind > 0,
        )
    }

    private companion object {
        /**
         * What the row of a download that failed says, from the download's own stream: a 401 or 403 from an address
         * on Hugging Face may want a token — the only address the token is sent to — and anything else is a failure,
         * worded with both ways on. A pause or a checksum mismatch says nothing here: the download recorded it, and
         * the row reads that record.
         *
         * @param state The failure.
         * @param url The address the download was started from.
         * @return The row's reason, or `null` for a stop the download recorded.
         */
        fun failureOf(state: DownloadState.Error, url: String): AfterRestoreDownloadFailure? {
            val error = state.error as? DownloadError ?: return AfterRestoreDownloadFailure.Failed
            return when {
                error.recordedInterruption -> null
                error.accessRefused && ModelDiscoveryConstants.isHuggingFaceUrl(url) ->
                    AfterRestoreDownloadFailure.NeedsAccess
                else -> AfterRestoreDownloadFailure.Failed
            }
        }

        /** Keys are left to enter when the report has neither of its two secrets flags. */
        fun keysToEnter(report: RestoreReport): Boolean = !report.secretsApplied && !report.secretsOnPhone

        fun deviceDate(epochMillis: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
    }
}
