package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.EntryPointSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EntryPointSettings] over the app's preferences DataStore: which pipeline each entry point runs,
 * the share target's one-chat preference and the external automation contract's master switch.
 *
 * The four pipeline ids are the user's bindings, never touched by a reset;
 * [writeRecommendedDefaults] writes only the two preferences beside them.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class EntryPointSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : EntryPointSettings {

    private object Keys {
        // The user's bindings: never written by a reset.
        val DEFAULT_PIPELINE_ID = stringPreferencesKey("default_pipeline_id")
        val SHARE_TARGET_PIPELINE_ID = stringPreferencesKey("share_target_pipeline_id")
        val QUICK_SETTINGS_TILE_PIPELINE_ID = stringPreferencesKey("quick_settings_tile_pipeline_id")
        val EXTERNAL_AUTOMATION_PIPELINE_ID = stringPreferencesKey("external_automation_pipeline_id")

        // Preferences: written back to their defaults by a reset.
        val SHARE_REUSE_SESSION = booleanPreferencesKey("share_reuse_session")
        val EXTERNAL_AUTOMATION_ENABLED = booleanPreferencesKey("external_automation_enabled")
    }

    override val defaultPipelineId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.DEFAULT_PIPELINE_ID]
        }

    override suspend fun setDefaultPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(Keys.DEFAULT_PIPELINE_ID)
            } else {
                preferences[Keys.DEFAULT_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val shareTargetPipelineId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.SHARE_TARGET_PIPELINE_ID]
        }

    override suspend fun setShareTargetPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(Keys.SHARE_TARGET_PIPELINE_ID)
            } else {
                preferences[Keys.SHARE_TARGET_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val shareReuseSession: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.SHARE_REUSE_SESSION]
                ?: SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT
        }

    override suspend fun setShareReuseSession(reuse: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.SHARE_REUSE_SESSION] = reuse
        }
    }

    override val quickSettingsTilePipelineId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.QUICK_SETTINGS_TILE_PIPELINE_ID]
        }

    override suspend fun setQuickSettingsTilePipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(Keys.QUICK_SETTINGS_TILE_PIPELINE_ID)
            } else {
                preferences[Keys.QUICK_SETTINGS_TILE_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val externalAutomationPipelineId: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.EXTERNAL_AUTOMATION_PIPELINE_ID]
        }

    override suspend fun setExternalAutomationPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(Keys.EXTERNAL_AUTOMATION_PIPELINE_ID)
            } else {
                preferences[Keys.EXTERNAL_AUTOMATION_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val externalAutomationEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.EXTERNAL_AUTOMATION_ENABLED]
                ?: SettingsDefaults.EXTERNAL_AUTOMATION_ENABLED_DEFAULT
        }

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.EXTERNAL_AUTOMATION_ENABLED] = enabled
        }
    }

    /**
     * Writes this section's recommended defaults into [preferences], as part of the one atomic edit
     * of "Reset all settings". The external-automation switch returns to off, the safe direction;
     * the pipeline bindings beside it are the user's and stay untouched.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        preferences[Keys.SHARE_REUSE_SESSION] = SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT
        preferences[Keys.EXTERNAL_AUTOMATION_ENABLED] = SettingsDefaults.EXTERNAL_AUTOMATION_ENABLED_DEFAULT
    }
}
