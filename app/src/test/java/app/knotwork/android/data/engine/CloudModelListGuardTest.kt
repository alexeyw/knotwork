package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import app.knotwork.android.domain.models.CloudProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The cloud model lists against Koog's own catalogue: every model the app offers is one of Koog's chat
 * constants, and every chat constant Koog declares for the four providers it lists is either offered or
 * left out here with a reason.
 *
 * The lists were written by hand and fell behind silently: a Koog upgrade added 22 chat models and the
 * app offered none of them, while models their providers had retired stayed on offer. With this guard, a
 * Koog upgrade that adds or drops a model fails the build until the list is sorted.
 */
class CloudModelListGuardTest {

    /**
     * Koog's chat constants per provider: every static [LLModel] field of the object that declares them.
     * Read by reflection rather than from Koog's `models` lists, which Koog keeps by hand and which mix in
     * embedding, audio and moderation models.
     */
    private val koogChatModels: Map<CloudProvider, List<LLModel>> = mapOf(
        CloudProvider.OPENAI to constantsOf(OpenAIModels.Chat::class.java),
        CloudProvider.ANTHROPIC to constantsOf(AnthropicModels::class.java),
        CloudProvider.GOOGLE to constantsOf(GoogleModels::class.java),
        CloudProvider.DEEPSEEK to constantsOf(DeepSeekModels::class.java),
    )

    /**
     * The chat constants the app deliberately does not offer, each with its reason. "Retired" is the
     * provider's own deprecation page or a request with a real key answering "not found"; "shutdown
     * announced" is a date on that page — the app adds no model already scheduled to go, and a release
     * drops an offered model whose date falls within a week of it or before the next planned release.
     */
    private val notOffered: Map<CloudProvider, Map<LLModel, String>> = mapOf(
        CloudProvider.OPENAI to mapOf(
            OpenAIModels.Chat.O1 to "shutdown announced for 2026-10-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.O3Mini to "shutdown announced for 2026-10-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.O4Mini to "shutdown announced for 2026-10-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT4_1Nano to "shutdown announced for 2026-10-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5Codex to "retired 2026-07-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5_1Codex to "retired 2026-07-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5_1CodexMax to "retired 2026-07-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5_2Codex to "retired 2026-07-23 (OpenAI deprecations page)",
            OpenAIModels.Chat.O3 to "shutdown announced for 2026-12-11 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5 to "shutdown announced for 2026-12-11 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5Mini to "shutdown announced for 2026-12-11 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5Nano to "shutdown announced for 2026-12-11 (OpenAI deprecations page)",
            OpenAIModels.Chat.GPT5Pro to "shutdown announced for 2026-12-11",
            OpenAIModels.Chat.GPT5_4Nano to "shutdown announced for 2027-04-01",
            OpenAIModels.Chat.GPT5_5Pro to "Responses API only, and no request to it has been measured",
        ),
        CloudProvider.ANTHROPIC to mapOf(
            AnthropicModels.Sonnet_4 to "retired 2026-06-15 (Anthropic model deprecations page)",
            AnthropicModels.Opus_4 to "retired 2026-06-15 (Anthropic model deprecations page)",
            AnthropicModels.Opus_4_1 to "retired 2026-08-05 (Anthropic model deprecations page)",
            AnthropicModels.Sonnet_4_5 to "shutdown announced for 2026-11-30 (Anthropic model deprecations page)",
        ),
        CloudProvider.GOOGLE to mapOf(
            GoogleModels.Gemini2_5Pro to "answers 404 to a key that had not used it (measured 2026-10-12)",
            GoogleModels.Gemini2_0FlashLite001 to "retired: answers 404 (measured 2026-10-12)",
            GoogleModels.Gemini3_1FlashLite_Preview to "shutdown announced for 2026-05-25",
            GoogleModels.Gemini3_1FlashLite to "shutdown announced for 2027-05-07",
        ),
        CloudProvider.DEEPSEEK to emptyMap(),
    )

    @Test
    fun `given Koog's catalogue then every provider's chat constants are found`() {
        // Not vacuous: reflection that found nothing would let every other test here pass.
        assertTrue(OpenAIModels.Chat.GPT5_4 in koogChatModels.getValue(CloudProvider.OPENAI))
        assertTrue(AnthropicModels.Sonnet_4_6 in koogChatModels.getValue(CloudProvider.ANTHROPIC))
        assertTrue(GoogleModels.Gemini3_Flash_Preview in koogChatModels.getValue(CloudProvider.GOOGLE))
        assertTrue(DeepSeekModels.DeepSeekV4Flash in koogChatModels.getValue(CloudProvider.DEEPSEEK))
    }

    @Test
    fun `given the app's lists then every offered model is one of Koog's chat constants for its provider`() {
        val strays = koogChatModels.flatMap { (provider, constants) ->
            offeredOf(provider).filterNot { offered -> constants.any { it === offered } }
                .map { "${provider.id}: ${it.id}" }
        }

        assertTrue(
            "Offered but not a Koog chat constant of the provider — a hand-built LLModel loses the " +
                "capabilities Koog routes by: $strays",
            strays.isEmpty(),
        )
    }

    @Test
    fun `given Koog's catalogue then every chat constant is offered or left out with a reason`() {
        val unsorted = koogChatModels.flatMap { (provider, constants) ->
            val offered = offeredOf(provider)
            val excluded = notOffered.getValue(provider)
            constants.filter { constant -> offered.none { it === constant } && constant !in excluded }
                .map { "${provider.id}: ${it.id}" }
        }

        assertTrue(
            "Koog declares these chat models and the app neither offers them nor says why not — add each " +
                "to KoogModelMapper, or to notOffered with its reason: $unsorted",
            unsorted.isEmpty(),
        )
    }

    @Test
    fun `given the list of models left out then each is a Koog chat constant, not offered, with a reason`() {
        val wrong = notOffered.flatMap { (provider, excluded) ->
            val constants = koogChatModels.getValue(provider)
            val offered = offeredOf(provider)
            excluded.mapNotNull { (model, reason) ->
                when {
                    constants.none { it === model } -> "${provider.id}: ${model.id} is not a chat constant of it"
                    offered.any { it === model } -> "${provider.id}: ${model.id} is offered and left out at once"
                    reason.isBlank() -> "${provider.id}: ${model.id} has no reason"
                    else -> null
                }
            }
        }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The models the app offers for [provider]. */
    private fun offeredOf(provider: CloudProvider): List<LLModel> =
        checkNotNull(KoogModelMapper.offeredModels(provider)) { "${provider.id} offers no built-in list" }

    /** Every static [LLModel] field declared by [holder] — Koog declares its constants there. */
    private fun constantsOf(holder: Class<*>): List<LLModel> = holder.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == LLModel::class.java }
        .map { field ->
            field.isAccessible = true
            field.get(null) as LLModel
        }
}
