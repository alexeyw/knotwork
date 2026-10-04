package app.knotwork.android.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.crypto.SecureValueUnreadableException
import app.knotwork.android.data.local.settings.AppStateSettingsStore
import app.knotwork.android.data.local.settings.EntryPointSettingsStore
import app.knotwork.android.data.local.settings.GenerationSettingsStore
import app.knotwork.android.data.local.settings.MemorySettingsStore
import app.knotwork.android.data.local.settings.NetworkSettingsStore
import app.knotwork.android.data.local.settings.PrivacySettingsStore
import app.knotwork.android.data.local.settings.RunSettingsStore
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.UpdateMcpServerResult
import app.knotwork.android.domain.repositories.AppStateSettings
import app.knotwork.android.domain.repositories.EntryPointSettings
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.repositories.RunSettings
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
import javax.inject.Singleton

/**
 * Concrete implementation of [SettingsRepository] utilizing Androidx DataStore Preferences.
 *
 * Secret payloads are **not** kept in DataStore: per-server MCP credentials live in the injected
 * [SecretStore] (AES-GCM under a dedicated Android Keystore key in production), with the same
 * re-enterable-secret recovery policy as [ApiKeyManager] — an undecryptable value is dropped and
 * reported as unset. Credentials persisted by earlier releases inline in plain DataStore are
 * migrated into the secret store on the first read and removed from DataStore. The Hugging Face
 * token follows the same policy in [GenerationSettingsStore].
 *
 * The sections already split out into their own stores ([AppStateSettingsStore],
 * [PrivacySettingsStore], [EntryPointSettingsStore], [GenerationSettingsStore],
 * [NetworkSettingsStore], [MemorySettingsStore], [RunSettingsStore]) are delegated to; the rest is still implemented
 * here and moves out section by section. Both resets stay here until every section has moved, and
 * write each split-out section's part through its store, in the same atomic edit.
 *
 * Scoped `@Singleton` on the class, not only on its bindings: the sections not yet split out are
 * bound to this one instance (`SettingsModule`), and the in-memory MCP credential cache and the mutex
 * serialising server edits must exist once.
 *
 * @property dataStore The underlying DataStore instance for persistence.
 * @property secretsStore The encrypted store backing every secret payload.
 * @param appState The store of the app-state section.
 * @property privacy The store of the privacy section; writes its part of a reset.
 * @property entryPoints The store of the entry-point section; writes its part of a reset.
 * @property generation The store of the generation section; writes its part of both resets.
 * @property network The store of the network section; writes its part of both resets.
 * @property memory The store of the memory section; writes its part of a reset.
 * @property run The store of the run section; writes its part of both resets.
 */
@Singleton
class SettingsManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretsStore: SecretStore,
    appState: AppStateSettingsStore,
    private val privacy: PrivacySettingsStore,
    private val entryPoints: EntryPointSettingsStore,
    private val generation: GenerationSettingsStore,
    private val network: NetworkSettingsStore,
    private val memory: MemorySettingsStore,
    private val run: RunSettingsStore,
) : SettingsRepository,
    GenerationSettings by generation,
    NetworkSettings by network,
    MemorySettings by memory,
    RunSettings by run,
    AppStateSettings by appState,
    PrivacySettings by privacy,
    EntryPointSettings by entryPoints {

    private object PreferencesKeys {

        /**
         * Legacy "ask before tool calls" boolean, superseded by [TOOL_APPROVAL_POLICY].
         * Read only by the policy migration, on every policy read while the policy
         * key is absent; never written.
         */
        val REQUIRES_USER_CONFIRMATION = booleanPreferencesKey("requires_user_confirmation")
        val MCP_SERVER_URLS = stringSetPreferencesKey("mcp_server_urls")
        val MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
        val DISABLED_APP_FUNCTIONS = stringSetPreferencesKey("disabled_app_functions")
        val DISABLED_MCP_TOOLS = stringSetPreferencesKey("disabled_mcp_tools")

        // The stored key keeps its original `app_function_risk_overrides` name even
        // though the map now also carries MCP entries: renaming the DataStore key
        // would silently drop every override a user had already set. The Kotlin
        // surface (`toolRiskOverrides`) is the honest name; this string is history.
        val TOOL_RISK_OVERRIDES = stringPreferencesKey("app_function_risk_overrides")

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

        // Settings redesign.
        val TOOL_APPROVAL_POLICY = stringPreferencesKey("tool_approval_policy")
        val BLOCK_DESTRUCTIVE_TOOLS = booleanPreferencesKey("block_destructive_tools")
    }

    /**
     * Entry names inside [secretsStore]. Distinct from [PreferencesKeys]: these are slots in
     * the Keystore-backed encrypted store, not DataStore preference keys.
     */
    private object SecretKeys {
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

    override suspend fun resetSamplingDefaults() {
        dataStore.edit { preferences ->
            generation.writeSamplingDefaults(preferences)
            run.writeSamplingDefaults(preferences)
            network.writeSamplingDefaults(preferences)
        }
    }

    override suspend fun resetToRecommendedDefaults() {
        dataStore.edit { preferences ->
            // The sections split out into stores write their own part, in this same edit.
            generation.writeRecommendedDefaults(preferences)
            network.writeRecommendedDefaults(preferences)
            run.writeRecommendedDefaults(preferences)
            memory.writeRecommendedDefaults(preferences)
            privacy.writeRecommendedDefaults(preferences)
            entryPoints.writeRecommendedDefaults(preferences)
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
            // Security toggles. The typed policy is written explicitly, so the
            // legacy boolean the migration reads can never decide it again.
            preferences[PreferencesKeys.TOOL_APPROVAL_POLICY] = ToolApprovalPolicy.DEFAULT.key
            preferences[PreferencesKeys.BLOCK_DESTRUCTIVE_TOOLS] =
                SettingsDefaults.BLOCK_DESTRUCTIVE_TOOLS_DEFAULT
        }
    }
}
