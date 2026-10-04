package app.knotwork.android.data.engine.retry

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * The `Retry-After` header of the latest error answer one client received.
 *
 * One slot belongs to one client, and the factories build one client per operation (the
 * single-operation contract of [RetryingCloudLlmClient]), so its attempts run one after
 * another and a plain volatile field is enough.
 */
class RetryAfterSlot {

    @Volatile
    private var header: String? = null

    /**
     * Records the header of an error answer, replacing what an earlier answer left.
     *
     * @param value The header, or `null` when the answer had none.
     */
    fun record(value: String?) {
        header = value
    }

    /** Forgets the recorded header, before an attempt starts. */
    fun clear() {
        header = null
    }

    /**
     * The recorded header, forgotten as it is read so a later attempt cannot see it.
     *
     * @return The header of the latest error answer since [clear], or `null`.
     */
    fun take(): String? = header.also { header = null }
}

/**
 * A [KoogHttpClient.Factory] that builds exactly what [delegate] builds, then records the
 * `Retry-After` header of every error answer into [slot].
 *
 * Koog turns an error answer into an exception carrying the status and the body, but not the
 * headers, so its retry loop never saw a provider's `Retry-After` — measured: a provider asking
 * for one second got its retry after ~110 ms. The header is therefore read here, on the
 * transport, and handed to [CloudRetryPolicy] by [RetryingCloudLlmClient].
 *
 * The client [delegate] configures (base URL, headers, timeouts, SSE, JSON) is taken as it is and
 * only extended — Ktor's `config` copies a client's installed plugins and adds the new one on the
 * same engine — so nothing Koog sets up is restated here to drift on an upgrade.
 *
 * @property delegate Koog's own factory, shared by every client of the owning factory.
 * @property slot Where this client's error answers leave their `Retry-After`.
 */
internal class RetryAfterCapturingHttpClientFactory(
    private val delegate: KtorKoogHttpClient.Factory,
    private val slot: RetryAfterSlot,
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        val configured = delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        )
        return KtorKoogHttpClient(clientName, logger, configured.ktorClient) {
            install(retryAfterCapture(slot))
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** First status of an error answer; a redirect's header is not the provider's wait. */
        const val FIRST_ERROR_STATUS = 400

        /**
         * A Ktor plugin recording the `Retry-After` of each error answer into [slot].
         *
         * It hooks the send step, which sees every answer as it arrives — on the streaming path
         * too, where Ktor's SSE support turns an error answer into an exception before the
         * response pipeline would show it.
         */
        fun retryAfterCapture(slot: RetryAfterSlot) = createClientPlugin("KnotworkRetryAfterCapture") {
            on(Send) { request ->
                val call = proceed(request)
                if (call.response.status.value >= FIRST_ERROR_STATUS) {
                    slot.record(call.response.headers[HttpHeaders.RetryAfter])
                }
                call
            }
        }
    }
}
