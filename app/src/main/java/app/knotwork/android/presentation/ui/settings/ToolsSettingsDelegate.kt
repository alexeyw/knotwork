package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tools-&-workspace category delegate of [SettingsViewModel].
 *
 * Owns the HITL approval policy, the two safety guardrails (block destructive
 * tools / block network from the local model) and the five tool / workspace
 * ceilings. Observes their persisted flows into the shared [state] and routes
 * edits back through [settingsRepository]. Shares the ViewModel's [scope] and single
 * [SettingsUiState] reducer (the `ChatHome*Delegate` pattern).
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property settingsRepository Persistence for the tool-restriction toggles.
 */
class ToolsSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val settingsRepository: SettingsRepository,
) {

    init {
        settingsRepository.toolApprovalPolicy.onEach { value ->
            state.update { it.copy(toolApprovalPolicy = value) }
        }.launchIn(scope)

        settingsRepository.blockDestructiveTools.onEach { value ->
            state.update { it.copy(blockDestructiveTools = value) }
        }.launchIn(scope)

        settingsRepository.blockNetworkFromLocalModel.onEach { value ->
            state.update { it.copy(blockNetworkFromLocalModel = value) }
        }.launchIn(scope)

        settingsRepository.toolCallTimeoutMs.onEach { value ->
            state.update { it.copy(toolCallTimeoutMs = value) }
        }.launchIn(scope)

        settingsRepository.workspaceMaxFileSizeBytes.onEach { value ->
            state.update { it.copy(workspaceMaxFileSizeBytes = value) }
        }.launchIn(scope)

        settingsRepository.workspaceMaxTotalBytes.onEach { value ->
            state.update { it.copy(workspaceMaxTotalBytes = value) }
        }.launchIn(scope)

        settingsRepository.workspaceReadTokenBudget.onEach { value ->
            state.update { it.copy(workspaceReadTokenBudget = value) }
        }.launchIn(scope)

        settingsRepository.httpToolMaxResponseBytes.onEach { value ->
            state.update { it.copy(httpToolMaxResponseBytes = value) }
        }.launchIn(scope)
    }

    /** Persists the Human-in-the-Loop approval policy. */
    fun setToolApprovalPolicy(policy: ToolApprovalPolicy) {
        scope.launch { settingsRepository.setToolApprovalPolicy(policy) }
    }

    /** Persists the "refuse destructive tool calls outright" guardrail. */
    fun setBlockDestructiveTools(blocked: Boolean) {
        scope.launch { settingsRepository.setBlockDestructiveTools(blocked) }
    }

    /** Persists the "local model may not reach the network" guardrail. */
    fun setBlockNetworkFromLocalModel(blocked: Boolean) {
        scope.launch { settingsRepository.setBlockNetworkFromLocalModel(blocked) }
    }

    /** Persists the per-tool-call wall-clock deadline, in milliseconds. */
    fun setToolCallTimeoutMs(timeoutMs: Long) {
        scope.launch { settingsRepository.setToolCallTimeoutMs(timeoutMs) }
    }

    /** Persists the per-file workspace size ceiling, in bytes. */
    fun setWorkspaceMaxFileSizeBytes(bytes: Long) {
        scope.launch { settingsRepository.setWorkspaceMaxFileSizeBytes(bytes) }
    }

    /** Persists the workspace-wide size ceiling, in bytes. */
    fun setWorkspaceMaxTotalBytes(bytes: Long) {
        scope.launch { settingsRepository.setWorkspaceMaxTotalBytes(bytes) }
    }

    /** Persists the token budget a single `read_file` call may return. */
    fun setWorkspaceReadTokenBudget(tokens: Int) {
        scope.launch { settingsRepository.setWorkspaceReadTokenBudget(tokens) }
    }

    /** Persists the ceiling on an `http_request` response body, in bytes. */
    fun setHttpToolMaxResponseBytes(bytes: Long) {
        scope.launch { settingsRepository.setHttpToolMaxResponseBytes(bytes) }
    }
}
