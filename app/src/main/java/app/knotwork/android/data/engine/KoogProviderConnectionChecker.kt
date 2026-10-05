package app.knotwork.android.data.engine

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.get
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.llm.LLModel
import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.engine.retry.RetryAfterSlot
import app.knotwork.android.data.network.ConnectionFailureClassifier
import app.knotwork.android.data.network.NotTheListException
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionPreconditions
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.connection.ProviderConnectionChecker
import app.knotwork.android.domain.connection.ProviderConnectionDraft
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.services.CleartextPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * [ProviderConnectionChecker] on the Koog clients a run uses: the model list of each provider,
 * asked once, with the values in the form.
 *
 * - **The same path as a run.** The client is the one [KoogClientFactory] builds, on a
 *   [KoogTransportFactory] holding the rule of [ModelNetworkGate.hopRule] — so every hop,
 *   redirects included, is judged as a run's would be — with [CloudClientTimeouts.CONFIG]. What
 *   differs is deliberate: no retry policy (a check is one attempt) and no model requirement (the
 *   check is how a model is chosen).
 * - **No prompt, no tokens.** A provider is asked for its model list; Ollama for `/api/tags`,
 *   which is one request where Koog's own listing asks once more per model. OpenRouter answers its
 *   model list without looking at the key (measured: 200 with an invalid key, and with none), so
 *   its key is first checked at `/api/v1/key`, which answers 401 to a key it does not know.
 * - **Nothing sent, nothing shown.** A check refused by [ConnectionPreconditions] — or by "Block
 *   network from local model" for a hosted provider — returns before the privacy indicator hears
 *   of it; a check that sends records the request first.
 *
 * @property clientFactory Builds the provider's client, exactly as for a run.
 * @property modelNetworkGate The rules of the moment, applied before sending and on every hop.
 * @property networkActivityTracker The privacy indicator, told of every check that sends.
 */
