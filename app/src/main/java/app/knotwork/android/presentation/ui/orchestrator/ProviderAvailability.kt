package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.presentation.ui.pipeline.editor.config.CloudProviderMapper
import app.knotwork.design.components.pipelineeditor.ProviderChoices

/**
 * Which providers are set up on this device, for the node sheet's *Provider* and *Engine* fields.
 *
 * @property models Every provider set up here, with the model id it uses — `null` when it uses its
 *   default. A provider absent from the map is not set up here; a node may still choose it, since
 *   a pipeline can run on another device.
 * @property auto What a node set to *Auto* runs on here, or `null` when nothing qualifies.
 */
data class ProviderAvailability(val models: Map<CloudProvider, String?> = emptyMap(), val auto: CloudProvider? = null)

/**
 * The node sheet's view of [ProviderAvailability], in the catalog's provider vocabulary.
 *
 * @param defaultModel What a provider using its default model shows instead of an id.
 * @param onDeviceModel The on-device model's name, for the *Engine* field's first option.
 * @return The choices the sheet's provider fields read.
 */
internal fun ProviderAvailability.toProviderChoices(defaultModel: String, onDeviceModel: String?): ProviderChoices =
    ProviderChoices(
        configuredModels = models.entries.associate { (provider, model) ->
            CloudProviderMapper.toCatalog(provider) to (model ?: defaultModel)
        },
        autoResolvesTo = auto?.let(CloudProviderMapper::toCatalog),
        onDeviceModel = onDeviceModel,
    )
