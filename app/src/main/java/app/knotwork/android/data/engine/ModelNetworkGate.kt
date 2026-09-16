package app.knotwork.android.data.engine

import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.android.domain.services.LocalOnlyPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides whether a model request may leave the device right now.
 *
 * Every client that carries model traffic asks here before it is built: the chat clients
 * in [KoogClientFactory] (Cloud nodes, structured output, `delegate_task`) and both
 * network embedding providers (memory search and memory writes). Before this gate the
 * decision was repeated per call site, and two of the sites had drifted: the Ollama chat
 * client never checked the "Block network from local model" restriction, and neither
 * embedding provider checked it at all — nor did the Ollama embedding client check the
 * cleartext rule. A new client that talks to a model belongs behind this gate.
 *
 * Tools (MCP servers, `http_request`) are deliberately not behind it: the restriction is
 * about model providers, and those tools have their own controls.
 *
 * @property settingsRepository Source of the restriction flag and the approved cleartext
 *   origins, both read on every call so a change applies to the next request.
 */
@Singleton
class ModelNetworkGate @Inject constructor(private val settingsRepository: SettingsRepository) {

    /**
     * Decides whether a hosted cloud provider (OpenAI, Anthropic, Google, DeepSeek) may be
     * reached. Credentials are the caller's concern; this answers only the policy question.
     *
     * @return [CloudClientUnavailability.BlockedByLocalOnlyMode] while the restriction is
     *   on, otherwise `null`.
     */
    suspend fun cloudRefusal(): CloudClientUnavailability? {
        if (!isLocalOnlyMode()) return null
        Timber.i("ModelNetworkGate: cloud provider refused — local-only mode is on")
        return CloudClientUnavailability.BlockedByLocalOnlyMode
    }

    /**
     * Decides whether the user's Ollama server at [url] may be reached, applying both
     * rules that concern it: while the restriction is on the host must be local
     * ([LocalOnlyPolicy]), whatever the scheme; and unencrypted traffic must go only to an
     * approved private address ([CleartextPolicy]).
     *
     * @param url The configured, non-blank Ollama base URL.
     * @return The refusal, or `null` when a connection may be opened.
     */
    suspend fun ollamaRefusal(url: String): CloudClientUnavailability? {
        if (isLocalOnlyMode() && !LocalOnlyPolicy.isLocalEndpoint(url)) {
            val host = CleartextPolicy.hostOf(url)
            Timber.w("ModelNetworkGate: Ollama at %s refused — not a local address in local-only mode", host)
            return CloudClientUnavailability.EndpointNotLocal(host)
        }
        val verdict = CleartextPolicy.classify(url, settingsRepository.approvedCleartextOrigins.first())
        return CleartextPolicy.refusalMessage(verdict)?.let { reason ->
            Timber.w("ModelNetworkGate: Ollama refused — %s", reason)
            CloudClientUnavailability.CleartextRefused(reason)
        }
    }

    private suspend fun isLocalOnlyMode(): Boolean =
        settingsRepository.blockNetworkFromLocalModel.firstOrNull() ?: false
}
