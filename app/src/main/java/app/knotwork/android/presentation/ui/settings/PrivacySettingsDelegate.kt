package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Privacy category delegate of [SettingsViewModel].
 *
 * Owns crash-reporting consent and the two trace retention windows. Observes
 * their persisted flows into the shared [state] and routes edits back through
 * [settingsRepository]. Crash-reporting consent is **only** persisted here: the
 * app's observer (`App.observeCrashReportingOptIn`, installed in release builds of
 * the `full` flavour alone) mirrors the flag into the live collector. Flipping the
 * collector from here as well turned Crashlytics on in debug builds, which the
 * privacy policy says never happens. Shares the ViewModel's [scope] and single
 * [SettingsUiState] reducer.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property settingsRepository Persistence for the privacy settings.
 */
class PrivacySettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val settingsRepository: SettingsRepository,
) {

    init {
        settingsRepository.crashReportingEnabled.onEach { value ->
            state.update { it.copy(crashReportingEnabled = value) }
        }.launchIn(scope)

        settingsRepository.traceRetentionRunsPerSession.onEach { value ->
            state.update { it.copy(traceRetentionRunsPerSession = value) }
        }.launchIn(scope)

        settingsRepository.traceRetentionMaxAgeDays.onEach { value ->
            state.update { it.copy(traceRetentionMaxAgeDays = value) }
        }.launchIn(scope)
    }

    /**
     * Persists crash-reporting consent; the app's release-only observer turns the
     * live collector on or off from it.
     *
     * @param enabled Whether the user consents to crash reports.
     */
    fun setCrashReportingEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setCrashReportingEnabled(enabled) }
    }

    /**
     * Persists how many most-recent pipeline runs the retention pass keeps per
     * chat session. The repository coerces the value into the sanctioned 5–100
     * range.
     *
     * @param runs The new per-session count picked on the slider.
     */
    fun setTraceRetentionRunsPerSession(runs: Int) {
        scope.launch { settingsRepository.setTraceRetentionRunsPerSession(runs) }
    }

    /**
     * Persists the maximum age (days) a terminal pipeline run is kept before the
     * retention pass deletes it. The repository coerces the value into the
     * sanctioned 7–180 range.
     *
     * @param days The new age limit picked on the slider.
     */
    fun setTraceRetentionMaxAgeDays(days: Int) {
        scope.launch { settingsRepository.setTraceRetentionMaxAgeDays(days) }
    }
}
