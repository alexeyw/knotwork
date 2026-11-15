package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import app.knotwork.android.domain.models.CloudProvider

/**
 * The models the app offers for each hosted provider Koog lists, and each one's default — Koog's
 * own [LLModel] constants, each written once.
 *
 * It is prohibited to instantiate [LLModel] for these providers: a constant carries the
 * capabilities Koog's client routes by (OpenAI's Responses-only models, for one). The id a user
 * saved maps back to its constant through [offeredModels]; [KoogCloudModelCatalog] decides what
 * a saved id that is not offered falls back to.
 *
 * Every chat constant Koog declares for these four providers is either offered here or left out
 * with a reason: `CloudModelListGuardTest` holds the two in step, so a Koog upgrade fails the
 * build until its new models are sorted.
 */
object KoogModelMapper {

    /**
     * A provider's offer.
     *
     * @property default The model used when nothing is chosen, or the choice is not offered.
     *   Always one of [offered].
     * @property offered The models the provider's list shows, in that order.
     */
    private class Offer(val default: LLModel, val offered: List<LLModel>)

    private val OPENAI = Offer(
        default = OpenAIModels.Chat.GPT5_4,
        offered = listOf(
            OpenAIModels.Chat.GPT4o,
            OpenAIModels.Chat.GPT4oMini,
            OpenAIModels.Chat.GPT4_1,
            OpenAIModels.Chat.GPT4_1Mini,
            OpenAIModels.Chat.GPT5_1,
            OpenAIModels.Chat.GPT5_2,
            OpenAIModels.Chat.GPT5_2Pro,
            OpenAIModels.Chat.GPT5_3Codex,
            OpenAIModels.Chat.GPT5_4,
            OpenAIModels.Chat.GPT5_4Mini,
            OpenAIModels.Chat.GPT5_4Pro,
            OpenAIModels.Chat.GPT5_5,
            OpenAIModels.Chat.GPT5_6Sol,
            OpenAIModels.Chat.GPT5_6Terra,
            OpenAIModels.Chat.GPT5_6Luna,
        ),
    )

    private val ANTHROPIC = Offer(
        default = AnthropicModels.Sonnet_4_6,
        offered = listOf(
            AnthropicModels.Haiku_4_5,
            AnthropicModels.Sonnet_4_6,
            AnthropicModels.Sonnet_5,
            AnthropicModels.Opus_4_5,
            AnthropicModels.Opus_4_6,
            AnthropicModels.Opus_4_7,
            AnthropicModels.Opus_4_8,
            AnthropicModels.Opus_5,
            AnthropicModels.Fable_5,
        ),
    )

    private val GOOGLE = Offer(
        default = GoogleModels.Gemini3_Flash_Preview,
        offered = listOf(
            GoogleModels.Gemini2_5Flash,
            GoogleModels.Gemini2_5FlashLite,
            GoogleModels.Gemini3_Flash_Preview,
            GoogleModels.Gemini3_1Pro_Preview,
            GoogleModels.Gemini3_5Flash,
            GoogleModels.Gemini3_5FlashLite,
            GoogleModels.Gemini3_6Flash,
            GoogleModels.Gemini3_7Flash,
        ),
    )

    private val DEEPSEEK = Offer(
        default = DeepSeekModels.DeepSeekV4Flash,
        offered = listOf(
            DeepSeekModels.DeepSeekV4Flash,
            DeepSeekModels.DeepSeekV4Pro,
            DeepSeekModels.DeepSeekV4FlashVisionExp,
        ),
    )

    /** The offer of [provider], or `null` for a provider whose model is typed. */
    private fun offerOf(provider: CloudProvider): Offer? = when (provider) {
        CloudProvider.OPENAI -> OPENAI
        CloudProvider.ANTHROPIC -> ANTHROPIC
        CloudProvider.GOOGLE -> GOOGLE
        CloudProvider.DEEPSEEK -> DEEPSEEK
        CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OLLAMA, CloudProvider.OPENAI_COMPATIBLE -> null
    }

    /**
     * The models the app offers for [provider], in the order its list shows them.
     *
     * @param provider The provider.
     * @return Koog's constants, or `null` for a provider whose model is typed.
     */
    fun offeredModels(provider: CloudProvider): List<LLModel>? = offerOf(provider)?.offered

    /**
     * The model [provider] uses when nothing is chosen or the choice is not offered.
     *
     * @param provider The provider.
     * @return Koog's constant, or `null` for a provider whose model is typed.
     */
    fun defaultModel(provider: CloudProvider): LLModel? = offerOf(provider)?.default
}
