package app.knotwork.android.data.network

import app.knotwork.android.domain.services.CleartextPolicy
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Refuses unencrypted requests to public hosts on the shared OkHttp client.
 *
 * The app's manifest permits cleartext app-wide, because Android's
 * `network_security_config.xml` cannot express the rule the product actually
 * needs ("any private-LAN address the user approved") — see [CleartextPolicy].
 * Moving the rule into app code means app code has to enforce it everywhere a
 * connection is opened, and this interceptor is the enforcement point for the
 * shared client: the `http_request` agent tool, model downloads, and Hugging
 * Face discovery.
 *
 * Unlike the per-connection gates on the Ollama and MCP paths, this runs on
 * **every** hop. [SharedHttpClient] registers it twice: as an application
 * interceptor, which refuses the first request before its host is even
 * resolved, and as a network interceptor, which sees each redirect OkHttp
 * follows — one that tries to downgrade an `https://` call to `http://`
 * mid-flight, the case the platform used to cover.
 *
 * Private-LAN cleartext is deliberately **not** gated here. The destinations
 * that reach this client are either public (downloads, discovery) or already
 * behind the `http_request` tool's own allowlist and per-call confirmation, and
 * duplicating the approved-origin lookup on every request would put a DataStore
 * read on the download path for no added protection.
 *
 * @property isPrivateHost predicate identifying loopback / private-LAN hosts;
 *   injected rather than called directly so the rule has one definition.
 */
class CleartextGuardInterceptor(private val isPrivateHost: (String) -> Boolean = CleartextPolicy::isPrivateHost) :
    Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps && !isPrivateHost(url.host)) {
            throw IOException(
                "Refusing an unencrypted request to ${url.host}: cleartext is permitted only to " +
                    "loopback and private-LAN addresses. Use https:// instead.",
            )
        }
        return chain.proceed(request)
    }
}
