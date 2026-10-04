package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-layer implementation of [CloudLlmModelResolver].
 *
 * Reads the user-configured model id for each provider from [ApiKeyRepository] and
 * substitutes a per-provider default when nothing is set. Centralising this here keeps
 * `CloudLlmNodeExecutor` free of both data-layer constants (`KoogModelMapper`,
 * `OpenAIModels`, …) and the per-provider model lookup.
 */
@Singleton
class KoogCloudLlmModelResolver @Inject constructor(private val apiKeyRepository: ApiKeyRepository) :
    CloudLlmModelResolver {

    /**
     * Resolves the Koog [LLModel] to use for a given provider. Exhaustive on
     * [CloudProvider] so the compiler flags any unhandled future provider.
     *
     * @param provider The typed [CloudProvider] to resolve a model object for.
     * @return The Koog [LLModel] cast to [Any] for the domain boundary.
     */
    override suspend fun resolveModel(provider: CloudProvider): Any {
        val chosen = apiKeyRepository.getModel(provider).first()
        return when (provider) {
            CloudProvider.OPENAI -> KoogModelMapper.getOpenAIModel(chosen ?: OpenAIModels.Chat.GPT5_4.id)
            CloudProvider.ANTHROPIC -> KoogModelMapper.getAnthropicModel(chosen ?: AnthropicModels.Sonnet_4_5.id)
            CloudProvider.GOOGLE -> KoogModelMapper.getGoogleModel(chosen ?: GoogleModels.Gemini3_Flash_Preview.id)
            CloudProvider.DEEPSEEK -> KoogModelMapper.getDeepSeekModel(chosen ?: DeepSeekModels.DeepSeekV4Flash.id)
            CloudProvider.OLLAMA -> LLModel(
                provider = LLMProvider.Ollama,
                id = chosen ?: "llama3",
                capabilities = listOf(LLMCapability.Completion),
                contextLength = apiKeyRepository.getOllamaContextWindowSize().first().toLong(),
            )
            // No default: the factory refuses a client while no model is chosen
            // (`MissingModel`), so reaching here without one means it was cleared in between.
            CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OPENAI_COMPATIBLE ->
                openAiCompatibleModel(checkNotNull(chosen) { "No model is selected for '${provider.id}'" })
        }
    }

    /**
     * A model asked for through the OpenAI API by an id the app has no catalogue entry for,
     * with exactly what a chat needs: `Completion` and the Chat Completions endpoint (without
     * the latter Koog refuses the request before sending it).
     *
     * Nothing the server does not declare is claimed. The app sends these models no tool
     * definitions, no images and no response format, so claiming more would change nothing —
     * or, for a JSON schema, switch off the app's own repair of structured output.
     *
     * @param modelId The chosen id, sent to the server as it is.
     * @return The Koog model.
     */
    private fun openAiCompatibleModel(modelId: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = modelId,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Temperature,
        ),
    )
}
