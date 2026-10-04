package app.knotwork.android.presentation.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.repositories.ToolSettings
import app.knotwork.android.domain.services.HttpRequestPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel backing the standalone Allowed-domains editor for the `http_request`
 * tool. Observes [ToolSettings.allowedHttpDomains] and owns the add /
 * remove gestures plus the live validation of the add field.
 *
 * Validation and normalisation are delegated to [HttpRequestPolicy] — the same
 * pure helper the tool's executor and risk lookup read — so what the editor
 * accepts and how it stores a host can never drift from what the tool enforces.
 */
@HiltViewModel
class AllowedDomainsViewModel @Inject constructor(private val toolSettings: ToolSettings) : ViewModel() {

    private val _uiState = MutableStateFlow(AllowedDomainsUiState())
    val uiState: StateFlow<AllowedDomainsUiState> = _uiState.asStateFlow()

    init {
        toolSettings.allowedHttpDomains
            .onEach { hosts ->
                _uiState.update { it.copy(hosts = hosts, addFeedback = feedbackFor(it.addInput, hosts)) }
            }
            .launchIn(viewModelScope)
    }

    /** The add-field text changed; recompute the preview/error feedback. */
    fun onAddInputChange(input: String) {
        _uiState.update { it.copy(addInput = input, addFeedback = feedbackFor(input, it.hosts)) }
    }

    /**
     * Commits the currently-previewed host. No-op unless the field holds a
     * valid, non-duplicate host (the catalog also disables the Add button in
     * any other state). Persists the appended list and clears the field.
     */
    fun onAddSubmit() {
        val current = _uiState.value
        val feedback = current.addFeedback
        if (feedback !is AddHostFeedback.Valid) return
        val updated = current.hosts + feedback.normalized
        persist(updated)
        _uiState.update { it.copy(addInput = "", addFeedback = AddHostFeedback.Idle) }
    }

    /** Removes [host] from the allowlist and persists the result. */
    fun onRemoveHost(host: String) {
        val updated = _uiState.value.hosts.filterNot { it == host }
        persist(updated)
    }

    private fun persist(hosts: List<String>) {
        viewModelScope.launch { toolSettings.setAllowedHttpDomains(hosts) }
    }

    /**
     * Maps a raw input + current list to the field feedback: blank → Idle,
     * un-normalisable → Invalid, normalises to an existing host → Duplicate,
     * otherwise the normalised host is previewed as Valid.
     */
    private fun feedbackFor(input: String, hosts: List<String>): AddHostFeedback {
        if (input.isBlank()) return AddHostFeedback.Idle
        val normalized = HttpRequestPolicy.normalizeDomain(input) ?: return AddHostFeedback.Invalid
        return if (hosts.any { it.equals(normalized, ignoreCase = true) }) {
            AddHostFeedback.Duplicate(normalized)
        } else {
            AddHostFeedback.Valid(normalized)
        }
    }
}
