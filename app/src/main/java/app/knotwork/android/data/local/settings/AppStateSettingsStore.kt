package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.domain.repositories.AppStateSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppStateSettings] over the app's preferences DataStore: the first-launch and onboarding flags, the
 * open chat and the preferred console tab. None of it is a tunable preference, so nothing here takes
 * part in a reset.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class AppStateSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : AppStateSettings {

    private object Keys {
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val CURRENT_CHAT_SESSION_ID = stringPreferencesKey("current_chat_session_id")
        val CONSOLE_PREFERRED_TAB = stringPreferencesKey("console_preferred_tab")
    }

    override val isFirstLaunch: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.IS_FIRST_LAUNCH] ?: true
        }

    override suspend fun setFirstLaunch(isFirstLaunch: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.IS_FIRST_LAUNCH] = isFirstLaunch
        }
    }

    override val hasCompletedOnboarding: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.HAS_COMPLETED_ONBOARDING] ?: false
        }

    override suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.HAS_COMPLETED_ONBOARDING] = completed
        }
    }

    override val currentChatSessionId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CURRENT_CHAT_SESSION_ID]
        }

    override suspend fun setCurrentChatSessionId(sessionId: String?) {
        dataStore.edit { preferences ->
            if (sessionId == null) {
                preferences.remove(Keys.CURRENT_CHAT_SESSION_ID)
            } else {
                preferences[Keys.CURRENT_CHAT_SESSION_ID] = sessionId
            }
        }
    }

    override val consolePreferredConsoleTabName: Flow<String> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CONSOLE_PREFERRED_TAB] ?: CONSOLE_PREFERRED_TAB_DEFAULT
        }

    override suspend fun setConsolePreferredConsoleTabName(name: String) {
        dataStore.edit { preferences ->
            preferences[Keys.CONSOLE_PREFERRED_TAB] = name
        }
    }

    private companion object {
        /**
         * Default value for [Keys.CONSOLE_PREFERRED_TAB] on a fresh install. Mirrors the enum name of
         * `app.knotwork.design.components.console.ConsoleTab.Logs` — kept as a raw string so this
         * data-layer constant stays free of the `:catalog` dependency.
         */
        const val CONSOLE_PREFERRED_TAB_DEFAULT = "Logs"
    }
}
