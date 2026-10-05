package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.domain.models.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.knotwork.design.components.pipelineeditor.CloudProvider as CatalogCloudProvider

/** Unit tests for [toProviderChoices] — what the node sheet's provider fields are told. */
class ProviderAvailabilityTest {

    @Test
    fun `given providers set up here then the sheet sees each with its model, or the default label`() {
        val choices = ProviderAvailability(
            models = mapOf(CloudProvider.OPENAI to null, CloudProvider.OPENAI_COMPATIBLE to "qwen2.5-7b-instruct"),
            auto = CloudProvider.OPENAI,
        ).toProviderChoices(defaultModel = "default model", onDeviceModel = "gemma")

        assertEquals(
            mapOf(
                CatalogCloudProvider.OPEN_AI to "default model",
                CatalogCloudProvider.OPENAI_COMPATIBLE to "qwen2.5-7b-instruct",
            ),
            choices.configuredModels,
        )
        assertEquals(CatalogCloudProvider.OPEN_AI, choices.autoResolvesTo)
        assertEquals("gemma", choices.onDeviceModel)
    }

    @Test
    fun `given nothing set up then auto resolves to nothing`() {
        val choices = ProviderAvailability().toProviderChoices(defaultModel = "default model", onDeviceModel = null)

        assertEquals(emptyMap<CatalogCloudProvider, String>(), choices.configuredModels)
        assertNull(choices.autoResolvesTo)
    }
}