@Singleton
class KoogProviderConnectionChecker @Inject constructor(
    private val clientFactory: KoogClientFactory,
    private val modelNetworkGate: ModelNetworkGate,
    private val networkActivityTracker: NetworkActivityTracker,
) : ProviderConnectionChecker {

    private val httpFactory = KtorKoogHttpClient.Factory()

    /**
     * Builds the transport of one check from the slot its `Retry-After` goes to and the rule of
     * the moment. Replaced in tests, to route a hosted provider's fixed address to a local server
     * behind the real transport.
     */
    @VisibleForTesting
    internal var transportFor: (RetryAfterSlot, ModelHopRule) -> KoogHttpClient.Factory =
        { retryAfter, rule -> KoogTransportFactory(httpFactory, retryAfter, rule) }

    override fun destination(provider: CloudProvider, baseUrl: String?): String? =
        listingUrl(provider, baseUrl.usable())?.let(CleartextPolicy::hostOf)

    override suspend fun check(provider: CloudProvider, draft: ProviderConnectionDraft): ConnectionCheckResult {
        val apiKey = draft.apiKey.usable().takeIf { provider.usesApiKey }
        val baseUrl = draft.baseUrl.usable().takeIf { provider.usesBaseUrl }
        val rule = modelNetworkGate.hopRule()
        val refusal = ConnectionPreconditions.provider(
            provider = provider,
            draft = ProviderConnectionDraft(apiKey = apiKey, baseUrl = baseUrl),
            approvedCleartextOrigins = rule.approvedCleartextOrigins,
            localOnly = rule.localOnly,
        ) ?: ConnectionRefusal.BlockedByLocalOnlyMode.takeIf { rule.localOnly && !provider.usesBaseUrl }
        if (refusal != null) return ConnectionCheckResult.Refused(refusal)

        val retryAfter = RetryAfterSlot()
        val listing = Listing(transportFor(retryAfter, rule), apiKey, baseUrl)
        val host = destination(provider, baseUrl) ?: baseUrl.orEmpty()
        networkActivityTracker.recordOutbound()
        return try {
            ConnectionCheckResult.Reachable(listing.of(provider))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Connection check of %s failed", provider.id)
            val context = ConnectionFailureClassifier.Context(
                keySent = apiKey != null,
                requestedUrl = listing.url,
                retryAfterHeader = retryAfter.take(),
                connectTimeout = CloudClientTimeouts.CONFIG.connectTimeoutMillis.milliseconds,
                socketTimeout = CloudClientTimeouts.CONFIG.socketTimeoutMillis.milliseconds,
                mcp = false,
            )
            ConnectionCheckResult.Failed(host, ConnectionFailureClassifier.classify(e, context))
        }
    }

    /**
     * The requests of one check, keeping the address of the one in flight for a 404.
     *
     * @property transport The per-check transport.
     * @property apiKey The key entered, if any.
     * @property baseUrl The address entered, for a server the user runs.
     */
    private inner class Listing(
        private val transport: KoogHttpClient.Factory,
        private val apiKey: String?,
        private val baseUrl: String?,
    ) {
        /** The full address of the last request sent. */
        var url: String = ""
            private set

        /** The model ids [provider] serves. */
        suspend fun of(provider: CloudProvider): List<String> = when (provider) {
            CloudProvider.OLLAMA -> ollamaModels()
            CloudProvider.OPENROUTER -> {
                openRouterKey()
                models(provider)
            }
            else -> models(provider)
        }

        private suspend fun models(provider: CloudProvider): List<String> {
            url = checkNotNull(listingUrl(provider, baseUrl)) { "a listing address for ${provider.id}" }
            return clientFactory.checkClient(provider, apiKey, baseUrl, transport).use { client ->
                client.models().map(LLModel::id)
            }
        }

        private suspend fun ollamaModels(): List<String> {
            val base = checkNotNull(baseUrl) { "an Ollama address" }
            url = join(base, OLLAMA_TAGS_PATH)
            val body = http(base, headers = emptyMap()).use { it.get<String>(OLLAMA_TAGS_PATH) }
            return ollamaNames(body)
        }

        private suspend fun openRouterKey() {
            val base = OpenAiCompatibleClients.OPENROUTER_BASE_URL
            url = join(base, OPENROUTER_KEY_PATH)
            val key = checkNotNull(apiKey) { "an OpenRouter key" }
            http(base, headers = mapOf(AUTHORIZATION to "Bearer $key")).use { it.get<String>(OPENROUTER_KEY_PATH) }
        }

        private fun http(base: String, headers: Map<String, String>): KoogHttpClient = transport.create(
            clientName = CLIENT_NAME,
            baseUrl = base,
            headers = headers,
            requestTimeoutMillis = CloudClientTimeouts.CONFIG.requestTimeoutMillis,
            connectTimeoutMillis = CloudClientTimeouts.CONFIG.connectTimeoutMillis,
            socketTimeoutMillis = CloudClientTimeouts.CONFIG.socketTimeoutMillis,
        )
    }

    internal companion object {
        /** Ollama's model list, relative to its address — the path Koog's Ollama client lists from. */
        const val OLLAMA_TAGS_PATH = "api/tags"

        /** OpenRouter's key check, relative to its API address. */
        const val OPENROUTER_KEY_PATH = "v1/key"

        private const val CLIENT_NAME = "KnotworkConnectionCheck"
        private const val AUTHORIZATION = "Authorization"

        /**
         * The full address of the model list a check of [provider] asks for.
         *
         * @param provider The provider.
         * @param baseUrl The address entered, for a server the user runs.
         * @return The address, or `null` for a server the user runs without one.
         */
        fun listingUrl(provider: CloudProvider, baseUrl: String?): String? = when (provider) {
            CloudProvider.OPENAI -> OpenAIClientSettings().let { join(it.baseUrl, it.modelsPath) }
            CloudProvider.ANTHROPIC -> AnthropicClientSettings().let { join(it.baseUrl, it.modelsPath) }
            CloudProvider.GOOGLE -> GoogleClientSettings().let { join(it.baseUrl, it.defaultPath) }
            CloudProvider.DEEPSEEK -> DeepSeekClientSettings().let { join(it.baseUrl, it.modelsPath) }
            CloudProvider.OLLAMA -> baseUrl?.let { join(it, OLLAMA_TAGS_PATH) }
            CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OPENAI_COMPATIBLE ->
                if (provider.usesBaseUrl && baseUrl == null) {
                    null
                } else {
                    OpenAiCompatibleClients.settings(provider, baseUrl, CloudClientTimeouts.CONFIG)
                        .let { join(it.baseUrl, it.modelsPath) }
                }
        }

        /**
         * The model names in Ollama's `/api/tags` answer (`{"models":[{"name":…}]}`).
         *
         * @throws NotTheListException when the answer is JSON of another shape.
         * @throws SerializationException when it is not JSON.
         */
        fun ollamaNames(body: String): List<String> {
            val models = (Json.parseToJsonElement(body) as? JsonObject)?.get("models") as? JsonArray
                ?: throw NotTheListException("an Ollama model list")
            return models.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        }

        /** [base] and [path] joined by one slash — how the clients resolve a path against an address. */
        private fun join(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

        /** The trimmed value, or `null` when nothing is left. */
        private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
