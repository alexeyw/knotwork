package app.knotwork.android.presentation.ui.settings.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.usecases.BuildUsageTelemetryExportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the on-device Usage statistics screen (Settings → Privacy).
 *
 * Observes the live [UsageTelemetryRepository.summary], the recording opt-in and
 * the pipeline catalogue (to resolve per-pipeline ids to names), and exposes the
 * voluntary export actions. **No path here makes a network call** — the share /
 * export simply renders the local figures the user already has.
 *
 * @property usageTelemetry Source of the on-device statistics + reset action.
 * @property privacySettings Owns the recording opt-in flag.
 * @property pipelineRepository Resolves `pipelineId → name` for the rows/export.
 * @property buildExport Pure renderer of the text / JSON export documents.
 */
@HiltViewModel
class UsageTelemetryViewModel @Inject constructor(
    private val usageTelemetry: UsageTelemetryRepository,
    private val privacySettings: PrivacySettings,
    private val pipelineRepository: PipelineRepository,
    private val buildExport: BuildUsageTelemetryExportUseCase,
) : ViewModel() {

    /** Live screen state aggregated from the counters, the opt-in and pipeline names. */
    val uiState: StateFlow<UsageTelemetryUiState> = combine(
        usageTelemetry.summary,
        privacySettings.usageTelemetryEnabled,
        pipelineRepository.observePipelineNames(),
    ) { summary, recordingEnabled, pipelineNames ->
        UsageTelemetryUiState(
            loading = false,
            recordingEnabled = recordingEnabled,
            summary = summary,
            pipelineNames = pipelineNames,
        )
    }
        // A repository read error must not strand the screen on the LOADING
        // spinner: surface a settled, empty state instead.
        .catch { e ->
            Timber.e(e, "Usage-statistics state read failed; showing empty state")
            emit(UsageTelemetryUiState.LOADING.copy(loading = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), UsageTelemetryUiState.LOADING)

    private val _events = MutableSharedFlow<UsageTelemetryEvent>(extraBufferCapacity = 1)

    /** One-shot events (share the rendered text through the system share sheet). */
    val events: SharedFlow<UsageTelemetryEvent> = _events

    /**
     * Flips the local-recording opt-in.
     *
     * @param enabled `true` to record local usage counters, `false` to stop.
     */
    fun setRecordingEnabled(enabled: Boolean) {
        viewModelScope.launch { privacySettings.setUsageTelemetryEnabled(enabled) }
    }

    /** Renders the current statistics to text and emits a share event. */
    fun shareAsText() {
        val state = uiState.value
        val export = buildExport(state.summary, state.pipelineNames, generatedAtLabel())
        _events.tryEmit(UsageTelemetryEvent.ShareText(export.text))
    }

    /**
     * Renders the current statistics to JSON and writes them to [outputStream]
     * (the document the user picked through the Storage Access Framework). The
     * stream is always closed; a write failure is logged, never surfaced as a
     * crash.
     *
     * @param outputStream Destination document stream (owned here; closed on exit).
     */
    fun writeJsonExport(outputStream: OutputStream) {
        val state = uiState.value
        viewModelScope.launch {
            val export = buildExport(state.summary, state.pipelineNames, generatedAtLabel())
            withContext(Dispatchers.IO) {
                try {
                    outputStream.use { it.write(export.json.toByteArray()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to write the usage-statistics JSON export")
                }
            }
        }
    }

    /** Clears every recorded counter, regardless of the recording opt-in. */
    fun reset() {
        viewModelScope.launch { usageTelemetry.reset() }
    }

    /** Device-local "generated at" label for the export header. */
    private fun generatedAtLabel(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern(GENERATED_AT_PATTERN, Locale.getDefault()))

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** Timestamp pattern for the export header; mirrors the string resource. */
        const val GENERATED_AT_PATTERN = "dd MMM yyyy HH:mm"
    }
}
