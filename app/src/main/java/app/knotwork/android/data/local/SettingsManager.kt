package app.knotwork.android.data.local

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.crypto.SecureValueUnreadableException
import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.TestProbeResult
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.UpdateMcpServerResult
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Concrete implementation of [SettingsRepository] utilizing Androidx DataStore Preferences.
 *
 * Secret payloads are **not** kept in DataStore: the HuggingFace access token and per-server
 * MCP credentials live in the injected [SecretStore] (AES-GCM under a dedicated Android Keystore
 * key in production), with the same re-enterable-secret recovery policy as [ApiKeyManager] — an
 * undecryptable value is dropped and reported as unset. Secrets persisted by earlier releases in
 * plain DataStore (the HuggingFace token, inline MCP auth) are migrated into the secret store on
 * the first read and removed from DataStore.
 *
 * @property dataStore The underlying DataStore instance for persistence.
 * @property secretsStore The encrypted store backing every secret payload.
 */
@Suppress("LargeClass") // 31-field DataStore facade by design; per-section split planned post-v0.1.
class SettingsManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretsStore: SecretStore,
) : SettingsRepository {

    private object PreferencesKeys {
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val HUGGING_FACE_TOKEN = stringPreferencesKey("hugging_face_token")
        val MAX_CONTEXT_LENGTH = intPreferencesKey("max_context_length")
        val TEMPERATURE = androidx.datastore.preferences.core.floatPreferencesKey("temperature")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = androidx.datastore.preferences.core.floatPreferencesKey("top_p")

        /**
         * Legacy "ask before tool calls" boolean, superseded by [TOOL_APPROVAL_POLICY].
         * Read only by the policy migration, on every policy read while the policy
         * key is absent; never written.
         */
        val REQUIRES_USER_CONFIRMATION = booleanPreferencesKey("requires_user_confirmation")
        val SYSTEM_PROMPT_PREFIX = stringPreferencesKey("system_prompt_prefix")
        val MCP_SERVER_URLS = stringSetPreferencesKey("mcp_server_urls")
        val MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
        val DISABLED_APP_FUNCTIONS = stringSetPreferencesKey("disabled_app_functions")
        val DISABLED_MCP_TOOLS = stringSetPreferencesKey("disabled_mcp_tools")
        val APPROVED_CLEARTEXT_ORIGINS = stringSetPreferencesKey("approved_cleartext_origins")

        // The stored key keeps its original `app_function_risk_overrides` name even
        // though the map now also carries MCP entries: renaming the DataStore key
        // would silently drop every override a user had already set. The Kotlin
        // surface (`toolRiskOverrides`) is the honest name; this string is history.
        val TOOL_RISK_OVERRIDES = stringPreferencesKey("app_function_risk_overrides")
        val CURRENT_CHAT_SESSION_ID = stringPreferencesKey("current_chat_session_id")
        val MEMORY_LAST_COMPACTED_AT =
            androidx.datastore.preferences.core.longPreferencesKey("memory_last_compacted_at")
        val MEMORY_SEARCH_TOP_K = intPreferencesKey("memory_search_top_k")
        val CHAT_HISTORY_COMPRESSION_ENABLED = booleanPreferencesKey("chat_history_compression_enabled")
        val CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS =
            intPreferencesKey("chat_history_compression_threshold_tokens")
        val CHAT_HISTORY_LIVE_WINDOW_SIZE = intPreferencesKey("chat_history_live_window_size")
        val AUDIO_MAX_DURATION_SEC = intPreferencesKey("audio_max_duration_sec")
        val MEMORY_SEARCH_THRESHOLD =
            androidx.datastore.preferences.core.floatPreferencesKey("memory_search_threshold")
        val MEMORY_RECENCY_HALF_LIFE_DAYS = intPreferencesKey("memory_recency_half_life_days")
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
        val TOOL_CALL_TIMEOUT_MS = androidx.datastore.preferences.core.longPreferencesKey("tool_call_timeout_ms")
        val WORKSPACE_MAX_FILE_SIZE_BYTES =
            androidx.datastore.preferences.core.longPreferencesKey("workspace_max_file_size_bytes")
        val WORKSPACE_MAX_TOTAL_BYTES =
            androidx.datastore.preferences.core.longPreferencesKey("workspace_max_total_bytes")
        val WORKSPACE_READ_TOKEN_BUDGET = intPreferencesKey("workspace_read_token_budget")

        /**
         * Ordered `http_request` domain allowlist, stored newline-delimited
         * (a `stringSet` would lose the user's ordering). Normalised hosts never
         * contain a newline, so the delimiter is collision-free.
         */
        val ALLOWED_HTTP_DOMAINS = stringPreferencesKey("allowed_http_domains")
        val HTTP_TOOL_MAX_RESPONSE_BYTES =
            androidx.datastore.preferences.core.longPreferencesKey("http_tool_max_response_bytes")
        val PIPELINE_MAX_STEPS = intPreferencesKey("pipeline_max_steps")
        val PIPELINE_MAX_STEPS_BACKGROUND = intPreferencesKey("pipeline_max_steps_background")
        val RUN_MAX_TOKENS = intPreferencesKey("run_max_tokens")
        val RUN_MAX_TOKENS_BACKGROUND = intPreferencesKey("run_max_tokens_background")
        val PIPELINE_MAX_NESTING_DEPTH = intPreferencesKey("pipeline_max_nesting_depth")
        val STRUCTURED_OUTPUT_MAX_REPAIRS = intPreferencesKey("structured_output_max_repairs")
        val CLOUD_RETRY_MAX_ATTEMPTS = intPreferencesKey("cloud_retry_max_attempts")
        val CLOUD_RETRY_BASE_DELAY_MS =
            androidx.datastore.preferences.core.longPreferencesKey("cloud_retry_base_delay_ms")
        val RESUME_MAX_AGE_HOURS = intPreferencesKey("resume_max_age_hours")
        val BACKGROUND_APPROVAL_WINDOW_HOURS = intPreferencesKey("background_approval_window_hours")
        val TRACE_RETENTION_RUNS_PER_SESSION = intPreferencesKey("trace_retention_runs_per_session")
        val TRACE_RETENTION_MAX_AGE_DAYS = intPreferencesKey("trace_retention_max_age_days")
        val MEMORY_SUMMARY_DEFAULT_LIMIT = intPreferencesKey("memory_summary_default_limit")
        val DEFAULT_PIPELINE_ID = stringPreferencesKey("default_pipeline_id")

        // Per-surface entry-point pipeline bindings. User bindings (like
        // DEFAULT_PIPELINE_ID), so excluded from resetToRecommendedDefaults.
        val SHARE_TARGET_PIPELINE_ID = stringPreferencesKey("share_target_pipeline_id")
        val QUICK_SETTINGS_TILE_PIPELINE_ID = stringPreferencesKey("quick_settings_tile_pipeline_id")
        val EXTERNAL_AUTOMATION_PIPELINE_ID = stringPreferencesKey("external_automation_pipeline_id")

        // Tunable behaviour preference (reset by resetToRecommendedDefaults).
        val SHARE_REUSE_SESSION = booleanPreferencesKey("share_reuse_session")
        val EXTERNAL_AUTOMATION_ENABLED = booleanPreferencesKey("external_automation_enabled")
        val CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
        val USAGE_TELEMETRY_ENABLED = booleanPreferencesKey("usage_telemetry_enabled")
        val CONSOLE_PREFERRED_TAB = stringPreferencesKey("console_preferred_tab")

        // Settings redesign.
        val TOOL_APPROVAL_POLICY = stringPreferencesKey("tool_approval_policy")
        val BLOCK_DESTRUCTIVE_TOOLS = booleanPreferencesKey("block_destructive_tools")
        val BLOCK_NETWORK_FROM_LOCAL_MODEL = booleanPreferencesKey("block_network_from_local_model")
        val SCHEDULED_TASK_NOTIFICATIONS = booleanPreferencesKey("scheduled_task_notifications")
        val LAST_TEST_PROBE_RESULT = stringPreferencesKey("last_test_probe_result")

        // Embedding provider abstraction.
        val ACTIVE_EMBEDDING_PROVIDER_ID = stringPreferencesKey("active_embedding_provider_id")
        val LAST_REEMBED_PROVIDER_ID = stringPreferencesKey("last_reembed_provider_id")

        // Memory write auto-extraction.
        val AUTO_EXTRACT_ENABLED = booleanPreferencesKey("auto_extract_enabled")

        // Background memory compaction.
        val MEMORY_COMPACTION_ENABLED = booleanPreferencesKey("memory_compaction_enabled")
        val MEMORY_COMPACTION_AGE_DAYS = intPreferencesKey("memory_compaction_age_days")
        val MAX_MEMORY_CHUNKS = intPreferencesKey("max_memory_chunks")

        // Memory observability.
        val VERBOSE_MEMORY_LOGGING_ENABLED = booleanPreferencesKey("verbose_memory_logging_enabled")
    }

    /**
     * Entry names inside [secretsStore]. Distinct from [PreferencesKeys]: these are slots in
     * the Keystore-backed encrypted store, not DataStore preference keys.
     */
    private object SecretKeys {
        const val HUGGING_FACE_TOKEN = "hugging_face_token"

        /**
         * Encrypted-store key for a single MCP server's auth payload, namespaced
         * by a hash of the server URL. The URL is hashed (not used verbatim) so
         * the entry name does not leak the endpoint and stays a stable, valid
         * preference key regardless of the URL's characters.
         *
         * @param url The MCP server URL the auth belongs to.
         * @return The per-server secret-store entry name.
         */
        fun mcpAuthKey(url: String): String = "mcp_auth_" + sha256Hex(url)

        /**
         * Encrypted-store key for a single MCP server's custom request headers, namespaced by
         * the same URL hash as [mcpAuthKey]. A slot of its own rather than a field of the auth
         * payload, so auth entries written before headers moved here stay byte-identical and
         * each of the two is rewritten only when it changes.
         *
         * @param url The MCP server URL the headers belong to.
         * @return The per-server secret-store entry name.
         */
        fun mcpHeadersKey(url: String): String = "mcp_headers_" + sha256Hex(url)

        private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override val isFirstLaunch: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] ?: true
        }

    override suspend fun setFirstLaunch(isFirstLaunch: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] = isFirstLaunch
        }
    }

    override val hasCompletedOnboarding: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false
        }

    override suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = completed
        }
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
            preferences.remove(PreferencesKeys.HUGGING_FACE_TOKEN)
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
                dataStore.data.first()[PreferencesKeys.HUGGING_FACE_TOKEN]
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
                    preferences.remove(PreferencesKeys.HUGGING_FACE_TOKEN)
                }
            }
            huggingFaceMigrationDone = true
        }
    }

    override val maxContextLength: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MAX_CONTEXT_LENGTH] ?: SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT
        }

    override suspend fun setMaxContextLength(length: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_CONTEXT_LENGTH] = length
        }
    }

    override val temperature: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TEMPERATURE] ?: SettingsDefaults.TEMPERATURE_DEFAULT
        }

    override suspend fun setTemperature(temperature: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TEMPERATURE] = temperature
        }
    }

    override val topK: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TOP_K] ?: SettingsDefaults.TOP_K_DEFAULT
        }

    override suspend fun setTopK(topK: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOP_K] = topK
        }
    }

    override val topP: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TOP_P] ?: SettingsDefaults.TOP_P_DEFAULT
        }

    override suspend fun setTopP(topP: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOP_P] = topP
        }
    }

    override val systemPromptPrefix: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT_PREFIX] ?: DefaultPrompts.SYSTEM_PROMPT_PREFIX
        }

    override suspend fun setSystemPromptPrefix(prompt: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT_PREFIX] = prompt
        }
    }

    override val mcpServers: Flow<List<McpServerConfig>> = flow {
        // Move any inline auth or headers left in plain DataStore by earlier releases
        // into the encrypted store before exposing the list (one-time, idempotent).
        migrateLegacyMcpSecrets()
        emitAll(
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        Timber.e(exception, "Error reading preferences")
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map { preferences ->
                    val json = preferences[PreferencesKeys.MCP_SERVERS_JSON]
                    if (!json.isNullOrBlank()) {
                        decodeMcpServers(json)
                    } else {
                        // Legacy fallback: read the old URL-only key. The first write through
                        // [addMcpServer]/[updateMcpServer]/[removeMcpServer] persists the new
                        // JSON form and the next read short-circuits above.
                        (preferences[PreferencesKeys.MCP_SERVER_URLS] ?: emptySet())
                            .map { url -> McpServerConfig(url = url) }
                    }
                },
        )
    }

    override suspend fun addMcpServer(config: McpServerConfig) = mcpMutex.withLock {
        val previous = mcpServers.first()
        val next = previous.filterNot { it.url == config.url } + config
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MCP_SERVERS_JSON] = encodeMcpServers(next)
            preferences.remove(PreferencesKeys.MCP_SERVER_URLS)
        }
        reconcileMcpSecrets(previous, next)
    }

    override suspend fun updateMcpServer(originalUrl: String, updated: McpServerConfig): UpdateMcpServerResult =
        mcpMutex.withLock {
            // The whole read-modify-write runs under `mcpMutex`, so the collision
            // check and the edit see a consistent list and concurrent mutations
            // cannot interleave (the prior "transient race is acceptable" caveat
            // no longer applies).
            val snapshot = mcpServers.first()
            McpServerCollisionCheck
                .detectCollision(currentList = snapshot, originalUrl = originalUrl, newUrl = updated.url)
                ?.let { return@withLock it }
            val index = snapshot.indexOfFirst { it.url == originalUrl }
            val next = if (index >= 0) {
                snapshot.toMutableList().also { it[index] = updated }
            } else {
                snapshot + updated
            }
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.MCP_SERVERS_JSON] = encodeMcpServers(next)
                preferences.remove(PreferencesKeys.MCP_SERVER_URLS)
            }
            // Reconciling against the prior snapshot drops the old URL's secret on
            // a URL change and writes the credentials under the new URL only when
            // they changed.
            reconcileMcpSecrets(snapshot, next)
            UpdateMcpServerResult.Success
        }

    override suspend fun removeMcpServer(url: String) = mcpMutex.withLock {
        val previous = mcpServers.first()
        val next = previous.filterNot { it.url == url }
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MCP_SERVERS_JSON] = encodeMcpServers(next)
            preferences.remove(PreferencesKeys.MCP_SERVER_URLS)
        }
        reconcileMcpSecrets(previous, next)
    }

    /**
     * In-memory cache of decrypted MCP auth, keyed by server URL. `decodeMcpServers`
     * is run on every `mcpServers` emission by every consumer (cold flow), so
     * without this cache each emission would perform one Keystore AES-GCM decrypt
     * per configured server. The encrypted store stays the source of truth;
     * [writeMcpAuth] / [removeMcpSecrets] keep the cache coherent, and an
     * undecryptable read is deliberately NOT cached so it retries.
     */
    private val mcpAuthCache = ConcurrentHashMap<String, McpAuth>()

    /** The same cache as [mcpAuthCache], for each server's custom headers; same coherence rules. */
    private val mcpHeadersCache = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Serialises the read-modify-write of the server list and its secret
     * reconcile across [addMcpServer] / [updateMcpServer] / [removeMcpServer], so
     * two concurrent mutations cannot interleave their `mcpServers.first()` read
     * with another's `dataStore.edit` — which would drop a server from the JSON
     * and orphan its just-written secret.
     */
    private val mcpMutex = Mutex()

    /**
     * Reconciles the per-server MCP secrets — auth and custom headers — against a
     * settings change: removes both encrypted entries of every server dropped (or
     * whose URL changed), and (re)writes each secret **only for a server where that
     * secret actually changed** — so editing one server never rewrites (and cannot
     * clobber) another's.
     */
    private fun reconcileMcpSecrets(previous: List<McpServerConfig>, next: List<McpServerConfig>) {
        val nextUrls = next.mapTo(mutableSetOf()) { it.url }
        previous.forEach { config ->
            if (config.url !in nextUrls) removeMcpSecrets(config.url)
        }
        val previousByUrl = previous.associateBy { it.url }
        next.forEach { config ->
            val before = previousByUrl[config.url]
            if (before?.auth != config.auth) {
                writeMcpAuth(config.url, config.auth)
            }
            if (before?.headers != config.headers) {
                writeMcpHeaders(config.url, config.headers)
            }
        }
    }

    /** Persists (or clears, for [McpAuth.None]) a single server's auth in the encrypted store and the cache. */
    private fun writeMcpAuth(url: String, auth: McpAuth) {
        val key = SecretKeys.mcpAuthKey(url)
        val encoded = encodeAuth(auth)
        if (encoded == null) {
            secretsStore.remove(key)
        } else {
            secretsStore.putString(key, encoded.toString(), synchronous = true)
        }
        mcpAuthCache[url] = auth
    }

    /**
     * Persists (or clears, when empty) a single server's custom headers in the encrypted
     * store and the cache. Headers are stored encrypted as a whole: the form invites an
     * `Authorization` row, so any value in it may be a credential.
     */
    private fun writeMcpHeaders(url: String, headers: Map<String, String>) {
        val key = SecretKeys.mcpHeadersKey(url)
        if (headers.isEmpty()) {
            secretsStore.remove(key)
        } else {
            secretsStore.putString(key, JSONObject(headers).toString(), synchronous = true)
        }
        mcpHeadersCache[url] = headers
    }

    /** Removes a server's auth and headers from both the encrypted store and the caches. */
    private fun removeMcpSecrets(url: String) {
        secretsStore.remove(SecretKeys.mcpAuthKey(url))
        secretsStore.remove(SecretKeys.mcpHeadersKey(url))
        mcpAuthCache.remove(url)
        mcpHeadersCache.remove(url)
    }

    /**
     * Reads a server's auth from the cache or the encrypted store. A *corrupt*
     * (un-parseable) entry is reported as [McpAuth.None] and cached. An
     * *undecryptable* entry (e.g. a momentarily-locked Keystore) is reported as
     * [McpAuth.None] but **not** removed or cached, so a transient failure cannot
     * destroy a valid credential and a later read recovers it.
     */
    private fun readMcpAuth(url: String): McpAuth {
        mcpAuthCache[url]?.let { return it }
        val stored = readMcpSecret(SecretKeys.mcpAuthKey(url), "auth") ?: return McpAuth.None
        val auth = stored.plaintext?.let { parseStoredMcpSecret(it, "auth") }?.let(::decodeAuth) ?: McpAuth.None
        mcpAuthCache[url] = auth
        return auth
    }

    /**
     * Reads a server's custom headers from the cache or the encrypted store, with the
     * recovery policy of [readMcpAuth]: a corrupt entry reads as no headers and is cached,
     * an undecryptable one reads as no headers for now and is left in place.
     */
    private fun readMcpHeaders(url: String): Map<String, String> {
        mcpHeadersCache[url]?.let { return it }
        val stored = readMcpSecret(SecretKeys.mcpHeadersKey(url), "headers") ?: return emptyMap()
        val headers = stored.plaintext?.let { parseStoredMcpSecret(it, "headers") }?.let(::decodeHeaders).orEmpty()
        mcpHeadersCache[url] = headers
        return headers
    }

    /**
     * Reads one MCP secret entry.
     *
     * @return The stored entry — its [StoredMcpSecret.plaintext] is `null` when nothing is
     *   stored — or `null` when the entry cannot be decrypted (logged; the caller must not
     *   cache that answer, so a transient Keystore failure recovers on a later read).
     */
    private fun readMcpSecret(key: String, what: String): StoredMcpSecret? = try {
        StoredMcpSecret(secretsStore.getString(key))
    } catch (e: SecureValueUnreadableException) {
        Timber.e(e, "Stored MCP %s for a server is unreadable; treating it as absent for now.", what)
        null
    }

    /**
     * A readable MCP secret entry, told apart from an undecryptable one by [readMcpSecret]
     * returning `null` for the latter.
     *
     * @property plaintext The decrypted value, or `null` when nothing is stored.
     */
    @JvmInline
    private value class StoredMcpSecret(val plaintext: String?)

    /**
     * Parses a decrypted MCP secret, or returns `null` when it is corrupt.
     *
     * The exception itself is never logged: on Android a `JSONException`'s message ends with
     * the whole parsed input (AOSP `JSONTokener.toString`), which here is the credential, and
     * every WARN+ record — throwable included — reaches crash reports after opt-in.
     */
    private fun parseStoredMcpSecret(raw: String, what: String): JSONObject? = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        Timber.e("Stored MCP %s JSON is corrupt (%s); treating it as absent.", what, e.javaClass.simpleName)
        null
    }

    /** Serializes the legacy-DataStore MCP-secrets migration so concurrent collectors run it once. */
    private val mcpSecretsMigrationMutex = Mutex()
    private var mcpSecretsMigrationDone = false

    /**
     * One-time move of the MCP secrets that earlier releases kept inline in the plain
     * `mcp_servers_json` DataStore entry — the typed `auth` object and the custom `headers`
     * — into the encrypted store. Every encrypted copy is committed synchronously
     * **before** the JSON is rewritten without the inline copies, so an interruption
     * leaves both copies rather than neither, and the next collection finishes the job.
     * An [IOException] reading or rewriting DataStore defers the migration the same way.
     */
    private suspend fun migrateLegacyMcpSecrets() {
        if (mcpSecretsMigrationDone) return
        mcpSecretsMigrationMutex.withLock {
            if (mcpSecretsMigrationDone) return
            val json = try {
                dataStore.data.first()[PreferencesKeys.MCP_SERVERS_JSON]
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.e(e, "Cannot read preferences for the MCP-secrets migration; retrying later.")
                return
            }
            val rewritten = if (json.isNullOrBlank()) null else extractInlineMcpSecrets(json)
            if (rewritten != null) {
                try {
                    dataStore.edit { it[PreferencesKeys.MCP_SERVERS_JSON] = rewritten }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // The encrypted copies are already committed; the inline ones stay
                    // until a later collection rewrites the JSON.
                    Timber.e(e, "Cannot rewrite preferences for the MCP-secrets migration; retrying later.")
                    return
                }
            }
            mcpSecretsMigrationDone = true
        }
    }

    /**
     * Pure (non-suspend) half of [migrateLegacyMcpSecrets]: moves every server's inline
     * `auth` and `headers` objects into the encrypted store and returns the JSON rewritten
     * with both stripped, or `null` when nothing changed or the JSON is malformed.
     *
     * The two differ on an entry that already exists. Auth keeps the stored one (the
     * behaviour of the original auth migration). Headers take the inline copy: this build
     * never writes headers inline, so an inline copy next to an encrypted one is either the
     * same value (an interrupted migration) or a newer one (written by an older build after
     * a downgrade) — never an older one.
     */
    private fun extractInlineMcpSecrets(json: String): String? = try {
        val array = JSONArray(json)
        var changed = false
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            obj.optJSONObject("auth")?.let { inlineAuth ->
                val key = SecretKeys.mcpAuthKey(url)
                val existing = try {
                    secretsStore.getString(key)
                } catch (e: SecureValueUnreadableException) {
                    null
                }
                if (existing == null) {
                    secretsStore.putString(key, inlineAuth.toString(), synchronous = true)
                }
                obj.remove("auth")
                changed = true
            }
            obj.optJSONObject("headers")?.let { inlineHeaders ->
                val key = SecretKeys.mcpHeadersKey(url)
                if (inlineHeaders.length() > 0) {
                    secretsStore.putString(key, inlineHeaders.toString(), synchronous = true)
                } else {
                    secretsStore.remove(key)
                }
                mcpHeadersCache.remove(url)
                obj.remove("headers")
                changed = true
            }
        }
        if (changed) array.toString() else null
    } catch (e: JSONException) {
        // Not the exception: its message would carry the whole JSON (see parseStoredMcpSecret).
        Timber.e("MCP servers JSON is malformed (%s); skipping the secrets migration.", e.javaClass.simpleName)
        null
    }

    private fun encodeMcpServers(servers: List<McpServerConfig>): String {
        val array = JSONArray()
        servers.forEach { config ->
            val obj = JSONObject()
                .put("url", config.url)
                .put("transport", config.transport.wireId)
            if (!config.name.isNullOrBlank()) obj.put("name", config.name)
            // Neither auth nor custom headers are written here: both live in the
            // encrypted store (see [writeMcpAuth] / [writeMcpHeaders] /
            // [reconcileMcpSecrets]), keyed by server URL.
            array.put(obj)
        }
        return array.toString()
    }

    private fun encodeAuth(auth: McpAuth): JSONObject? = when (auth) {
        is McpAuth.None -> null
        is McpAuth.Bearer -> JSONObject().put("type", "bearer").put("token", auth.token)
        is McpAuth.Basic -> JSONObject()
            .put("type", "basic")
            .put("username", auth.username)
            .put("password", auth.password)
        is McpAuth.ApiKey -> JSONObject()
            .put("type", "apiKey")
            .put("headerName", auth.headerName)
            .put("value", auth.value)
    }

    private fun decodeAuth(obj: JSONObject?): McpAuth {
        if (obj == null) return McpAuth.None
        return when (obj.optString("type")) {
            "bearer" -> McpAuth.Bearer(token = obj.optString("token"))
            "basic" -> McpAuth.Basic(
                username = obj.optString("username"),
                password = obj.optString("password"),
            )
            "apiKey" -> McpAuth.ApiKey(
                headerName = obj.optString("headerName"),
                value = obj.optString("value"),
            )
            else -> McpAuth.None
        }
    }

    /** Decodes a stored headers object; a non-string value reads as its string form. */
    private fun decodeHeaders(obj: JSONObject): Map<String, String> = buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, obj.optString(key))
        }
    }

    private fun decodeMcpServers(json: String): List<McpServerConfig> = try {
        val array = JSONArray(json)
        buildList(capacity = array.length()) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name").takeIf { it.isNotBlank() }
                val transport = McpTransport.fromWireId(obj.optString("transport").takeIf { it.isNotBlank() })
                // Auth and headers normally come from the encrypted store; a
                // still-inline `auth` / `headers` object is honoured too, covering
                // the window before the one-time migration
                // ([migrateLegacyMcpSecrets]) has rewritten the JSON — or after an
                // interrupted rewrite. Post-migration the JSON carries neither.
                val inlineAuth = obj.optJSONObject("auth")
                val auth = if (inlineAuth != null) decodeAuth(inlineAuth) else readMcpAuth(url)
                val inlineHeaders = obj.optJSONObject("headers")
                val headers = if (inlineHeaders != null) decodeHeaders(inlineHeaders) else readMcpHeaders(url)
                add(
                    McpServerConfig(
                        url = url,
                        name = name,
                        transport = transport,
                        auth = auth,
                        headers = headers,
                    ),
                )
            }
        }
    } catch (e: JSONException) {
        // Not the exception: its message would carry the whole JSON (see parseStoredMcpSecret).
        Timber.w("Failed to decode MCP servers JSON (%s); falling back to empty list", e.javaClass.simpleName)
        emptyList()
    }

    override val disabledAppFunctions: Flow<Set<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DISABLED_APP_FUNCTIONS] ?: emptySet()
        }

    override suspend fun setDisabledAppFunctions(functions: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DISABLED_APP_FUNCTIONS] = functions
        }
    }

    override val disabledMcpTools: Flow<Set<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DISABLED_MCP_TOOLS] ?: emptySet()
        }

    override suspend fun setDisabledMcpTools(toolIds: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DISABLED_MCP_TOOLS] = toolIds
        }
    }

    override val approvedCleartextOrigins: Flow<Set<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.APPROVED_CLEARTEXT_ORIGINS] ?: emptySet()
        }

    override suspend fun approveCleartextOrigin(origin: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.APPROVED_CLEARTEXT_ORIGINS] ?: emptySet()
            preferences[PreferencesKeys.APPROVED_CLEARTEXT_ORIGINS] = current + origin
        }
    }

    override val toolRiskOverrides: Flow<Map<String, ToolRisk>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeRiskOverrides(preferences[PreferencesKeys.TOOL_RISK_OVERRIDES])
        }

    override suspend fun setToolRiskOverride(toolKey: String, risk: ToolRisk) {
        dataStore.edit { preferences ->
            val current = decodeRiskOverrides(preferences[PreferencesKeys.TOOL_RISK_OVERRIDES])
            val merged = current + (toolKey to risk)
            preferences[PreferencesKeys.TOOL_RISK_OVERRIDES] = encodeRiskOverrides(merged)
        }
    }

    private fun decodeRiskOverrides(raw: String?): Map<String, ToolRisk> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key)
                    val risk = runCatching { ToolRisk.valueOf(value) }.getOrNull()
                    if (risk != null) {
                        put(key, risk)
                    } else {
                        Timber.w("Dropping AppFunction risk override for %s — unknown risk value '%s'", key, value)
                    }
                }
            }
        } catch (e: org.json.JSONException) {
            Timber.w(e, "Failed to parse app_function_risk_overrides — falling back to empty map")
            emptyMap()
        }
    }

    private fun encodeRiskOverrides(overrides: Map<String, ToolRisk>): String {
        val json = JSONObject()
        for ((name, risk) in overrides) {
            json.put(name, risk.name)
        }
        return json.toString()
    }

    override val currentChatSessionId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CURRENT_CHAT_SESSION_ID]
        }

    override suspend fun setCurrentChatSessionId(sessionId: String?) {
        dataStore.edit { preferences ->
            if (sessionId == null) {
                preferences.remove(PreferencesKeys.CURRENT_CHAT_SESSION_ID)
            } else {
                preferences[PreferencesKeys.CURRENT_CHAT_SESSION_ID] = sessionId
            }
        }
    }

    override val consolePreferredConsoleTabName: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CONSOLE_PREFERRED_TAB] ?: CONSOLE_PREFERRED_TAB_DEFAULT
        }

    override suspend fun setConsolePreferredConsoleTabName(name: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CONSOLE_PREFERRED_TAB] = name
        }
    }

    override val memoryLastCompactedAt: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_LAST_COMPACTED_AT] ?: 0L
        }

    override suspend fun setMemoryLastCompactedAt(millis: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_LAST_COMPACTED_AT] = millis
        }
    }

    override val memorySearchTopK: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_SEARCH_TOP_K]
                ?: SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT
        }

    override suspend fun setMemorySearchTopK(topK: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_SEARCH_TOP_K] = topK
        }
    }

    override val chatHistoryCompressionEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_ENABLED]
                ?: SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT
        }

    override suspend fun setChatHistoryCompressionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_ENABLED] = enabled
        }
    }

    override val chatHistoryCompressionThresholdTokens: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS]
                ?: SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT
        }

    override suspend fun setChatHistoryCompressionThresholdTokens(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS] = tokens
        }
    }

    override val chatHistoryLiveWindowSize: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_LIVE_WINDOW_SIZE]
                ?: SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT
        }

    override suspend fun setChatHistoryLiveWindowSize(size: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CHAT_HISTORY_LIVE_WINDOW_SIZE] = size
        }
    }

    override val audioMaxDurationSec: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUDIO_MAX_DURATION_SEC]
                ?: SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT
        }

    override suspend fun setAudioMaxDurationSec(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_MAX_DURATION_SEC] = seconds
        }
    }

    override val memorySearchThreshold: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_SEARCH_THRESHOLD]
                ?: SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT
        }

    override suspend fun setMemorySearchThreshold(threshold: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_SEARCH_THRESHOLD] = threshold
        }
    }

    override val memoryRecencyHalfLifeDays: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_RECENCY_HALF_LIFE_DAYS]
                ?: SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT
        }

    override suspend fun setMemoryRecencyHalfLifeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_RECENCY_HALF_LIFE_DAYS] = days
        }
    }

    override val defaultPipelineId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEFAULT_PIPELINE_ID]
        }

    override suspend fun setDefaultPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(PreferencesKeys.DEFAULT_PIPELINE_ID)
            } else {
                preferences[PreferencesKeys.DEFAULT_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val shareTargetPipelineId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHARE_TARGET_PIPELINE_ID]
        }

    override suspend fun setShareTargetPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(PreferencesKeys.SHARE_TARGET_PIPELINE_ID)
            } else {
                preferences[PreferencesKeys.SHARE_TARGET_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val shareReuseSession: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SHARE_REUSE_SESSION]
                ?: SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT
        }

    override suspend fun setShareReuseSession(reuse: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHARE_REUSE_SESSION] = reuse
        }
    }

    override val quickSettingsTilePipelineId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.QUICK_SETTINGS_TILE_PIPELINE_ID]
        }

    override suspend fun setQuickSettingsTilePipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(PreferencesKeys.QUICK_SETTINGS_TILE_PIPELINE_ID)
            } else {
                preferences[PreferencesKeys.QUICK_SETTINGS_TILE_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val externalAutomationPipelineId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.EXTERNAL_AUTOMATION_PIPELINE_ID]
        }

    override suspend fun setExternalAutomationPipelineId(pipelineId: String?) {
        dataStore.edit { preferences ->
            if (pipelineId == null) {
                preferences.remove(PreferencesKeys.EXTERNAL_AUTOMATION_PIPELINE_ID)
            } else {
                preferences[PreferencesKeys.EXTERNAL_AUTOMATION_PIPELINE_ID] = pipelineId
            }
        }
    }

    override val externalAutomationEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.EXTERNAL_AUTOMATION_ENABLED]
                ?: SettingsDefaults.EXTERNAL_AUTOMATION_ENABLED_DEFAULT
        }

    override suspend fun setExternalAutomationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXTERNAL_AUTOMATION_ENABLED] = enabled
        }
    }

    override val localModelBackend: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_BACKEND] ?: LocalBackend.CPU.key
        }

    override val localModelBackendPreference: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[PreferencesKeys.LOCAL_MODEL_BACKEND] }

    override suspend fun setLocalModelBackend(backend: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_BACKEND] = backend
        }
    }

    override val activeEmbeddingProviderId: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.ACTIVE_EMBEDDING_PROVIDER_ID]
                ?: SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT
        }

    override suspend fun setActiveEmbeddingProviderId(id: String) {
        dataStore.edit { preferences ->
            // First provider switch ever: capture the provider the stored
            // vectors were created with, so the re-embed reminder banner can
            // compare it against the new active id. Done inside the same edit
            // so the capture and the switch land atomically.
            if (preferences[PreferencesKeys.LAST_REEMBED_PROVIDER_ID] == null) {
                preferences[PreferencesKeys.LAST_REEMBED_PROVIDER_ID] =
                    preferences[PreferencesKeys.ACTIVE_EMBEDDING_PROVIDER_ID]
                        ?: SettingsDefaults.ACTIVE_EMBEDDING_PROVIDER_ID_DEFAULT
            }
            preferences[PreferencesKeys.ACTIVE_EMBEDDING_PROVIDER_ID] = id
        }
    }

    override val lastReembedProviderId: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LAST_REEMBED_PROVIDER_ID]
        }

    override suspend fun setLastReembedProviderId(id: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_REEMBED_PROVIDER_ID] = id
        }
    }

    override val autoExtractEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_EXTRACT_ENABLED] ?: SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT
        }

    override val memoryCompactionEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_COMPACTION_ENABLED]
                ?: SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT
        }

    override suspend fun setMemoryCompactionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_COMPACTION_ENABLED] = enabled
        }
    }

    override val verboseMemoryLoggingEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.VERBOSE_MEMORY_LOGGING_ENABLED]
                ?: SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT
        }

    override suspend fun setVerboseMemoryLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.VERBOSE_MEMORY_LOGGING_ENABLED] = enabled
        }
    }

    override val memoryCompactionAgeDays: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_COMPACTION_AGE_DAYS]
                ?: SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT
        }

    override suspend fun setMemoryCompactionAgeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_COMPACTION_AGE_DAYS] = days
        }
    }

    override val maxMemoryChunks: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MAX_MEMORY_CHUNKS]
                ?: SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT
        }

    override suspend fun setMaxMemoryChunks(limit: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_MEMORY_CHUNKS] = limit
        }
    }

    override suspend fun setAutoExtractEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_EXTRACT_ENABLED] = enabled
        }
    }

    override val lastInitBackendAttempt: Flow<String?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[PreferencesKeys.LAST_INIT_BACKEND_ATTEMPT] }

    override suspend fun setLastInitBackendAttempt(backendKey: String?) {
        dataStore.edit { preferences ->
            if (backendKey == null) {
                preferences.remove(PreferencesKeys.LAST_INIT_BACKEND_ATTEMPT)
            } else {
                preferences[PreferencesKeys.LAST_INIT_BACKEND_ATTEMPT] = backendKey
            }
        }
    }

    override val localBackendFailureStreak: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[PreferencesKeys.LOCAL_BACKEND_FAILURE_STREAK] ?: 0 }

    override suspend fun setLocalBackendFailureStreak(streak: Int) {
        dataStore.edit { preferences ->
            if (streak <= 0) {
                preferences.remove(PreferencesKeys.LOCAL_BACKEND_FAILURE_STREAK)
            } else {
                preferences[PreferencesKeys.LOCAL_BACKEND_FAILURE_STREAK] = streak
            }
        }
    }

    override val toolCallTimeoutMs: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TOOL_CALL_TIMEOUT_MS] ?: SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT
        }

    override suspend fun setToolCallTimeoutMs(timeoutMs: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOOL_CALL_TIMEOUT_MS] = timeoutMs.coerceIn(
                SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MIN,
                SettingsDefaults.TOOL_CALL_TIMEOUT_MS_MAX,
            )
        }
    }

    override val workspaceMaxFileSizeBytes: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.WORKSPACE_MAX_FILE_SIZE_BYTES]
                ?: SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT
        }

    override suspend fun setWorkspaceMaxFileSizeBytes(bytes: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WORKSPACE_MAX_FILE_SIZE_BYTES] = bytes.coerceIn(
                SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_MIN,
                SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_MAX,
            )
        }
    }

    override val workspaceMaxTotalBytes: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.WORKSPACE_MAX_TOTAL_BYTES]
                ?: SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_DEFAULT
        }

    override suspend fun setWorkspaceMaxTotalBytes(bytes: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WORKSPACE_MAX_TOTAL_BYTES] = bytes.coerceIn(
                SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_MIN,
                SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_MAX,
            )
        }
    }

    override val workspaceReadTokenBudget: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.WORKSPACE_READ_TOKEN_BUDGET]
                ?: SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_DEFAULT
        }

    override suspend fun setWorkspaceReadTokenBudget(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WORKSPACE_READ_TOKEN_BUDGET] = tokens.coerceIn(
                SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_MIN,
                SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_MAX,
            )
        }
    }

    override val allowedHttpDomains: Flow<List<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.ALLOWED_HTTP_DOMAINS]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }

    override suspend fun setAllowedHttpDomains(domains: List<String>) {
        val encoded = domains.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator = "\n")
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALLOWED_HTTP_DOMAINS] = encoded
        }
    }

    override val httpToolMaxResponseBytes: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.HTTP_TOOL_MAX_RESPONSE_BYTES]
                ?: SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT
        }

    override suspend fun setHttpToolMaxResponseBytes(bytes: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HTTP_TOOL_MAX_RESPONSE_BYTES] = bytes.coerceIn(
                SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_MIN,
                SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_MAX,
            )
        }
    }

    override val pipelineMaxSteps: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.PIPELINE_MAX_STEPS] ?: SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT
        }

    override suspend fun setPipelineMaxSteps(steps: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PIPELINE_MAX_STEPS] = steps.coerceIn(
                SettingsDefaults.PIPELINE_MAX_STEPS_MIN,
                SettingsDefaults.PIPELINE_MAX_STEPS_MAX,
            )
        }
    }

    override val pipelineMaxStepsBackground: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            // Falls back to the *configured* interactive cap, not to a constant.
            // Until this key existed one setting governed every origin, so a user
            // who had raised the cap to 40 would otherwise find their triggers
            // silently dropped to the shipped default on upgrade — the exact
            // capability regression the background default was chosen to avoid.
            // A constant is only reached when the user never set either.
            preferences[PreferencesKeys.PIPELINE_MAX_STEPS_BACKGROUND]
                ?: preferences[PreferencesKeys.PIPELINE_MAX_STEPS]
                ?: SettingsDefaults.PIPELINE_MAX_STEPS_BACKGROUND_DEFAULT
        }

    /**
     * True once the background key exists in storage, whatever its value.
     *
     * Deliberately keyed on presence, not on the number: the default background
     * ceiling equals the interactive one, so a user who deliberately sets 15
     * and a user who has never touched it produce the same figure and only the
     * stored key tells them apart.
     */
    override val pipelineMaxStepsBackgroundIsSet: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences.contains(PreferencesKeys.PIPELINE_MAX_STEPS_BACKGROUND) }

    override suspend fun setPipelineMaxStepsBackground(steps: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PIPELINE_MAX_STEPS_BACKGROUND] = steps.coerceIn(
                SettingsDefaults.PIPELINE_MAX_STEPS_MIN,
                SettingsDefaults.PIPELINE_MAX_STEPS_MAX,
            )
        }
    }

    override val runMaxTokens: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.RUN_MAX_TOKENS] ?: SettingsDefaults.RUN_MAX_TOKENS_DEFAULT
        }

    override suspend fun setRunMaxTokens(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RUN_MAX_TOKENS] = tokens.coerceIn(
                SettingsDefaults.RUN_MAX_TOKENS_MIN,
                SettingsDefaults.RUN_MAX_TOKENS_MAX,
            )
        }
    }

    override val runMaxTokensBackground: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.RUN_MAX_TOKENS_BACKGROUND]
                ?: SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT
        }

    override suspend fun setRunMaxTokensBackground(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RUN_MAX_TOKENS_BACKGROUND] = tokens.coerceIn(
                SettingsDefaults.RUN_MAX_TOKENS_MIN,
                SettingsDefaults.RUN_MAX_TOKENS_MAX,
            )
        }
    }

    override val pipelineMaxNestingDepth: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.PIPELINE_MAX_NESTING_DEPTH]
                ?: SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT
        }

    override suspend fun setPipelineMaxNestingDepth(depth: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PIPELINE_MAX_NESTING_DEPTH] = depth.coerceIn(
                SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MIN,
                SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MAX,
            )
        }
    }

    override val structuredOutputMaxRepairs: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.STRUCTURED_OUTPUT_MAX_REPAIRS]
                ?: SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT
        }

    override suspend fun setStructuredOutputMaxRepairs(count: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.STRUCTURED_OUTPUT_MAX_REPAIRS] = count.coerceIn(
                SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MIN,
                SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MAX,
            )
        }
    }

    override val cloudRetryMaxAttempts: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CLOUD_RETRY_MAX_ATTEMPTS]
                ?: SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT
        }

    override suspend fun setCloudRetryMaxAttempts(attempts: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLOUD_RETRY_MAX_ATTEMPTS] = attempts.coerceIn(
                SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MIN,
                SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MAX,
            )
        }
    }

    override val cloudRetryBaseDelayMs: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CLOUD_RETRY_BASE_DELAY_MS]
                ?: SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT
        }

    override suspend fun setCloudRetryBaseDelayMs(delayMs: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLOUD_RETRY_BASE_DELAY_MS] = delayMs.coerceIn(
                SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MIN,
                SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MAX,
            )
        }
    }

    override val resumeMaxAgeHours: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.RESUME_MAX_AGE_HOURS] ?: SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT
        }

    override suspend fun setResumeMaxAgeHours(hours: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RESUME_MAX_AGE_HOURS] = hours.coerceIn(
                SettingsDefaults.RESUME_MAX_AGE_HOURS_MIN,
                SettingsDefaults.RESUME_MAX_AGE_HOURS_MAX,
            )
        }
    }

    override val backgroundApprovalWindowHours: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.BACKGROUND_APPROVAL_WINDOW_HOURS]
                ?: SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT
        }

    override suspend fun setBackgroundApprovalWindowHours(hours: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACKGROUND_APPROVAL_WINDOW_HOURS] = hours.coerceIn(
                SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_MIN,
                SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_MAX,
            )
        }
    }

    override val traceRetentionRunsPerSession: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TRACE_RETENTION_RUNS_PER_SESSION]
                ?: SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT
        }

    override suspend fun setTraceRetentionRunsPerSession(runs: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRACE_RETENTION_RUNS_PER_SESSION] = runs.coerceIn(
                SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MIN,
                SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MAX,
            )
        }
    }

    override val traceRetentionMaxAgeDays: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.TRACE_RETENTION_MAX_AGE_DAYS]
                ?: SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT
        }

    override suspend fun setTraceRetentionMaxAgeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRACE_RETENTION_MAX_AGE_DAYS] = days.coerceIn(
                SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MIN,
                SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MAX,
            )
        }
    }

    override val crashReportingEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CRASH_REPORTING_ENABLED]
                ?: SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT
        }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CRASH_REPORTING_ENABLED] = enabled
        }
    }

    override val usageTelemetryEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.USAGE_TELEMETRY_ENABLED]
                ?: SettingsDefaults.USAGE_TELEMETRY_ENABLED_DEFAULT
        }

    override suspend fun setUsageTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USAGE_TELEMETRY_ENABLED] = enabled
        }
    }

    override val memorySummaryDefaultLimit: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.MEMORY_SUMMARY_DEFAULT_LIMIT]
                ?: SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT
        }

    override suspend fun setMemorySummaryDefaultLimit(limit: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEMORY_SUMMARY_DEFAULT_LIMIT] = limit
        }
    }

    override val toolApprovalPolicy: Flow<ToolApprovalPolicy> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val storedKey = preferences[PreferencesKeys.TOOL_APPROVAL_POLICY]
            if (storedKey != null) {
                ToolApprovalPolicy.fromKey(storedKey)
            } else {
                // Migration from the legacy boolean key, until the policy is written.
                // true  → SensitiveOrDestructive (default-with-care).
                // false → NeverPrompt (the legacy switch was the only way to quiet
                // the prompts; a destructive call still asks under it).
                when (preferences[PreferencesKeys.REQUIRES_USER_CONFIRMATION]) {
                    false -> ToolApprovalPolicy.NeverPrompt
                    true -> ToolApprovalPolicy.SensitiveOrDestructive
                    null -> ToolApprovalPolicy.DEFAULT
                }
            }
        }

    override suspend fun setToolApprovalPolicy(policy: ToolApprovalPolicy) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOOL_APPROVAL_POLICY] = policy.key
        }
    }

    override val blockDestructiveTools: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.BLOCK_DESTRUCTIVE_TOOLS]
                ?: SettingsDefaults.BLOCK_DESTRUCTIVE_TOOLS_DEFAULT
        }

    override suspend fun setBlockDestructiveTools(blocked: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLOCK_DESTRUCTIVE_TOOLS] = blocked
        }
    }

    override val blockNetworkFromLocalModel: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.BLOCK_NETWORK_FROM_LOCAL_MODEL]
                ?: SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT
        }

    override suspend fun setBlockNetworkFromLocalModel(blocked: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLOCK_NETWORK_FROM_LOCAL_MODEL] = blocked
        }
    }

    override val scheduledTaskNotificationsEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.SCHEDULED_TASK_NOTIFICATIONS]
                ?: SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT
        }

    override suspend fun setScheduledTaskNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCHEDULED_TASK_NOTIFICATIONS] = enabled
        }
    }

    override val lastTestProbeResult: Flow<TestProbeResult?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeTestProbeResult(preferences[PreferencesKeys.LAST_TEST_PROBE_RESULT])
        }

    override suspend fun setLastTestProbeResult(result: TestProbeResult?) {
        dataStore.edit { preferences ->
            if (result == null) {
                preferences.remove(PreferencesKeys.LAST_TEST_PROBE_RESULT)
            } else {
                preferences[PreferencesKeys.LAST_TEST_PROBE_RESULT] = encodeTestProbeResult(result)
            }
        }
    }

    /**
     * Writes the local-generation sampling + pipeline/run-ceiling/structured-output/
     * cloud-retry defaults into [preferences]. Shared by [resetSamplingDefaults] (the
     * per-card "Reset to defaults") and [resetToRecommendedDefaults] (the global
     * reset) so the two paths cannot drift on these twelve keys.
     */
    private fun MutablePreferences.applySamplingDefaults() {
        this[PreferencesKeys.TEMPERATURE] = SettingsDefaults.TEMPERATURE_DEFAULT
        this[PreferencesKeys.TOP_K] = SettingsDefaults.TOP_K_DEFAULT
        this[PreferencesKeys.TOP_P] = SettingsDefaults.TOP_P_DEFAULT
        this[PreferencesKeys.MAX_CONTEXT_LENGTH] = SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT
        this[PreferencesKeys.PIPELINE_MAX_STEPS] = SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT
        // REMOVED, not written back to its default. Writing the key is exactly
        // what marks the background ceiling as independently chosen, so writing
        // it here would make a reset do the one thing a reset must not: leave
        // the user with a deliberate-looking decision they never made, silently
        // detached from the interactive ceiling it is supposed to follow.
        // Removing it restores the inheritance, which *is* the default state.
        remove(PreferencesKeys.PIPELINE_MAX_STEPS_BACKGROUND)
        this[PreferencesKeys.RUN_MAX_TOKENS] = SettingsDefaults.RUN_MAX_TOKENS_DEFAULT
        this[PreferencesKeys.RUN_MAX_TOKENS_BACKGROUND] = SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT
        this[PreferencesKeys.PIPELINE_MAX_NESTING_DEPTH] = SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT
        this[PreferencesKeys.STRUCTURED_OUTPUT_MAX_REPAIRS] = SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT
        this[PreferencesKeys.CLOUD_RETRY_MAX_ATTEMPTS] = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT
        this[PreferencesKeys.CLOUD_RETRY_BASE_DELAY_MS] = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT
    }

    override suspend fun resetSamplingDefaults() {
        dataStore.edit { preferences -> preferences.applySamplingDefaults() }
    }

    @Suppress("LongMethod") // Flat list of independent key→default writes; splitting it would only obscure it.
    override suspend fun resetToRecommendedDefaults() {
        dataStore.edit { preferences ->
            // Sampling / generation + pipeline / structured output / cloud retry.
            preferences.applySamplingDefaults()
            // Tool / workspace / http limits.
            preferences[PreferencesKeys.TOOL_CALL_TIMEOUT_MS] = SettingsDefaults.TOOL_CALL_TIMEOUT_MS_DEFAULT
            preferences[PreferencesKeys.WORKSPACE_MAX_FILE_SIZE_BYTES] =
                SettingsDefaults.WORKSPACE_MAX_FILE_SIZE_BYTES_DEFAULT
            preferences[PreferencesKeys.WORKSPACE_MAX_TOTAL_BYTES] =
                SettingsDefaults.WORKSPACE_MAX_TOTAL_BYTES_DEFAULT
            preferences[PreferencesKeys.WORKSPACE_READ_TOKEN_BUDGET] =
                SettingsDefaults.WORKSPACE_READ_TOKEN_BUDGET_DEFAULT
            preferences[PreferencesKeys.HTTP_TOOL_MAX_RESPONSE_BYTES] =
                SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT
            // Run lifecycle windows / retention.
            preferences[PreferencesKeys.RESUME_MAX_AGE_HOURS] = SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT
            preferences[PreferencesKeys.BACKGROUND_APPROVAL_WINDOW_HOURS] =
                SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT
            preferences[PreferencesKeys.TRACE_RETENTION_RUNS_PER_SESSION] =
                SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT
            preferences[PreferencesKeys.TRACE_RETENTION_MAX_AGE_DAYS] =
                SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT
            // Audio.
            preferences[PreferencesKeys.AUDIO_MAX_DURATION_SEC] = SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT
            // Memory tuning.
            preferences[PreferencesKeys.MEMORY_SUMMARY_DEFAULT_LIMIT] =
                SettingsDefaults.MEMORY_SUMMARY_DEFAULT_LIMIT_DEFAULT
            preferences[PreferencesKeys.MEMORY_SEARCH_TOP_K] = SettingsDefaults.MEMORY_SEARCH_TOP_K_DEFAULT
            preferences[PreferencesKeys.MEMORY_SEARCH_THRESHOLD] = SettingsDefaults.MEMORY_SEARCH_THRESHOLD_DEFAULT
            preferences[PreferencesKeys.MEMORY_RECENCY_HALF_LIFE_DAYS] =
                SettingsDefaults.MEMORY_RECENCY_HALF_LIFE_DAYS_DEFAULT
            preferences[PreferencesKeys.MEMORY_COMPACTION_ENABLED] =
                SettingsDefaults.MEMORY_COMPACTION_ENABLED_DEFAULT
            preferences[PreferencesKeys.MEMORY_COMPACTION_AGE_DAYS] =
                SettingsDefaults.MEMORY_COMPACTION_AGE_DAYS_DEFAULT
            preferences[PreferencesKeys.MAX_MEMORY_CHUNKS] = SettingsDefaults.MAX_MEMORY_CHUNKS_DEFAULT
            preferences[PreferencesKeys.AUTO_EXTRACT_ENABLED] = SettingsDefaults.AUTO_EXTRACT_ENABLED_DEFAULT
            preferences[PreferencesKeys.VERBOSE_MEMORY_LOGGING_ENABLED] =
                SettingsDefaults.VERBOSE_MEMORY_LOGGING_ENABLED_DEFAULT
            // Chat-history compression.
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_ENABLED] =
                SettingsDefaults.CHAT_HISTORY_COMPRESSION_ENABLED_DEFAULT
            preferences[PreferencesKeys.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS] =
                SettingsDefaults.CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS_DEFAULT
            preferences[PreferencesKeys.CHAT_HISTORY_LIVE_WINDOW_SIZE] =
                SettingsDefaults.CHAT_HISTORY_LIVE_WINDOW_DEFAULT
            // Security toggles. The typed policy is written explicitly, so the
            // legacy boolean the migration reads can never decide it again.
            preferences[PreferencesKeys.TOOL_APPROVAL_POLICY] = ToolApprovalPolicy.DEFAULT.key
            preferences[PreferencesKeys.BLOCK_DESTRUCTIVE_TOOLS] =
                SettingsDefaults.BLOCK_DESTRUCTIVE_TOOLS_DEFAULT
            preferences[PreferencesKeys.BLOCK_NETWORK_FROM_LOCAL_MODEL] =
                SettingsDefaults.BLOCK_NETWORK_FROM_LOCAL_MODEL_DEFAULT
            // Notifications + privacy.
            preferences[PreferencesKeys.SCHEDULED_TASK_NOTIFICATIONS] =
                SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT
            preferences[PreferencesKeys.SHARE_REUSE_SESSION] =
                SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT
            preferences[PreferencesKeys.CRASH_REPORTING_ENABLED] =
                SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT
            preferences[PreferencesKeys.USAGE_TELEMETRY_ENABLED] =
                SettingsDefaults.USAGE_TELEMETRY_ENABLED_DEFAULT
            // Security-relevant switch: reset returns it to off, the safe
            // direction. The pipeline *binding* beside it is a user binding and
            // stays untouched, exactly like the other two surface bindings.
            preferences[PreferencesKeys.EXTERNAL_AUTOMATION_ENABLED] =
                SettingsDefaults.EXTERNAL_AUTOMATION_ENABLED_DEFAULT
        }
    }

    /**
     * Reflectively enumerates the wire names of every preference declared in
     * [PreferencesKeys]. Exposed only so the test-suite can assert that every
     * persistable key is either restored by [resetToRecommendedDefaults] or
     * listed among the deliberately-excluded user-data keys — catching the case
     * where a future setting is added but silently escapes the reset.
     */
    @VisibleForTesting
    internal fun knownPreferenceKeyNames(): Set<String> =
        PreferencesKeys::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            (field.get(PreferencesKeys) as? Preferences.Key<*>)?.name
        }.toSet()

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

    private companion object {
        /**
         * Default value for [PreferencesKeys.CONSOLE_PREFERRED_TAB] on a
         * fresh install. Mirrors the enum name of
         * `app.knotwork.design.components.console.ConsoleTab.Logs` — kept as
         * a raw string so this data-layer constant stays free of the
         * `:catalog` dependency.
         */
        const val CONSOLE_PREFERRED_TAB_DEFAULT = "Logs"
    }
}
