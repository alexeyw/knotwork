package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.usecases.GetSystemPromptVariableCatalogUseCase
import app.knotwork.android.domain.usecases.ResetSamplingDefaultsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Generation category delegate of [SettingsViewModel].
 *
 * Owns the system-instructions textarea (plus its `$VARIABLE` catalog chips),
 * the four sampling parameters (temperature / top-K / top-P / max context
 * length) and the voice-input capture length. Observes the persisted flows into the shared [state]
 * and routes edits back through [generationSettings]. Shares the ViewModel's
 * [scope] and single [SettingsUiState] reducer.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property appContext Application context for snackbar copy.
 * @property generationSettings Persistence for the generation settings.
 * @property getSystemPromptVariableCatalogUseCase Source of the `$VARIABLE` chips.
 * @property resetSamplingDefaultsUseCase Restores the sampling params to defaults.
 */
class GenerationSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val appContext: Context,
    private val generationSettings: GenerationSettings,
    private val getSystemPromptVariableCatalogUseCase: GetSystemPromptVariableCatalogUseCase,
    private val resetSamplingDefaultsUseCase: ResetSamplingDefaultsUseCase,
) {

    init {
        loadVariableCatalog()

        generationSettings.systemPromptPrefix.onEach { value ->
            state.update { it.copy(systemInstructions = value) }
        }.launchIn(scope)

        generationSettings.temperature.onEach { value ->
            state.update { it.copy(temperature = value) }
        }.launchIn(scope)
        generationSettings.topK.onEach { value ->
            state.update { it.copy(topK = value) }
        }.launchIn(scope)
        generationSettings.topP.onEach { value ->
            state.update { it.copy(topP = value) }
        }.launchIn(scope)
        generationSettings.maxContextLength.onEach { value ->
            state.update { it.copy(maxContextLength = value) }
        }.launchIn(scope)
        generationSettings.audioMaxDurationSec.onEach { value ->
            state.update { it.copy(audioMaxDurationSec = value) }
        }.launchIn(scope)
    }

    private fun loadVariableCatalog() {
        scope.launch {
            val entries = getSystemPromptVariableCatalogUseCase()
                .map { VariableCatalogChip(it.placeholder, it.sample) }
            state.update { it.copy(variableCatalog = entries) }
        }
    }

    /** Persists the user's system-instructions prefix. */
    fun updateSystemInstructions(value: String) {
        scope.launch { generationSettings.setSystemPromptPrefix(value) }
    }

    /** Appends [placeholder] to the current instructions (space-separated) and persists. */
    fun insertVariable(placeholder: String) {
        val current = state.value.systemInstructions
        val updated = if (current.endsWith(' ') || current.isEmpty()) {
            "$current$placeholder"
        } else {
            "$current $placeholder"
        }
        updateSystemInstructions(updated)
    }

    /** Persists the sampling temperature. */
    fun setTemperature(value: Float) {
        scope.launch { generationSettings.setTemperature(value) }
    }

    /** Persists the top-K sampling bound. */
    fun setTopK(value: Int) {
        scope.launch { generationSettings.setTopK(value) }
    }

    /** Persists the top-P (nucleus) sampling bound. */
    fun setTopP(value: Float) {
        scope.launch { generationSettings.setTopP(value) }
    }

    /** Persists the maximum context length (tokens). */
    fun setMaxContextLength(value: Int) {
        scope.launch { generationSettings.setMaxContextLength(value) }
    }

    /** Persists the maximum voice-input capture length (seconds). */
    fun setAudioMaxDurationSec(seconds: Int) {
        scope.launch { generationSettings.setAudioMaxDurationSec(seconds) }
    }

    /** Restores every sampling parameter to its recommended default and surfaces a snackbar. */
    fun resetSamplingDefaults() {
        scope.launch {
            resetSamplingDefaultsUseCase()
            state.update { it.copy(snackbarMessage = appContext.getString(R.string.settings_llm_reset_defaults)) }
        }
    }
}
