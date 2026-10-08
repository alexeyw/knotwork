package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.crypto.SecureValueUnreadableException
import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.repositories.GenerationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GenerationSettings] over the app's preferences DataStore and the settings secret store: the
 * sampling parameters, the system-prompt prefix, the context window and the voice-input length; the
 * local backend with its crash-recovery breadcrumbs and the last backend probe; and the Hugging Face
 * access token.
 *
 * The token is a secret, so it is not kept in DataStore: it lives in [secretsStore] (AES-GCM under a
 * dedicated Android Keystore key in production), with the re-enterable-secret recovery policy — an
 * entry that cannot be decrypted is dropped and reported as unset. A token an earlier release kept in
 * plain DataStore is moved to the secret store on the first read and removed from DataStore.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`): the
 * in-memory token flow and the migration's mutex must exist once.
 *
 * @property dataStore The app's one preferences DataStore.
 * @property secretsStore The encrypted store holding the Hugging Face token.
 */
@Singleton
class GenerationSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretsStore: SecretStore,
) : GenerationSettings {

    private object Keys {
        val HUGGING_FACE_TOKEN = stringPreferencesKey("hugging_face_token")
        val MAX_CONTEXT_LENGTH = intPreferencesKey("max_context_length")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = floatPreferencesKey("top_p")
        val SYSTEM_PROMPT_PREFIX = stringPreferencesKey("system_prompt_prefix")
        val AUDIO_MAX_DURATION_SEC = intPreferencesKey("audio_max_duration_sec")
        val LOCAL_MODEL_BACKEND = stringPreferencesKey("local_model_backend")

        /**
         * Sentinel persisted right before a non-CPU LiteRT backend init is
         * attempted and cleared when the init returns successfully. If a
         * subsequent cold-start still sees this key set, the previous attempt
         * crashed the process during native init (e.g. GPU/NPU dispatch
         * library missing) — `LiteRTLlmEngine.initialize` then falls back
         * to CPU automatically.
         */
        val LAST_INIT_BACKEND_ATTEMPT = stringPreferencesKey("last_init_backend_attempt")

        /**
         * Consecutive cold starts that found [LAST_INIT_BACKEND_ATTEMPT] still
         * set. Absent means zero — a permanent downgrade needs corroboration
         * across two starts, not one unexplained process death.
         */
        val LOCAL_BACKEND_FAILURE_STREAK = intPreferencesKey("local_backend_failure_streak")
        val LAST_TEST_PROBE_RESULT = stringPreferencesKey("last_test_probe_result")
    }

    /**
     * Entry names inside [secretsStore]. Distinct from [Keys]: these are slots in the Keystore-backed
     * encrypted store, not DataStore preference keys.
     */
    private object SecretKeys {
        const val HUGGING_FACE_TOKEN = "hugging_face_token"
    }

    /**
     * In-memory mirror of the encrypted HuggingFace-token entry. Initialized lazily from the
     * encrypted store with the re-enterable-secret policy applied; updated by
     * [setHuggingFaceAuthToken] and by the one-time legacy migration.
     */
    private val huggingFaceTokenFlow by lazy { MutableStateFlow(readHuggingFaceTokenOrNull()) }

    /** Serializes the legacy-DataStore migration so concurrent collectors run it once. */
    private val huggingFaceMigrationMutex = Mutex()
    private var huggingFaceMigrationDone = false

    override val huggingFaceAuthToken: Flow<String?> = flow {
        migrateLegacyHuggingFaceToken()
        emitAll(huggingFaceTokenFlow)
    }

    override suspend fun setHuggingFaceAuthToken(token: String?) {
        if (token == null) {
            secretsStore.remove(SecretKeys.HUGGING_FACE_TOKEN)
        } else {
            secretsStore.putString(SecretKeys.HUGGING_FACE_TOKEN, token)
        }
        huggingFaceTokenFlow.value = token
        // Any explicit write supersedes whatever a pre-migration release left in DataStore;
        // dropping the legacy key here also makes the one-time migration a no-op.
        dataStore.edit { preferences ->
            preferences.remove(Keys.HUGGING_FACE_TOKEN)
        }
    }

    /**
     * Reads the encrypted token entry, applying the re-enterable-secret recovery policy:
     * an entry that cannot be decrypted is dropped and reported as absent (the user pastes
     * the token again), never propagated as an error.
     */
    private fun readHuggingFaceTokenOrNull(): String? = try {
        secretsStore.getString(SecretKeys.HUGGING_FACE_TOKEN)
    } catch (e: SecureValueUnreadableException) {
        Timber.e(e, "Stored HuggingFace token is unreadable; treating it as unset.")
        secretsStore.remove(SecretKeys.HUGGING_FACE_TOKEN)
        null
    }

    /**
     * One-time move of a token persisted by earlier releases in plain DataStore into the
     * encrypted store. The encrypted copy is committed synchronously **before** the legacy
     * entry is removed, so a crash in between leaves both copies rather than neither; if the
     * encrypted store already holds a token, the legacy leftover is just deleted. An
     * [IOException] while reading DataStore defers the migration to the next collection
     * instead of failing the flow.
     */
    private suspend fun migrateLegacyHuggingFaceToken() {
        if (huggingFaceMigrationDone) return
        huggingFaceMigrationMutex.withLock {
            if (huggingFaceMigrationDone) return
            val legacyToken = try {
                dataStore.data.first()[Keys.HUGGING_FACE_TOKEN]
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.e(e, "Cannot read preferences for the HuggingFace token migration; retrying later.")
                return
            }
            if (legacyToken != null) {
                if (huggingFaceTokenFlow.value == null) {
                    secretsStore.putString(SecretKeys.HUGGING_FACE_TOKEN, legacyToken, synchronous = true)
                    huggingFaceTokenFlow.value = legacyToken
                    Timber.i("Migrated the HuggingFace token from plain DataStore to the encrypted store.")
                }
                dataStore.edit { preferences ->
                    preferences.remove(Keys.HUGGING_FACE_TOKEN)
                }
            }
            huggingFaceMigrationDone = true
        }
    }

    override val maxContextLength: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.MAX_CONTEXT_LENGTH] ?: SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT
        }

    override suspend fun setMaxContextLength(length: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.MAX_CONTEXT_LENGTH] = length
        }
    }

    override val temperature: Flow<Float> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.TEMPERATURE] ?: SettingsDefaults.TEMPERATURE_DEFAULT
        }

    override suspend fun setTemperature(temperature: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.TEMPERATURE] = temperature
        }
    }

    override val topK: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.TOP_K] ?: SettingsDefaults.TOP_K_DEFAULT
        }

    override suspend fun setTopK(topK: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.TOP_K] = topK
        }
    }

    override val topP: Flow<Float> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.TOP_P] ?: SettingsDefaults.TOP_P_DEFAULT
        }

    override suspend fun setTopP(topP: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.TOP_P] = topP
        }
    }

    override val systemPromptPrefix: Flow<String> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.SYSTEM_PROMPT_PREFIX] ?: DefaultPrompts.SYSTEM_PROMPT_PREFIX
        }

    override suspend fun setSystemPromptPrefix(prompt: String) {
        dataStore.edit { preferences ->
            preferences[Keys.SYSTEM_PROMPT_PREFIX] = prompt
        }
    }

    override val audioMaxDurationSec: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.AUDIO_MAX_DURATION_SEC]
                ?: SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT
        }

    override suspend fun setAudioMaxDurationSec(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.AUDIO_MAX_DURATION_SEC] = seconds
        }
    }

    override val localModelBackend: Flow<String> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            // A withdrawn backend reads as CPU — what it runs on — so no screen shows
            // a choice the app no longer offers. An unknown key passes through as before.
            preferences[Keys.LOCAL_MODEL_BACKEND]
                ?.takeUnless { LocalBackend.fromKey(it)?.offered == false }
                ?: LocalBackend.CPU.key
        }

    override val localModelBackendPreference: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences -> preferences[Keys.LOCAL_MODEL_BACKEND] }

    override suspend fun setLocalModelBackend(backend: String) {
        dataStore.edit { preferences ->
            preferences[Keys.LOCAL_MODEL_BACKEND] = backend
        }
    }

    override val lastInitBackendAttempt: Flow<String?> = dataStore.preferencesOrEmpty()
        .map { preferences -> preferences[Keys.LAST_INIT_BACKEND_ATTEMPT] }

    override suspend fun setLastInitBackendAttempt(backendKey: String?) {
        dataStore.edit { preferences ->
            if (backendKey == null) {
                preferences.remove(Keys.LAST_INIT_BACKEND_ATTEMPT)
            } else {
                preferences[Keys.LAST_INIT_BACKEND_ATTEMPT] = backendKey
            }
        }
    }

    override val localBackendFailureStreak: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences -> preferences[Keys.LOCAL_BACKEND_FAILURE_STREAK] ?: 0 }

    override suspend fun setLocalBackendFailureStreak(streak: Int) {
        dataStore.edit { preferences ->
            if (streak <= 0) {
                preferences.remove(Keys.LOCAL_BACKEND_FAILURE_STREAK)
            } else {
                preferences[Keys.LOCAL_BACKEND_FAILURE_STREAK] = streak
            }
        }
    }

    override val lastTestProbeResult: Flow<TestProbeResult?> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            decodeTestProbeResult(preferences[Keys.LAST_TEST_PROBE_RESULT])
        }

    override suspend fun setLastTestProbeResult(result: TestProbeResult?) {
        dataStore.edit { preferences ->
            if (result == null) {
                preferences.remove(Keys.LAST_TEST_PROBE_RESULT)
            } else {
                preferences[Keys.LAST_TEST_PROBE_RESULT] = encodeTestProbeResult(result)
            }
        }
    }

    /**
     * Writes the sampling defaults of this section — temperature, top-K, top-P and the context
     * window — into [preferences]. Part of both resets: the LLM-parameters card's "Reset to
     * defaults" and "Reset all settings", which share it so the two cannot drift.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeSamplingDefaults(preferences: MutablePreferences) {
        preferences[Keys.TEMPERATURE] = SettingsDefaults.TEMPERATURE_DEFAULT
        preferences[Keys.TOP_K] = SettingsDefaults.TOP_K_DEFAULT
        preferences[Keys.TOP_P] = SettingsDefaults.TOP_P_DEFAULT
        preferences[Keys.MAX_CONTEXT_LENGTH] = SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT
    }

    /**
     * Writes every recommended default of this section into [preferences], as part of the one atomic
     * edit of "Reset all settings": the sampling defaults and the voice-input length. The prompt
     * prefix, the backend choice with its breadcrumbs, the probe result and the token are the user's
     * and stay untouched.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        writeSamplingDefaults(preferences)
        preferences[Keys.AUDIO_MAX_DURATION_SEC] = SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT
    }

    private fun encodeTestProbeResult(result: TestProbeResult): String = JSONObject().apply {
        put("tokens", result.tokensGenerated)
        put("durationMs", result.durationMs)
        put("timestampMs", result.timestampMs)
        put("success", result.success)
        if (result.errorMessage != null) put("error", result.errorMessage)
    }.toString()

    private fun decodeTestProbeResult(raw: String?): TestProbeResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            TestProbeResult(
                tokensGenerated = json.optInt("tokens", 0),
                durationMs = json.optLong("durationMs", 0L),
                timestampMs = json.optLong("timestampMs", 0L),
                success = json.optBoolean("success", false),
                errorMessage = json.optString("error", "").takeIf { it.isNotBlank() },
            )
        } catch (e: JSONException) {
            Timber.w(e, "Failed to parse last_test_probe_result — clearing")
            null
        }
    }
}
