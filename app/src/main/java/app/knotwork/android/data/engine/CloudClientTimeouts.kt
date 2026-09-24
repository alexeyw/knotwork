package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig

/**
 * Network deadlines applied to every Koog model client the app builds — the chat clients
 * in [KoogClientFactory] and the embedding clients in
 * [app.knotwork.android.data.services.embedding.DefaultKoogEmbedderFactory].
 *
 * One object for both factories because they drifted apart once already: the chat
 * factory got explicit deadlines and the embedding factory kept Koog's defaults, so every
 * memory write and memory search could wait fifteen minutes on a provider that accepted
 * the connection and went quiet. `KoogClientTimeoutKonsistTest` refuses a Koog client
 * construction that does not pass [CONFIG].
 *
 * Koog's own default is 900 s for both the request and the socket. Measured, not assumed:
 * a stalled stub was still connected after 120 s under that configuration, while an
 * explicit config on the same SSE path cut at its stated value.
 *
 * The load-bearing value is [SOCKET_TIMEOUT_MS], because it is applied **per read**: on a
 * streamed answer it bounds how long the provider may stay *silent*, not how long a
 * healthy answer may take — the same rule the task queue's silence valve uses. An
 * embedding request is not streamed, so there the same value bounds the wait for the
 * whole answer; the largest batch the app sends is 64 texts
 * (`RecomputePendingEmbeddingsUseCase.BATCH_SIZE`). [REQUEST_TIMEOUT_MS] stays at Koog's
 * generous value as a backstop for a provider that dribbles bytes forever.
 */
internal object CloudClientTimeouts {

    /**
     * Longest the provider may stay silent between bytes. Applied per read, so a
     * healthy long generation is untouched and a dead stream is not.
     */
    const val SOCKET_TIMEOUT_MS: Long = 60_000

    /** Connection establishment budget — a person is usually waiting on this. */
    const val CONNECT_TIMEOUT_MS: Long = 30_000

    /** Outer backstop for a request that never ends despite continuous bytes. */
    const val REQUEST_TIMEOUT_MS: Long = 900_000

    /** The configuration every Koog model client is built with. */
    val CONFIG: ConnectionTimeoutConfig = ConnectionTimeoutConfig(
        requestTimeoutMillis = REQUEST_TIMEOUT_MS,
        connectTimeoutMillis = CONNECT_TIMEOUT_MS,
        socketTimeoutMillis = SOCKET_TIMEOUT_MS,
    )
}
