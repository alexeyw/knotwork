package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * Where model traffic may go and how a cloud call is retried: local-only mode ("Block network from
 * local model"), the origins the user agreed to reach without encryption, and the retry budget of a
 * cloud call.
 *
 * Provider API keys and model names are not here: they live in [ApiKeyRepository], on their own
 * encrypted store. One of the sections [SettingsRepository] is made of.
 */
interface NetworkSettings {

    /**
     * A [Flow] of origins (`http://host[:port]`) the user has explicitly agreed
     * to talk to **unencrypted**.
     *
     * Android's `network_security_config.xml` cannot express "any private-LAN
     * address", so the cleartext rule lives in app code instead
     * (`CleartextPolicy`): unencrypted traffic is permitted only to a
     * loopback / private address present in this set, and never to a public
     * host. Entries are added when the user confirms the prompt shown while
     * saving a local Ollama or MCP address.
     *
     * An empty set means no unencrypted destination has been approved yet —
     * which is the state of a fresh install.
     */
    val approvedCleartextOrigins: Flow<Set<String>>

    /**
     * Records the user's agreement to send unencrypted traffic to [origin].
     *
     * @param origin canonical `scheme://host[:port]`, as produced by
     *   `CleartextPolicy.originOf`. Storing anything else would silently fail
     *   to match at request time.
     */
    suspend fun approveCleartextOrigin(origin: String)

    /**
     * A [Flow] representing the maximum number of attempts (the initial call
     * plus retries) a transient cloud-call failure is given before the error
     * propagates. Consumed by the cloud client/embedding factories to build
     * Koog's retry policy; `1` means "no retries". Valid range: 1–5.
     */
    val cloudRetryMaxAttempts: Flow<Int>

    /**
     * Updates the cloud-retry attempt budget.
     *
     * @param attempts The new attempt ceiling. Will be coerced to the range 1–5.
     */
    suspend fun setCloudRetryMaxAttempts(attempts: Int)

    /**
     * A [Flow] representing the base delay, in milliseconds, before the first
     * cloud retry. Subsequent retries grow it by the fixed exponential-backoff
     * multiplier with jitter. Valid range: 100–10000.
     */
    val cloudRetryBaseDelayMs: Flow<Long>

    /**
     * Updates the cloud-retry base delay.
     *
     * @param delayMs The new base delay in milliseconds. Will be coerced to the
     *   range 100–10000.
     */
    suspend fun setCloudRetryBaseDelayMs(delayMs: Long)

    /**
     * Local-only mode flag ("Block network from local model"). When `true`, no
     * model request leaves the device or the user's network: `ModelNetworkGate`
     * refuses every hosted cloud provider (OpenAI / Anthropic / Google / DeepSeek)
     * for chat, `delegate_task` and memory embeddings alike, and admits Ollama only
     * when its host is `localhost` or a loopback / private IPv4 literal, whatever
     * the scheme ([app.knotwork.android.domain.services.LocalOnlyPolicy]). Tools
     * (MCP servers, `http_request`) are not affected. Defaults to `false`.
     */
    val blockNetworkFromLocalModel: Flow<Boolean>

    /**
     * Updates the local-only mode flag.
     *
     * @param blocked `true` to gate every cloud provider.
     */
    suspend fun setBlockNetworkFromLocalModel(blocked: Boolean)
}
