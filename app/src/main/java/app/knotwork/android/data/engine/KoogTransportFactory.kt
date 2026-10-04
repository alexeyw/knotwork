package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import app.knotwork.android.data.engine.retry.RetryAfterSlot
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * The transport every Koog model client the app builds runs on: exactly the client Koog's own
 * factory ([delegate]) configures, extended with two things Koog does not do.
 *
 * - **Every hop is checked before it is sent** against [hopRule] — the first request and each
 *   redirect a server answers with. The address the user entered is judged once, before the
 *   client is built ([ModelNetworkGate.endpointRefusal]); without this, a server could redirect
 *   the prompt anywhere, over plain HTTP, and nothing inside Koog/Ktor would look. A refused
 *   hop fails the call with [HopRefusedException] and is never sent.
 * - **The `Retry-After` header of every error answer is recorded** into [retryAfter]. Koog turns
 *   an error answer into an exception carrying the status and the body, but not the headers, so
 *   its retry loop never saw it — measured: a provider asking for one second got its retry after
 *   ~120 ms. [app.knotwork.android.data.engine.retry.RetryingCloudLlmClient] reads it.
 *
 * Both run in one Ktor send hook. Ktor installs its redirect handling before any plugin of
 * ours, so the hook sits inside it and sees each hop as its own send.
 *
 * Koog's configuration (base URL, default headers that carry the API key, timeouts, SSE, JSON)
 * is taken as it is and only extended — Ktor's `config` copies a client's installed plugins and
 * adds the new one on the same engine — so nothing Koog sets up is restated here to drift on an
 * upgrade. `KoogTransportFactoryTest` pins that the headers and the socket deadline survive.
 *
 * @property delegate Koog's own factory, shared by every client of the owning factory.
 * @property retryAfter Where this client's error answers leave their `Retry-After`.
 * @property hopRule Where this client's requests may go, taken when the client is built.
 */
class KoogTransportFactory(
    private val delegate: KtorKoogHttpClient.Factory,
    private val retryAfter: RetryAfterSlot,
    private val hopRule: ModelHopRule,
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
            install(transportChecks(hopRule, retryAfter))
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** First status of an error answer; a redirect's header is not the provider's wait. */
        const val FIRST_ERROR_STATUS = 400

        /**
         * A Ktor plugin that refuses a hop [hopRule] does not allow, and records the
         * `Retry-After` of each error answer into [retryAfter].
         *
         * It hooks the send step, which sees every hop before it goes out and every answer as
         * it arrives — on the streaming path too, where Ktor's SSE support turns an error answer
         * into an exception before the response pipeline would show it.
         */
        fun transportChecks(hopRule: ModelHopRule, retryAfter: RetryAfterSlot) =
            createClientPlugin("KnotworkModelTransport") {
                on(Send) { request ->
                    hopRule.refusal(request.url.buildString())?.let { throw HopRefusedException(it) }
                    val call = proceed(request)
                    if (call.response.status.value >= FIRST_ERROR_STATUS) {
                        retryAfter.record(call.response.headers[HttpHeaders.RetryAfter])
                    }
                    call
                }
            }
    }
}
