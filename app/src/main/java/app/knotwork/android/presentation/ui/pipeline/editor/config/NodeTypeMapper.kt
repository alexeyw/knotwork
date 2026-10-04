package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.CloudProvider as DomainCloudProvider
import app.knotwork.android.domain.models.NodeType as DomainNodeType
import app.knotwork.design.components.pipelineeditor.CloudProvider as CatalogCloudProvider
import app.knotwork.design.components.pipelineeditor.NodeType as CatalogNodeType

/**
 * Bridges between the production-domain [DomainNodeType] (`app.knotwork.android.domain.models.NodeType`)
 * and the design-catalog [CatalogNodeType] (`app.knotwork.design.components.pipelineeditor.NodeType`).
 *
 * The catalog enum is intentionally independent of `:app` so the design module has zero coupling
 * to production code (`pipelineeditor/NodeType.kt`). The two enums are kept name-for-name aligned
 * — adding a new node type requires editing both — and this mapper is the single translation
 * point used by the editor screen.
 */
internal object NodeTypeMapper {

    /**
     * Maps a domain node type onto its catalog counterpart.
     *
     * @return the catalog enum entry with the same conceptual identity.
     */
    fun toCatalog(type: DomainNodeType): CatalogNodeType = when (type) {
        DomainNodeType.INPUT -> CatalogNodeType.INPUT
        DomainNodeType.OUTPUT -> CatalogNodeType.OUTPUT
        DomainNodeType.LITE_RT -> CatalogNodeType.LITE_RT
        DomainNodeType.CLOUD -> CatalogNodeType.CLOUD
        DomainNodeType.INTENT_ROUTER -> CatalogNodeType.INTENT_ROUTER
        DomainNodeType.IF_CONDITION -> CatalogNodeType.IF_CONDITION
        DomainNodeType.CLARIFICATION -> CatalogNodeType.CLARIFICATION
        DomainNodeType.TOOL -> CatalogNodeType.TOOL
        DomainNodeType.DECOMPOSITION -> CatalogNodeType.DECOMPOSITION
        DomainNodeType.QUEUE_PROCESSOR -> CatalogNodeType.QUEUE_PROCESSOR
        DomainNodeType.EVALUATION -> CatalogNodeType.EVALUATION
        DomainNodeType.SUMMARY -> CatalogNodeType.SUMMARY
        DomainNodeType.PIPELINE -> CatalogNodeType.PIPELINE
        DomainNodeType.SKILL -> CatalogNodeType.SKILL
    }

    /**
     * Maps a catalog node type back onto its production-domain counterpart.
     *
     * @return the domain enum entry with the same conceptual identity.
     */
    fun toDomain(type: CatalogNodeType): DomainNodeType = when (type) {
        CatalogNodeType.INPUT -> DomainNodeType.INPUT
        CatalogNodeType.OUTPUT -> DomainNodeType.OUTPUT
        CatalogNodeType.LITE_RT -> DomainNodeType.LITE_RT
        CatalogNodeType.CLOUD -> DomainNodeType.CLOUD
        CatalogNodeType.INTENT_ROUTER -> DomainNodeType.INTENT_ROUTER
        CatalogNodeType.IF_CONDITION -> DomainNodeType.IF_CONDITION
        CatalogNodeType.CLARIFICATION -> DomainNodeType.CLARIFICATION
        CatalogNodeType.TOOL -> DomainNodeType.TOOL
        CatalogNodeType.DECOMPOSITION -> DomainNodeType.DECOMPOSITION
        CatalogNodeType.QUEUE_PROCESSOR -> DomainNodeType.QUEUE_PROCESSOR
        CatalogNodeType.EVALUATION -> DomainNodeType.EVALUATION
        CatalogNodeType.SUMMARY -> DomainNodeType.SUMMARY
        CatalogNodeType.PIPELINE -> DomainNodeType.PIPELINE
        CatalogNodeType.SKILL -> DomainNodeType.SKILL
    }
}

/**
 * Bridges between the catalog-side [CatalogCloudProvider] enum (used by `CloudConfig` forms)
 * and the production-domain [DomainCloudProvider] (persisted as `cloudProvider` wire-id).
 *
 * The catalog set (`AUTO / OPEN_AI / ANTHROPIC / GOOGLE / COMPATIBLE`) enumerates the
 * user-presentable provider tiles. The domain adds `DEEPSEEK` and `OLLAMA`, which both
 * map onto catalog's `COMPATIBLE` slot (OpenAI-compatible wire protocol), while catalog's
 * `AUTO` has no concrete domain provider — it round-trips through the
 * [DomainCloudProvider.AUTO_KEY] (`"auto"`) wire sentinel via [toWireId] / [fromWireId].
 */
internal object CloudProviderMapper {

