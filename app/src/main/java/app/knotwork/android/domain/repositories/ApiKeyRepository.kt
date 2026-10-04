package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.CloudProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * Credentials and per-provider configuration of the external LLM providers: the API key, the
 * chosen model id and, for a provider reached at an address the user enters
 * ([CloudProvider.usesBaseUrl]), that address.
 *
 * Every accessor is keyed by [CloudProvider] rather than spelled out once per provider. A
 * per-provider getter pair used to exist for each slot, and every consumer that needed "all
 * keys" or "all configured providers" wrote its own list of them — the stored-key filter of
 * `http_request` among them, which is a security control. A provider added to the enum now
 * reaches every consumer that iterates [CloudProvider.entries], instead of being silently left
 * out of a hand-written list.
 *
 * A slot a provider does not use (an API key for Ollama, a base URL for OpenAI) simply reads as
 * `null`; nothing ever writes it.
 */
interface ApiKeyRepository {

    /**
     * The saved API key of [provider].
     *
     * @param provider The provider whose key is read.
     * @return A Flow emitting the key, or `null` when none is saved.
     */
    fun getApiKey(provider: CloudProvider): Flow<String?>

    /**
     * Saves or removes the API key of [provider].
     *
     * @param provider The provider whose key is written.
     * @param key The key to save, or `null` to remove it.
     */
    suspend fun setApiKey(provider: CloudProvider, key: String?)

    /**
     * The model id the user chose for [provider].
     *
     * @param provider The provider whose model is read.
     * @return A Flow emitting the model id, or `null` when none is chosen — the model resolver
     *   then substitutes the provider's default.
     */
    fun getModel(provider: CloudProvider): Flow<String?>

    /**
     * Saves or removes the model id chosen for [provider].
     *
     * @param provider The provider whose model is written.
     * @param model The model id to save, or `null` to remove it.
     */
    suspend fun setModel(provider: CloudProvider, model: String?)

    /**
     * The server address of a provider reached at an address the user enters
     * ([CloudProvider.usesBaseUrl]: Ollama, an OpenAI-compatible server).
     *
     * @param provider The provider whose address is read.
     * @return A Flow emitting the address (e.g. `http://192.168.1.100:11434`), or `null` when
     *   none is saved.
     */
    fun getBaseUrl(provider: CloudProvider): Flow<String?>

    /**
     * Saves or removes the server address of [provider].
     *
     * @param provider The provider whose address is written.
     * @param url The address to save, or `null` to remove it.
     */
    suspend fun setBaseUrl(provider: CloudProvider, url: String?)

    /**
     * The context window requested from the Ollama server.
     *
     * @return A Flow emitting the window size; the default when none is saved.
     */
    fun getOllamaContextWindowSize(): Flow<Int>

    /**
     * Saves the context window requested from the Ollama server.
     *
     * @param size The context window size to save.
     */
    suspend fun setOllamaContextWindowSize(size: Int)
}

/**
 * The credential that decides whether [provider] is set up at all: its server address when it
 * is reached at one ([CloudProvider.usesBaseUrl]), otherwise its API key.
 *
 * One definition for every place that asks which credential a provider cannot do without —
 * [isConfigured] and the provider rows in Settings — so they cannot disagree about a provider
 * that has both an address and a key.
 *
 * @param provider The provider asked about.
 * @return A Flow emitting the deciding credential, or `null` when it is not saved.
 */
fun ApiKeyRepository.requiredCredential(provider: CloudProvider): Flow<String?> =
    if (provider.usesBaseUrl) getBaseUrl(provider) else getApiKey(provider)

/**
 * Whether [provider] is set up on this device: its deciding credential ([requiredCredential])
 * is saved and, for a provider without a default model ([CloudProvider.requiresModel]), so is
 * a model id. A provider that is not configured cannot be used, whatever else is true.
 *
 * The "Block network from local model" restriction is deliberately not part of it: that is a
 * rule about where traffic may go, not the provider's own configuration, and it is named
 * where a call is refused.
 *
 * @param provider The provider asked about.
 * @return `true` when nothing the provider itself needs is missing.
 */
suspend fun ApiKeyRepository.isConfigured(provider: CloudProvider): Boolean =
    !requiredCredential(provider).firstOrNull().isNullOrBlank() &&
        (!provider.requiresModel || !getModel(provider).firstOrNull().isNullOrBlank())
