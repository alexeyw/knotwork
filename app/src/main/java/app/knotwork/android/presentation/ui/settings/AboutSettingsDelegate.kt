package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.repositories.AppLanguageRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.usecases.ResetToRecommendedDefaultsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * About category delegate of [SettingsViewModel].
 *
 * Loads the read-only identity card on construction, mirrors the app's
 * interface language into the state for the language row, and owns the
 * "Reset all settings" action. Shares the ViewModel's [scope] and single
 * [SettingsUiState] reducer.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property appContext Application context for the identity placeholder + snackbar copy.
 * @property identityRepository Source of the identity-card snapshot.
 * @property appLanguageRepository The app's interface language — read for the
 *   language row, written by the picker.
 * @property resetToRecommendedDefaultsUseCase Single atomic write restoring every
 *   tunable preference to its recommended default.
 */
class AboutSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val appContext: Context,
    private val identityRepository: IdentityRepository,
    private val appLanguageRepository: AppLanguageRepository,
    private val resetToRecommendedDefaultsUseCase: ResetToRecommendedDefaultsUseCase,
) {

    init {
        loadIdentity()
        observeLanguage()
    }

    /**
     * Picks the app's interface language. The system re-creates the screen in it;
     * the state follows through [observeLanguage].
     *
     * @param languageTag A tag from [SettingsUiState.supportedLanguages], or
     *   `null` to follow the device's language.
     */
    fun selectLanguage(languageTag: String?) {
        appLanguageRepository.select(languageTag)
    }

    private fun observeLanguage() {
        state.update { it.copy(supportedLanguages = appLanguageRepository.supportedLanguages()) }
        scope.launch {
            appLanguageRepository.selectedLanguage.collect { tag -> state.update { it.copy(appLanguage = tag) } }
        }
        // The identity card's label is resolved from resources, and the ViewModel
        // outlives the screen's re-creation in the new language: resolve it again
        // when the interface language changes. Not on `selectedLanguage`: a pick
        // changes that at once, before the system has applied the language, so the
        // label would be read in the old one and nothing would read it again.
        scope.launch {
            appLanguageRepository.interfaceLanguage.drop(1).collect { loadIdentity() }
        }
    }

    private fun loadIdentity() {
        scope.launch {
            val anonymous = appContext.getString(R.string.settings_identity_display_name)
            val identity = identityRepository.getIdentity(anonymous)
            state.update { it.copy(identity = identity) }
        }
    }

    /**
     * Restores every tunable preference to its recommended default via
     * [ResetToRecommendedDefaultsUseCase] (a single atomic write), then mirrors
     * the reset crash-reporting consent into [CrashReportingRepository] so
     * Crashlytics collection actually stops — the persisted flag alone does not
     * flip the live collector. User data (memory, chats, pipelines, prompts,
     * connections, secrets) is deliberately left untouched; the scope contract
     * lives on `SettingsReset.resetToRecommendedDefaults`.
     */
    fun performResetSettings() {
        scope.launch {
            // The reset writes the consent default too; the app's observer turns
            // the collector off from the persisted flag.
            resetToRecommendedDefaultsUseCase()
            state.update { it.copy(snackbarMessage = appContext.getString(R.string.settings_reset_button)) }
        }
    }
}
