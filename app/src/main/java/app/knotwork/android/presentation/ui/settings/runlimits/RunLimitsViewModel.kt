package app.knotwork.android.presentation.ui.settings.runlimits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.RunSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the run-limits screen.
 *
 * The four numbers here are the *drafts* the sliders show. They track the
 * persisted values while the user is not dragging, and diverge from them
 * between the first frame of a gesture and its commit — which is the whole
 * reason they exist rather than the screen rendering the repository directly.
 *
 * @property steps Interactive step ceiling.
 * @property stepsBackground Step ceiling for runs nobody is watching. Equals
 *   [steps] while [stepsBackgroundInherited] is `true`.
 * @property stepsBackgroundInherited Whether the background step ceiling is
 *   following the interactive one rather than standing on its own.
 * @property tokens Interactive token ceiling.
 * @property tokensBackground Token ceiling for background runs. Never
 *   inherited — the token axis is new, so there was no earlier value for it to
 *   inherit from.
 */
data class RunLimitsUiState(
    val steps: Int = SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT,
    val stepsBackground: Int = SettingsDefaults.PIPELINE_MAX_STEPS_BACKGROUND_DEFAULT,
    val stepsBackgroundInherited: Boolean = true,
    val tokens: Int = SettingsDefaults.RUN_MAX_TOKENS_DEFAULT,
    val tokensBackground: Int = SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT,
)

/**
 * Owns the four autonomous-run ceilings.
 *
 * A screen of its own rather than a delegate of `SettingsViewModel`, following
 * the external-automation journal: the limits are one coherent subject with
 * their own surface, and the category screen keeps only an entry row.
 *
 * **Why the sliders commit on gesture end rather than on every frame.** For the
 * interactive rows it is merely tidy. For the background step row it is the
 * behaviour: while its key has never been written, the ceiling *follows* the
 * interactive one, so a user who raised that to 40 has background runs at 40
 * too. Writing the key ends that relationship permanently. Persisting on every
 * drag frame — or on a touch that lands and goes nowhere — would end it by
 * accident, which is the same defect as a reset writing the key (fixed in
 * `SettingsManager.applySamplingDefaults`, and for the same reason).
 *
 * @property runSettings Persistence for all four ceilings.
 */
@HiltViewModel
class RunLimitsViewModel @Inject constructor(private val runSettings: RunSettings) : ViewModel() {

    private val _state = MutableStateFlow(RunLimitsUiState())

    /** The screen's single source of truth. */
    val state: StateFlow<RunLimitsUiState> = _state.asStateFlow()

    init {
        runSettings.pipelineMaxSteps
            .onEach { value -> _state.update { it.copy(steps = value) } }
            .launchIn(viewModelScope)
        runSettings.pipelineMaxStepsBackground
            .onEach { value -> _state.update { it.copy(stepsBackground = value) } }
            .launchIn(viewModelScope)
        runSettings.pipelineMaxStepsBackgroundIsSet
            .onEach { isSet -> _state.update { it.copy(stepsBackgroundInherited = !isSet) } }
            .launchIn(viewModelScope)
        runSettings.runMaxTokens
            .onEach { value -> _state.update { it.copy(tokens = value) } }
            .launchIn(viewModelScope)
        runSettings.runMaxTokensBackground
            .onEach { value -> _state.update { it.copy(tokensBackground = value) } }
            .launchIn(viewModelScope)
    }

    /**
     * Moves the interactive step slider without persisting.
     *
     * @param value The dragged position.
     */
    fun onStepsChange(value: Int) = _state.update { it.copy(steps = value) }

    /** Persists the interactive step ceiling once the gesture ends. */
    fun onStepsCommit() {
        viewModelScope.launch { runSettings.setPipelineMaxSteps(_state.value.steps) }
    }

    /**
     * Moves the background step slider without persisting.
     *
     * Note what this does **not** do: it does not clear the inherited
     * qualifier. Dragging is not deciding, and the row keeps saying the value
     * is inherited until the gesture actually commits a different number.
     *
     * @param value The dragged position.
     */
    fun onStepsBackgroundChange(value: Int) = _state.update { it.copy(stepsBackground = value) }

    /**
     * Persists the background step ceiling — but only if the user actually
     * moved it somewhere new.
     *
     * The guard is the point. Writing the key is what detaches this ceiling
     * from the interactive one for good, so a touch that lands on the current
     * value and lifts must leave the inheritance intact.
     */
    fun onStepsBackgroundCommit() {
        val draft = _state.value.stepsBackground
        viewModelScope.launch {
            if (draft != runSettings.pipelineMaxStepsBackground.first()) {
                runSettings.setPipelineMaxStepsBackground(draft)
            }
        }
    }

    /**
     * Moves the interactive token slider without persisting.
     *
     * @param value The dragged position.
     */
    fun onTokensChange(value: Int) = _state.update { it.copy(tokens = value) }

    /** Persists the interactive token ceiling once the gesture ends. */
    fun onTokensCommit() {
        viewModelScope.launch { runSettings.setRunMaxTokens(_state.value.tokens) }
    }

    /**
     * Moves the background token slider without persisting.
     *
     * @param value The dragged position.
     */
    fun onTokensBackgroundChange(value: Int) = _state.update { it.copy(tokensBackground = value) }

    /** Persists the background token ceiling once the gesture ends. */
    fun onTokensBackgroundCommit() {
        viewModelScope.launch { runSettings.setRunMaxTokensBackground(_state.value.tokensBackground) }
    }
}
