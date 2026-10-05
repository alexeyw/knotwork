package app.knotwork.android.data.network

import ai.koog.http.client.KoogHttpClientException
import app.knotwork.android.data.engine.HopRefusedException
import app.knotwork.android.data.engine.retry.RetryAfterHint
import app.knotwork.android.data.engine.retry.causeChain
import app.knotwork.android.data.mcp.McpHandshakeTimeoutException
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.time.Duration

/**
 * Names what went wrong when a connection check that sent a request failed.
 *
 * The rules follow the shapes measured on the clients the checks use: a Koog client reports an
 * HTTP answer as a [KoogHttpClientException] carrying the status and the body; an MCP server over
 * SSE as an [SSEClientException] carrying the response; one over Streamable HTTP as a
 * [StreamableHttpError] carrying the status, or `-1` for a body of the wrong type. Below HTTP,
 * Ktor reports a connect deadline as [ConnectTimeoutException] — a [ConnectException], so it is
 * told apart before a refused connection is — and a read deadline as a [SocketTimeoutException].
 * The two deadlines are told apart by type alone: Ktor's message for a read deadline quotes the
 * URL, and a URL can contain the word "connect".
 *
 * Opens nothing: it reads errors that already happened.
 */
internal object ConnectionFailureClassifier {

    /**
     * What the classifier needs besides the error.
     *
     * @property keySent Whether the check sent a key or other credentials.
     * @property requestedUrl The full address the failing request was sent to, for a 404.
     * @property retryAfterHeader The `Retry-After` the transport recorded, if any.
     * @property connectTimeout The connect deadline the request had.
     * @property socketTimeout The read deadline the request had.
     * @property mcp Whether the server was being asked as an MCP server, where an answer of the
     *   wrong kind means the address is not an MCP endpoint.
     */
    data class Context(
        val keySent: Boolean,
        val requestedUrl: String,
        val retryAfterHeader: String?,
        val connectTimeout: Duration,
        val socketTimeout: Duration,
        val mcp: Boolean,
    )

    /**
     * Classifies [error].
     *
     * @param error What the failed check threw.
     * @param context What the check knew about the request.
     * @param now The current time, to read a `Retry-After` given as a date.
     * @return The failure, [ConnectionFailure.Other] when nothing more specific fits.
     */
    fun classify(error: Throwable, context: Context, now: Instant = Instant.now()): ConnectionFailure {
        val chain = causeChain(error)
        val hop = chain.firstNotNullOfOrNull { it as? HopRefusedException }
        val handshake = chain.firstNotNullOfOrNull { it as? McpHandshakeTimeoutException }
        val answer = chain.firstNotNullOfOrNull(::httpAnswerOf)
        return when {
            hop != null -> ConnectionFailure.RedirectRefused(hop.message.orEmpty())
            handshake != null -> ConnectionFailure.HandshakeTimeout(handshake.after)
            answer != null -> byStatus(answer, error, context, now)
            chain.any(::isNotTheList) -> if (context.mcp) ConnectionFailure.NotMcp else ConnectionFailure.NoModelList
            else -> transportFailure(chain, context) ?: ConnectionFailure.Other(CloudErrorSanitizer.sanitize(error))
        }
    }

    /** An HTTP answer that was not a success, as one of the three clients reports it. */
    private class HttpAnswer(val status: Int, val body: String?, val retryAfter: String?)

    private fun httpAnswerOf(error: Throwable): HttpAnswer? = when (error) {
        is KoogHttpClientException -> error.statusCode?.let { HttpAnswer(it, error.errorBody, retryAfter = null) }
        is SSEClientException ->
            error.response
                ?.takeUnless { it.status.isSuccess() }
                ?.let { HttpAnswer(it.status.value, body = null, retryAfter = it.headers[HttpHeaders.RetryAfter]) }
        is StreamableHttpError ->
            error.code
                ?.takeIf { it > 0 }
                ?.let { HttpAnswer(it, body = null, retryAfter = null) }
        else -> null
    }

    private fun byStatus(answer: HttpAnswer, error: Throwable, context: Context, now: Instant): ConnectionFailure {
        val status = answer.status
        return when {
            status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN ->
                ConnectionFailure.Unauthorized(status, context.keySent)
            status == HTTP_BAD_REQUEST && answer.body.orEmpty().contains(GOOGLE_INVALID_KEY) ->
                ConnectionFailure.Unauthorized(status, context.keySent)
            status == HTTP_TOO_MANY_REQUESTS -> ConnectionFailure.RateLimited(
                RetryAfterHint.fromHeader(answer.retryAfter ?: context.retryAfterHeader, now)
                    ?: RetryAfterHint.fromMessages(error),
            )
            status in SERVER_ERRORS -> ConnectionFailure.ServerError(status)
            context.mcp -> ConnectionFailure.NotMcp
            status == HTTP_NOT_FOUND -> ConnectionFailure.NotFound(context.requestedUrl)
            else -> ConnectionFailure.UnexpectedAnswer(status, excerpt(answer.body))
        }
    }

    /** A success whose body is not what was asked for: the wrong content type, or the wrong shape. */
    private fun isNotTheList(error: Throwable): Boolean = when (error) {
        is SSEClientException -> error.response?.status?.isSuccess() == true
        is StreamableHttpError -> error.code?.let { it <= 0 } == true
        is SerializationException, is ContentConvertException, is NoTransformationFoundException -> true
        is NotTheListException -> true
        else -> false
    }

    private fun transportFailure(chain: List<Throwable>, context: Context): ConnectionFailure? = when {
        chain.any { it is UnknownHostException } -> ConnectionFailure.UnknownHost
        chain.any { it is ConnectTimeoutException } -> ConnectionFailure.ConnectTimeout(context.connectTimeout)
        chain.any { it is SocketTimeoutException } -> ConnectionFailure.Silence(context.socketTimeout)
        chain.any { it is ConnectException } && chain.any { it.message.orEmpty().contains("refused", true) } ->
            ConnectionFailure.ConnectionRefused
        else -> null
    }

    /**
     * The start of what a server said, for a status no other value covers: the `message` of a
     * JSON error body when there is one, the body otherwise; credentials removed, whitespace
     * collapsed, cut at [EXCERPT_CHARS].
     */
    private fun excerpt(body: String?): String? {
        val text = body?.let(::errorMessageOf)
            ?.let(CloudErrorSanitizer::redactSecrets)
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (text.length <= EXCERPT_CHARS) text else text.take(EXCERPT_CHARS).trimEnd() + "…"
    }

    /** `error.message`, `error` or `message` of a JSON body, or the body itself. */
    private fun errorMessageOf(body: String): String {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return body
        val error = json["error"]
        val message = (error as? JsonObject)?.get("message") ?: error ?: json["message"]
        return (message as? JsonPrimitive)?.contentOrNull ?: body
    }

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val SERVER_ERRORS = 500..599

    /** Google answers an unknown key with 400 and this reason (measured). */
    private const val GOOGLE_INVALID_KEY = "API_KEY_INVALID"

    /** How much of an unexpected answer is kept. */
    private const val EXCERPT_CHARS = 160

    private val WHITESPACE = Regex("\\s+")
}

/**
 * A server answered a connection check successfully with something that is not the list the check
 * asked for — for example a JSON object without the field the list lives in.
 *
 * @param what Which list was expected, for the log.
 */
internal class NotTheListException(what: String) : IOException("the answer is not $what")
