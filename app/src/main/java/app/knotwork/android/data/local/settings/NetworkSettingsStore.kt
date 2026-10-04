package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [NetworkSettings] over the app's preferences DataStore: local-only mode ("Block network from local
 * model"), the origins the user agreed to reach without encryption, and the retry budget of a cloud
 * call.
 *
 * The approved origins are the user's grants and are never written by a reset.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class NetworkSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : NetworkSettings {

    private object Keys {
        val APPROVED_CLEARTEXT_ORIGINS = stringSetPreferencesKey("approved_cleartext_origins")
        val CLOUD_RETRY_MAX_ATTEMPTS = intPreferencesKey("cloud_retry_max_attempts")
        val CLOUD_RETRY_BASE_DELAY_MS = longPreferencesKey("cloud_retry_base_delay_ms")
        val BLOCK_NETWORK_FROM_LOCAL_MODEL = booleanPreferencesKey("block_network_from_local_model")
    }

    override val approvedCleartextOrigins: Flow<Set<String>> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.APPROVED_CLEARTEXT_ORIGINS] ?: emptySet()
        }

    override suspend fun approveCleartextOrigin(origin: String) {
        dataStore.edit { preferences ->
            val current = preferences[Keys.APPROVED_CLEARTEXT_ORIGINS] ?: emptySet()
            preferences[Keys.APPROVED_CLEARTEXT_ORIGINS] = current + origin
        }
    }

    override val cloudRetryMaxAttempts: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CLOUD_RETRY_MAX_ATTEMPTS]
                ?: SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT
        }

    override suspend fun setCloudRetryMaxAttempts(attempts: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.CLOUD_RETRY_MAX_ATTEMPTS] = attempts.coerceIn(
                SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MIN,
                SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MAX,
            )
        }
    }

    override val cloudRetryBaseDelayMs: Flow<Long> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CLOUD_RETRY_BASE_DELAY_MS]
                ?: SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT
        }

    override suspend fun setCloudRetryBaseDelayMs(delayMs: Long) {
        dataStore.edit { preferences ->
            preferences[Keys.CLOUD_RETRY_BASE_DELAY_MS] = delayMs.coerceIn(
                SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MIN,
                SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MAX,
            )
        }
    }

    override val blockNetworkFromLocalModel: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.BLOCK_NETWORK_FROM_LOCAL_MODEL]
                ?: SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT
        }

    override suspend fun setBlockNetworkFromLocalModel(blocked: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.BLOCK_NETWORK_FROM_LOCAL_MODEL] = blocked
        }
    }

    /**
     * Writes the cloud-retry defaults into [preferences]. Part of both resets — the LLM-parameters
     * card's "Reset to defaults" carries the retry budget with the sampling parameters — so the two
     * cannot drift.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeSamplingDefaults(preferences: MutablePreferences) {
        preferences[Keys.CLOUD_RETRY_MAX_ATTEMPTS] = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT
        preferences[Keys.CLOUD_RETRY_BASE_DELAY_MS] = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT
    }

    /**
     * Writes every recommended default of this section into [preferences], as part of the one atomic
     * edit of "Reset all settings": the retry budget and local-only mode. The approved unencrypted
     * origins are the user's grants and stay: revoking one silently would break a working local
     * setup, and re-granting one silently would be worse.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        writeSamplingDefaults(preferences)
        preferences[Keys.BLOCK_NETWORK_FROM_LOCAL_MODEL] = SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT
    }
}
