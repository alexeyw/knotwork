package app.knotwork.android.data.engine

import app.knotwork.android.domain.engine.CloudModelFallback
import app.knotwork.android.domain.engine.CloudModelInUse
import app.knotwork.android.domain.models.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KoogCloudModelCatalog] — which model a run of each provider uses for what the user
 * saved, the answer every screen naming a provider's model and the resolver share.
 */
class KoogCloudModelCatalogTest {

    private val catalog = KoogCloudModelCatalog()

    /** The providers whose model is picked from a built-in list. */
    private val listed =
        listOf(CloudProvider.OPENAI, CloudProvider.ANTHROPIC, CloudProvider.GOOGLE, CloudProvider.DEEPSEEK)

    @Test
    fun `given a provider with a built-in list then its default is one of the models it offers`() {
        listed.forEach { provider ->
            val offered = checkNotNull(KoogModelMapper.offeredModels(provider)) { "$provider offers no list" }
            val default = checkNotNull(KoogModelMapper.defaultModel(provider)) { "$provider has no default" }

            assertTrue("$provider's default is not offered.", offered.any { it === default })
            assertEquals("$provider offers an id twice.", offered.size, offered.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `given a provider with a built-in list then the catalog offers exactly the mapper's ids`() {
        listed.forEach { provider ->
            assertEquals(KoogModelMapper.offeredModels(provider)?.map { it.id }, catalog.offeredModels(provider))
        }
    }

    @Test
    fun `given a provider whose model is typed then nothing is offered`() {
        CloudProvider.entries.filterNot { it in listed }.forEach { provider ->
            assertNull("$provider's model is typed.", catalog.offeredModels(provider))
            assertNull(KoogModelMapper.defaultModel(provider))
        }
    }

    @Test
    fun `given a built-in list and nothing saved then runs use the default`() {
        listed.forEach { provider ->
            val expected = CloudModelInUse(KoogModelMapper.defaultModel(provider)!!.id, CloudModelFallback.NotChosen)

            assertEquals(expected, catalog.modelInUse(provider, null))
            assertEquals("A blank id counts as nothing saved.", expected, catalog.modelInUse(provider, "  "))
        }
    }

    @Test
    fun `given a built-in list and an offered model saved then runs use it`() {
        listed.forEach { provider ->
            KoogModelMapper.offeredModels(provider)!!.forEach { model ->
                assertEquals(CloudModelInUse(model.id), catalog.modelInUse(provider, model.id))
            }
        }
    }

    @Test
    fun `given a built-in list and a model it no longer offers then runs use the default and say why`() {
        val inUse = catalog.modelInUse(CloudProvider.ANTHROPIC, "claude-3-opus")

        assertEquals(
            CloudModelInUse("claude-sonnet-4-6", CloudModelFallback.NoLongerOffered("claude-3-opus")),
            inUse,
        )
    }

    @Test
    fun `given an OpenAI model shut down on 23 October saved then runs use the default and say why`() {
        listOf("o1", "o3-mini", "o4-mini", "gpt-4.1-nano").forEach { retiring ->
            assertEquals(
                CloudModelInUse("gpt-5.4", CloudModelFallback.NoLongerOffered(retiring)),
                catalog.modelInUse(CloudProvider.OPENAI, retiring),
            )
        }
    }

    @Test
    fun `given a model shut down on 30 November or 11 December saved then runs use the default and say why`() {
        // The ids as 0.14.0 saved them: a choice kept in settings or carried in a backup.
        val retiring = mapOf(
            CloudProvider.ANTHROPIC to listOf("claude-sonnet-4-5"),
            CloudProvider.OPENAI to listOf("gpt-5", "gpt-5-mini", "gpt-5-nano", "o3"),
        )
        val defaults = mapOf(CloudProvider.ANTHROPIC to "claude-sonnet-4-6", CloudProvider.OPENAI to "gpt-5.4")

        retiring.forEach { (provider, ids) ->
            ids.forEach { saved ->
                assertEquals(
                    CloudModelInUse(defaults.getValue(provider), CloudModelFallback.NoLongerOffered(saved)),
                    catalog.modelInUse(provider, saved),
                )
            }
        }
    }

    @Test
    fun `given Ollama then a typed model is used as it is and nothing typed means its default`() {
        assertEquals(CloudModelInUse("qwen3:8b"), catalog.modelInUse(CloudProvider.OLLAMA, "qwen3:8b"))
        assertEquals(
            CloudModelInUse("llama3", CloudModelFallback.NotChosen),
            catalog.modelInUse(CloudProvider.OLLAMA, null),
        )
        assertEquals(
            CloudModelInUse("llama3", CloudModelFallback.NotChosen),
            catalog.modelInUse(CloudProvider.OLLAMA, ""),
        )
    }

    @Test
    fun `given a provider without a default then only a saved model is used`() {
        listOf(CloudProvider.OPENROUTER, CloudProvider.GROQ, CloudProvider.OPENAI_COMPATIBLE).forEach { provider ->
            assertNull("$provider has no default.", catalog.modelInUse(provider, null))
            assertNull("A blank id counts as nothing saved.", catalog.modelInUse(provider, " "))
            assertEquals(CloudModelInUse("any/model-id"), catalog.modelInUse(provider, "any/model-id"))
        }
    }
}