    /**
     * Translates the catalog form's selection into the domain provider.
     *
     * @return the matching [DomainCloudProvider]; `COMPATIBLE` maps to [DomainCloudProvider.DEEPSEEK]
     * as the canonical "OpenAI-compatible" instance because it is the only such provider with a
     * dedicated wire-id today. [CatalogCloudProvider.AUTO] returns `null` — it has no concrete
     * domain provider and is persisted as the [DomainCloudProvider.AUTO_KEY] sentinel instead
     * (see [toWireId]).
     */
    fun toDomain(provider: CatalogCloudProvider): DomainCloudProvider? = when (provider) {
        CatalogCloudProvider.AUTO -> null
        CatalogCloudProvider.OPEN_AI -> DomainCloudProvider.OPENAI
        CatalogCloudProvider.ANTHROPIC -> DomainCloudProvider.ANTHROPIC
        CatalogCloudProvider.GOOGLE -> DomainCloudProvider.GOOGLE
        CatalogCloudProvider.COMPATIBLE -> DomainCloudProvider.DEEPSEEK
    }

    /**
     * Resolves the catalog selection to the `cloudProvider` wire-id stored on
     * the domain [app.knotwork.android.domain.models.NodeModel]. [CatalogCloudProvider.AUTO]
     * yields [DomainCloudProvider.AUTO_KEY] (`"auto"`) so runtime auto-routing is
     * preserved across a config-sheet save; every other tile yields its concrete
     * `DomainCloudProvider.id`.
     */
    fun toWireId(provider: CatalogCloudProvider): String = toDomain(provider)?.id ?: DomainCloudProvider.AUTO_KEY

    /**
     * Round-trip-safe variant of [toWireId] for a node that already has a provider.
     *
     * The catalog→domain direction is lossy where several domain providers share one
     * tile: `COMPATIBLE` covers both [DomainCloudProvider.DEEPSEEK] and
     * [DomainCloudProvider.OLLAMA], so [toWireId] alone rewrote every Ollama node to
     * DeepSeek the moment its config sheet was opened and saved. For a local-first
     * product that is a privacy change, not a cosmetic one — the node silently starts
     * sending the same data to a third-party endpoint instead of a LAN server.
     *
     * The rule: if the node's existing wire-id already resolves to the tile the form is
     * showing, the user did not change the provider, so keep what was there. Only a
     * genuine change of tile — or an unrecognised / absent previous value — falls
     * through to [toWireId]'s canonical pick.
     *
     * @param provider the tile currently selected in the form.
     * @param previousWireId the `cloudProvider` wire-id already stored on the node.
     * @return the wire-id to persist.
     */
    fun toWireIdPreserving(provider: CatalogCloudProvider, previousWireId: String?): String {
        val recognised = previousWireId?.takeIf {
            it.isNotBlank() &&
                (it.equals(DomainCloudProvider.AUTO_KEY, ignoreCase = true) || DomainCloudProvider.fromId(it) != null)
        }
        return recognised?.takeIf { fromWireId(it) == provider } ?: toWireId(provider)
    }

    /**
     * Translates a domain wire-id into the matching catalog selection. Every provider without a
     * tile of its own — DeepSeek, Ollama, OpenRouter, Groq, an OpenAI-compatible server — maps to
     * the catalog's `COMPATIBLE` tile, and [toWireIdPreserving] keeps the id a node already has.
     *
     * @return the catalog enum entry the form should pre-select; defaults to [CatalogCloudProvider.OPEN_AI]
     * when [provider] is `null` (legacy CLOUD nodes saved by older app versions with no explicit provider).
     */
    fun toCatalog(provider: DomainCloudProvider?): CatalogCloudProvider = when (provider) {
        DomainCloudProvider.OPENAI -> CatalogCloudProvider.OPEN_AI
        DomainCloudProvider.ANTHROPIC -> CatalogCloudProvider.ANTHROPIC
        DomainCloudProvider.GOOGLE -> CatalogCloudProvider.GOOGLE
        DomainCloudProvider.DEEPSEEK,
        DomainCloudProvider.OLLAMA,
        DomainCloudProvider.OPENROUTER,
        DomainCloudProvider.GROQ,
        DomainCloudProvider.OPENAI_COMPATIBLE,
        -> CatalogCloudProvider.COMPATIBLE
        null -> CatalogCloudProvider.OPEN_AI
    }

    /**
     * Resolves a persisted `cloudProvider` wire-id back to the catalog selection,
     * preserving the [DomainCloudProvider.AUTO_KEY] sentinel as
     * [CatalogCloudProvider.AUTO]. Unlike [DomainCloudProvider.fromId] (which folds
     * `"auto"`, blank and unknown ids all to `null`), this keeps `"auto"` distinct
     * from "no provider" so a browser-edited / auto CLOUD node round-trips as Auto
     * instead of silently becoming OpenAI.
     *
     * @return [CatalogCloudProvider.AUTO] for the auto sentinel, otherwise the tile
     *   for the concrete provider (defaulting to `OPEN_AI` for `null` / unknown).
     */
    fun fromWireId(wireId: String?): CatalogCloudProvider =
        if (wireId != null && wireId.equals(DomainCloudProvider.AUTO_KEY, ignoreCase = true)) {
            CatalogCloudProvider.AUTO
        } else {
            toCatalog(DomainCloudProvider.fromId(wireId))
        }
}
