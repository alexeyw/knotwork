package app.knotwork.android.presentation.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.usecases.automation.ExportExternalAutomationJournalUseCase
import app.knotwork.android.presentation.ui.common.EXTERNAL_REQUESTS_EXPORT_STEM
import app.knotwork.android.presentation.ui.common.JournalExportDelegate
import app.knotwork.android.presentation.ui.common.JournalExportEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel of the external-automation request journal.
 *
 * Read-only by design: the screen shows history and posture, and every control
 * that could change the posture lives one level up on the Background settings
 * category. That keeps the app's most security-sensitive toggle a settings row
 * that search can find and deep-link into, and keeps this surface unable to
 * widen the attack surface it exists to report on.
 *
 * Read-only **of the posture**, that is. The one thing the screen does write is
 * a copy of its own history out of the app, on an explicit user action — which is
 * the opposite of widening an attack surface: it is what lets the owner of a
 * silent automation profile prove what reached the app.
 *
 * @param entryPointSettings Source of the master switch and the surface binding.
 * @param pipelineRepository Resolves the bound pipeline id to its display name.
 * @param journal The external-request journal store.
 * @param exportJournal Renders the journal into its export document.
 */
@HiltViewModel
class ExternalAutomationJournalViewModel @Inject constructor(
    entryPointSettings: EntryPointSettings,
    pipelineRepository: PipelineRepository,
    journal: ExternalAutomationJournalRepository,
    exportJournal: ExportExternalAutomationJournalUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalAutomationJournalUiState())

    /** Live journal + posture snapshot consumed by the screen. */
    val uiState: StateFlow<ExternalAutomationJournalUiState> = _uiState.asStateFlow()

    /**
     * The journal export half of this screen. Reads the journal afresh rather
     * than exporting [uiState]'s copy: the state is a projection kept for
     * rendering, and an export must be a snapshot of the store, taken at the
     * moment the user asked for it.
     */
    val journalExport: JournalExportDelegate = JournalExportDelegate(
        scope = viewModelScope,
        fileNameStem = EXTERNAL_REQUESTS_EXPORT_STEM,
        buildDocument = { label -> exportJournal(label) },
    )

    /** One-shot journal-export outcomes, rendered by the screen. */
    val journalExportEvents: SharedFlow<JournalExportEvent> get() = journalExport.events

    init {
        entryPointSettings.externalAutomationEnabled
            .onEach { enabled -> _uiState.update { it.copy(contractEnabled = enabled) } }
            .launchIn(viewModelScope)

        // The name is resolved rather than stored, so a binding whose pipeline was
        // deleted reads as unbound here exactly as the authorizer already treats
        // it — the screen never claims a pipeline the app could not actually run.
        combine(
            entryPointSettings.externalAutomationPipelineId,
            pipelineRepository.observePipelineNames(),
        ) { boundId, names -> boundId?.let(names::get) }
            .onEach { name -> _uiState.update { it.copy(boundPipelineName = name) } }
            .launchIn(viewModelScope)

        journal.observeAll()
            .onEach { entries -> _uiState.update { it.copy(entries = entries) } }
            .launchIn(viewModelScope)
    }
}
