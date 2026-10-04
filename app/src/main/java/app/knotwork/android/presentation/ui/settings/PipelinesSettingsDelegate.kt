package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pipelines-&-structured-output category delegate of [SettingsViewModel].
 *
 * Owns the PIPELINE-node nesting-depth cap and the structured-output repair
 * budget, and observes the two limits shown on the run-limits entry row. The
 * limits are no longer edited here — writing them belongs to the run-limits
 * screen, which owns all four together. Shares the ViewModel's [scope] and
 * single [SettingsUiState] reducer.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property runSettings Persistence for the pipeline step cap.
 */
class PipelinesSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val runSettings: RunSettings,
) {

    init {
        runSettings.pipelineMaxSteps.onEach { value ->
            state.update { it.copy(capAutonomousSteps = value) }
        }.launchIn(scope)
        // Read, never written here: the entry row shows the current limits, and
        // the run-limits screen is where they are changed.
        runSettings.runMaxTokens.onEach { value ->
            state.update { it.copy(runMaxTokens = value) }
        }.launchIn(scope)
        runSettings.pipelineMaxNestingDepth.onEach { value ->
            state.update { it.copy(pipelineMaxNestingDepth = value) }
        }.launchIn(scope)
        runSettings.structuredOutputMaxRepairs.onEach { value ->
            state.update { it.copy(structuredOutputMaxRepairs = value) }
        }.launchIn(scope)
    }

    /**
     * Persists the maximum PIPELINE-node nesting depth. The repository coerces
     * the value into the sanctioned range.
     */
    fun setPipelineMaxNestingDepth(depth: Int) {
        scope.launch { runSettings.setPipelineMaxNestingDepth(depth) }
    }

    /**
     * Persists the structured-output repair budget. The repository coerces the
     * value into the sanctioned range.
     */
    fun setStructuredOutputMaxRepairs(count: Int) {
        scope.launch { runSettings.setStructuredOutputMaxRepairs(count) }
    }
}
