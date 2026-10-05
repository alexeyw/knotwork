package app.knotwork.android.data.mcp

import app.knotwork.android.data.network.ConnectionFailureClassifier
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionPreconditions
import app.knotwork.android.domain.connection.McpConnectionChecker
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.services.CleartextPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * [McpConnectionChecker] on a client of its own: connect with the values in the form, list the
 * tools, disconnect.
 *
 * The client comes from [McpClientFactory] — the same [KoogMcpClient] the agent uses, with its
 * handshake deadline, its limits on what a server sends, and its report to the privacy indicator —
 * but it never enters [McpConnectionPool]. The pool keys connections by saved server, and a form
 * being edited is not one: a check through the pool would replace the live connection of the
 * server being edited with one built from unsaved values.
 *
 * The cleartext rule is the one the pool applies when it connects; "Block network from local model"
 * does not cover MCP servers.
 *
 * @property clientFactory Creates the throwaway client.
 * @property networkSettings The approved unencrypted origins.
 */
@Singleton
class KoogMcpConnectionChecker @Inject constructor(
    private val clientFactory: McpClientFactory,
    private val networkSettings: NetworkSettings,
) : McpConnectionChecker {

    override suspend fun check(config: McpServerConfig): ConnectionCheckResult {
        ConnectionPreconditions.mcp(config.url, networkSettings.approvedCleartextOrigins.first())
            ?.let { return ConnectionCheckResult.Refused(it) }
        val host = CleartextPolicy.hostOf(config.url) ?: config.url.trim()
        val client = clientFactory.create()
        return try {
            client.connect(config.copy(url = config.url.trim()))
            ConnectionCheckResult.Reachable(client.getTools().map { it.name })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "MCP connection check of %s failed", host)
            ConnectionCheckResult.Failed(host, ConnectionFailureClassifier.classify(e, contextFor(config)))
        } finally {
            withContext(NonCancellable) { disconnect(client) }
        }
    }

    /** Releases [client]; a server that cannot be told the session ended does not fail the check. */
    private suspend fun disconnect(client: McpClient) {
        try {
            client.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "MCP connection check could not disconnect cleanly")
        }
    }

    /** What the classifier needs to know about a check of [config]. */
    private fun contextFor(config: McpServerConfig) = ConnectionFailureClassifier.Context(
        keySent = config.auth != McpAuth.None ||
            config.headers.keys.any { it.equals(AUTHORIZATION, ignoreCase = true) },
        requestedUrl = config.url.trim(),
        retryAfterHeader = null,
        connectTimeout = KoogMcpClient.CONNECT_TIMEOUT_MS.milliseconds,
        socketTimeout = (KoogMcpClient.TOOL_CALL_TIMEOUT_MS + KoogMcpClient.SOCKET_TIMEOUT_SLACK_MS).milliseconds,
        mcp = true,
    )

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
