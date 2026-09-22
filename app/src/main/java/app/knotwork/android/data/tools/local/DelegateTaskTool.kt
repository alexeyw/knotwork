package app.knotwork.android.data.tools.local

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.data.engine.KoogModelMapper
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * A specialized tool designed for the main (local) AI agent to delegate complex or
 * specialized tasks to powerful external Large Language Models (LLMs) such as Claude,
 * OpenAI, or Gemini, through the KoogClientFactory.
 *
 * This class exposes a function that the local agent can call. When called,
 * it routes the prompt to the specified external model, awaits the response asynchronously,
 * generates a semantic text embedding for the resulting response, and finally saves
 * both the text and its embedding into the local long-term memory via [MemoryRepository].
 *
 * This allows the local agent to remain lightweight and responsive, while offloading
 * computationally expensive or highly specialized reasoning to cloud models.
 *
 * @property koogClientFactory A factory used to instantiate the appropriate external LLM client.
 * @property memoryRepository The repository responsible for persisting long-term memories.
 * @property embeddingProviderResolver Resolves the user's active embedding provider so the saved
 *   chunk shares the same embedding space as every other memory write — otherwise retrieval, which
 *   embeds the query with that same active provider, could never match a delegated result.
 * @property apiKeyRepository The repository responsible for persisting selected model configurations.
 */
class DelegateTaskTool @Inject constructor(
    private val koogClientFactory: KoogClientFactory,
    private val memoryRepository: MemoryRepository,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val apiKeyRepository: ApiKeyRepository,
) {

    /**
     * Executes the task delegation process.
     *
     * It performs the following steps:
     * 1. Validates the target model.
     * 2. Instantiates the client for the target model.
     * 3. Sends the prompt to the external model with a 60-second timeout to avoid blocking.
     * 4. Processes the response, generates an embedding, and saves it to the memory repository.
     *
     * The entire operation is wrapped in a [Dispatchers.IO] context to prevent blocking
     * the main thread or the agent's Foreground Service.
     *
     * @param taskDescription A detailed explanation of the task to be delegated. This will be used as the prompt for the external LLM.
     * @param targetModel The identifier for the external model to use. Supported values: "anthropic", "openai", "google", "deepseek", "ollama". Defaults to "google".
     * @return A summary string detailing the outcome of the delegation, including whether it succeeded, timed out, or encountered an error. This summary is returned back to the calling agent.
     */
    suspend fun executeDelegation(taskDescription: String, targetModel: String = CloudProvider.GOOGLE.id): String =
        withContext(Dispatchers.IO) {
            // Tool arguments arrive as raw JSON strings produced by the LLM, so the
            // provider id is parsed (with legacy aliases) on the way in. Unknown ids
            // surface as a typed error rather than silently falling through.
            val provider = CloudProvider.fromId(targetModel)
                ?: return@withContext "Error: Unsupported target model '$targetModel'." +
                    " Supported models: anthropic, openai, google, deepseek, ollama."

            val client = when (provider) {
                CloudProvider.ANTHROPIC -> koogClientFactory.createAnthropicExecutor()
                CloudProvider.OPENAI -> koogClientFactory.createOpenAIExecutor()
                CloudProvider.GOOGLE -> koogClientFactory.createGoogleExecutor()
                CloudProvider.DEEPSEEK -> koogClientFactory.createDeepSeekExecutor()
                CloudProvider.OLLAMA -> koogClientFactory.createOllamaExecutor()
            }

            if (client == null) {
                // Name the real cause — a missing key, the "Block network from local model"
                // restriction, or a refused address — so the model can relay the right remedy.
                val cause = koogClientFactory.unavailabilityOf(provider)?.message(provider)
                    ?: "Check the provider's settings."
                return@withContext "Error: Client for '${provider.id}' could not be initialized. $cause"
            }

            return@withContext try {
                val model = when (provider) {
                    CloudProvider.ANTHROPIC -> KoogModelMapper.getAnthropicModel(
                        apiKeyRepository.getAnthropicModel().first() ?: AnthropicModels.Sonnet_4_5.id,
                    )

                    CloudProvider.OPENAI -> KoogModelMapper.getOpenAIModel(
                        apiKeyRepository.getOpenAIModel().first() ?: OpenAIModels.Chat.GPT5_4.id,
                    )

                    CloudProvider.GOOGLE -> KoogModelMapper.getGoogleModel(
                        apiKeyRepository.getGoogleModel().first() ?: GoogleModels.Gemini3_Flash_Preview.id,
                    )

                    CloudProvider.DEEPSEEK -> KoogModelMapper.getDeepSeekModel(
                        apiKeyRepository.getDeepSeekModel().first() ?: DeepSeekModels.DeepSeekV4Flash.id,
                    )

                    CloudProvider.OLLAMA -> LLModel(
                        provider = LLMProvider.Ollama,
                        id = apiKeyRepository.getOllamaModelName().first() ?: "llama3",
                        capabilities = listOf(
                            LLMCapability.Completion,
                        ),
                        contextLength = apiKeyRepository.getOllamaContextWindowSize().first().toLong(),
                    )
                }

                // Apply a 60-second timeout for the external API call
                val result = withTimeoutOrNull(LLM_CALL_TIMEOUT_MS) {
                    val stream = client.executeStreaming(prompt("default") { user(taskDescription) }, model)
                    stream.mapNotNull { frame -> (frame as? StreamFrame.TextDelta)?.text }.toList().joinToString("")
                }

                if (result.isNullOrBlank()) {
                    "Error: Task delegation to '${provider.id}' timed out or returned empty after 60 seconds."
                } else {
                    val responseText = result
                    // Persisting to long-term memory is a best-effort side effect:
                    // the embed call can hit the network under a cloud provider and
                    // fail. Never let that discard the hard-won delegated result —
                    // catch and report, but still return the response to the caller.
                    // CancellationException is rethrown to preserve structured
                    // concurrency.
                    val savedToMemory = try {
                        val embedding = embeddingProviderResolver.resolve().embed(responseText)
                        memoryRepository.saveMemory(responseText, embedding)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to persist delegated result to memory; returning the result anyway")
                        false
                    }

                    val memoryNote = if (savedToMemory) "and saved to memory" else "(memory save failed)"
                    "Success: Task completed by '${provider.id}' $memoryNote. " +
                        "Summary of response: ${responseText.take(RESPONSE_PREVIEW_CHAR_LIMIT)}..."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The result becomes the node's output, the console line and the model's
                // next observation — scrubbed, since a provider error can quote its key.
                "Error: Task delegation failed due to an exception: ${CloudErrorSanitizer.sanitize(e)}"
            }
        }

    private companion object {
        /** Maximum wall-clock time, in milliseconds, allowed for a single delegated cloud-LLM call. */
        const val LLM_CALL_TIMEOUT_MS: Long = 60_000L

        /** Maximum number of characters of the delegated response previewed in the success message. */
        const val RESPONSE_PREVIEW_CHAR_LIMIT: Int = 100
    }
}
