@file:Suppress("TooManyFunctions") // 13 node types -> 13 encode/decode pairs by design.

package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.design.components.pipelineeditor.ClarificationConfig
import app.knotwork.design.components.pipelineeditor.CloudConfig
import app.knotwork.design.components.pipelineeditor.DecompositionConfig
import app.knotwork.design.components.pipelineeditor.EvaluationConfig
import app.knotwork.design.components.pipelineeditor.IfConditionConfig
import app.knotwork.design.components.pipelineeditor.InputConfig
import app.knotwork.design.components.pipelineeditor.IntentClass
import app.knotwork.design.components.pipelineeditor.IntentRouterConfig
import app.knotwork.design.components.pipelineeditor.LiteRtConfig
import app.knotwork.design.components.pipelineeditor.NodeConfig
import app.knotwork.design.components.pipelineeditor.OutputConfig
import app.knotwork.design.components.pipelineeditor.PipelineConfig
import app.knotwork.design.components.pipelineeditor.QueueProcessorConfig
import app.knotwork.design.components.pipelineeditor.SkillConfig
import app.knotwork.design.components.pipelineeditor.SkillEngine
import app.knotwork.design.components.pipelineeditor.SummaryConfig
import app.knotwork.design.components.pipelineeditor.ToolConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import app.knotwork.android.domain.models.NodeType as DomainNodeType
import app.knotwork.design.components.pipelineeditor.CloudProvider as CatalogCloudProvider
import app.knotwork.design.components.pipelineeditor.NodeType as CatalogNodeType

/**
 * Bridges between the catalog's typed [NodeConfig] sealed family and the production-domain
 * [NodeModel] persistence layer.
 *
 * Two responsibilities:
 *  - **Encode / decode** the configuration as a JSON blob written to `NodeModel.configJson`.
 *    The blob carries the typed
 *    payload introduced by the new `NodeConfigSheet`.
 *  - **Derive defaults** from the legacy flat fields (`systemPrompt`, `cloudProvider`,
 *    `toolName`, `conditionComplexity`, …) when a row was saved by an older app version. This way
 *    pre-existing pipelines open in the new editor with sensible field values without
 *    forcing a one-shot data migration.
 *
 * Pure Kotlin — Android-free — so the codec is unit-testable on the JVM. JSON I/O uses
 * `org.json.JSONObject` per the project's API-conventions doc.
 */
internal object NodeConfigCodec {

    // Schema marker — bump when the JSON shape changes incompatibly.
    private const val SCHEMA_VERSION_KEY = "v"
    private const val SCHEMA_VERSION = 1

    // Common envelope keys.
    private const val TYPE_KEY = "type"
    private const val TITLE_KEY = "title"
    private const val DESCRIPTION_KEY = "description"

    /**
     * Decodes the [NodeConfig] backing [node]. Falls back to legacy flat fields when
     * [NodeModel.configJson] is `null` or malformed.
     *
     * @return the typed configuration; the catalog form receives this as its starting value.
     */
    fun decode(node: NodeModel): NodeConfig {
        val payload = node.configJson?.takeIf { it.isNotBlank() }
        if (payload != null) {
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            if (parsed != null) {
                return decodeFromJson(parsed, node)
            } else {
                Timber.w("NodeConfig payload for node=%s is not valid JSON; falling back to legacy", node.id)
            }
        }
        return deriveFromLegacy(node)
    }

    /**
     * Encodes [config] to a JSON string suitable for [NodeModel.configJson] persistence.
     *
     * @return a stable JSON document keyed by [SCHEMA_VERSION_KEY], [TYPE_KEY], and per-type fields.
     */
    fun encode(config: NodeConfig): String {
        val json = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(TYPE_KEY, NodeTypeMapper.toDomain(config.type).name)
            .put(TITLE_KEY, config.title)
        config.description?.let { json.put(DESCRIPTION_KEY, it) }
        when (config) {
            is InputConfig -> Unit // No payload beyond the shared title / description.
            is OutputConfig -> encodeOutput(json, config)
            is LiteRtConfig -> encodeLiteRt(json, config)
            is CloudConfig -> encodeCloud(json, config)
            is IntentRouterConfig -> encodeIntentRouter(json, config)
            is IfConditionConfig -> encodeIfCondition(json, config)
            is ClarificationConfig -> encodeClarification(json, config)
            is ToolConfig -> encodeTool(json, config)
            is DecompositionConfig -> encodeDecomposition(json, config)
            is QueueProcessorConfig -> encodeQueueProcessor(json, config)
            is EvaluationConfig -> encodeEvaluation(json, config)
            is SummaryConfig -> encodeSummary(json, config)
            is PipelineConfig -> encodePipeline(json, config)
            is SkillConfig -> encodeSkill(json, config)
        }
        return json.toString()
    }

    /**
     * Projects an edited [NodeConfig] back onto its source [NodeModel], preserving graph
     * identity (id, position, context flags) and updating both the JSON payload and the
     * legacy flat fields the runtime engine still reads.
     *
     * @return a copy of [source] with the new payload encoded into `configJson` and the
     * matching flat columns (label / systemPrompt / cloudProvider / toolName / clarification
     * timeout / condition fields) refreshed from [config].
     */
    fun apply(source: NodeModel, config: NodeConfig): NodeModel {
        val withJson = source.copy(
            label = config.title,
            configJson = encode(config),
        )
        return when (config) {
            is LiteRtConfig -> withJson.copy(
                systemPrompt = config.systemPrompt,
                // Blank `modelId` is the explicit "Active model" sentinel
                // — persist as `null` on
                // the domain row so `LoadModelUseCase(null)` falls back to
                // the current `LocalModelRepository.getActiveModel()` at
                // execute time. Earlier this branch preserved the previous
                // `withJson.modelPath`, which froze the node to whichever
                // model happened to be active when the user first opened
                // the form.
                modelPath = config.modelId.takeIf { it.isNotBlank() },
            )
            is CloudConfig -> withJson.copy(
                systemPrompt = config.systemPrompt,
                // `toWireIdPreserving` keeps `CloudProvider.AUTO` as the "auto"
                // sentinel (rather than collapsing to a concrete provider), so
                // saving the sheet does not rewrite an auto-routing node to
                // OpenAI — and, for the same reason, does not rewrite an Ollama
                // node to DeepSeek just because both share the `COMPATIBLE` tile.
                cloudProvider = CloudProviderMapper.toWireIdPreserving(config.provider, source.cloudProvider),
            )
            is ToolConfig -> withJson.copy(
                toolName = config.toolId.takeIf { it.isNotBlank() },
                // `false` stored as `null`: the flat field's absence and its
                // `false` mean the same thing to the gate, and `null` is what an
                // exported file should carry for "not set".
                alwaysConfirm = config.alwaysConfirm.takeIf { it },
                cloudProvider = engineWire(config.engineProvider, source.cloudProvider),
            )
            is IfConditionConfig -> withJson.copy(
                conditionPrompt = config.expression,
                // Blank keywords become `null`, not `""`: `EvaluateIfConditionUseCase`
                // treats an empty split as "no keyword check", and writing an empty
                // string would leave a field that reads as configured-but-inert.
                conditionKeywords = config.keywords.takeIf { it.isNotBlank() },
                conditionComplexity = config.complexityThreshold,
                conditionHasImage = config.branchOnImage,
                cloudProvider = engineWire(config.engineProvider, source.cloudProvider),
            )
            is ClarificationConfig -> withJson.copy(
                clarificationTimeoutMs = config.timeoutMs?.toLong(),
                // Joined rather than stored as JSON, matching `conditionKeywords`:
                // the column is a list the user typed, and blank means "let the
                // model write the options" rather than "no options".
                quickReplies = config.quickReplies
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", "),
                systemPrompt = config.questionTemplate,
            )
            // OUTPUT mirrors `systemPrompt` onto the domain row so the
            // `OutputNodeExecutor` can read it via `node.systemPrompt`.
            // Empty string is allowed
            // and the executor reads it as "echo upstream verbatim".
            is OutputConfig -> withJson.copy(systemPrompt = config.systemPrompt)
            // Persist the chosen sub-pipeline id onto the domain row so
            // `PipelineNodeExecutor` resolves the target at run time; blank
            // means "no target chosen" and round-trips as `null`.
            is PipelineConfig -> withJson.copy(
                targetPipelineId = config.targetPipelineId.takeIf { it.isNotBlank() },
            )
            // Persist the chosen skill id onto the domain row so
            // `SkillNodeExecutor` resolves it at run time; the engine choice is
            // carried via `cloudProvider` (the "auto" sentinel for CLOUD, `null`
            // for the on-device LiteRT engine) so no new flat column is needed.
            is SkillConfig -> withJson.copy(
                skillId = config.skillId.takeIf { it.isNotBlank() },
                cloudProvider = if (config.engine == SkillEngine.CLOUD) CloudProvider.AUTO_KEY else null,
                // Same encoding as TOOL: `false` stored as `null`.
                alwaysConfirm = config.alwaysConfirm.takeIf { it },
            )
            // These four types name their prompt field differently on the
            // sheet, but the executors read exactly one thing —
            // `node.systemPrompt` (`SystemNodeExecutor`, `SummaryNodeExecutor`).
            // Mirroring the rich field onto the flat property is what makes an
            // edit take effect. Without it the field is *required* by
            // `NodeConfigValidation`, survives a reopen (because `decode` reads
            // it back out of `configJson`) and still never reaches the run — an
            // edit that looks saved and is not.
            //
            // Blank maps to `null`, not to `""`: both executors fall back with
            // `node.systemPrompt ?: FALLBACK`, so an empty string would run the
            // node with no instructions at all rather than its default prompt.
            // The browser editor writes `''` here, but its value reaches a node
            // through `PipelineJsonSerializer`, whose `optStringOrNull` maps an
            // empty string to `null` — so the two editors agree in effect.
            is IntentRouterConfig -> withJson.copy(
                fallbackClass = config.fallbackClass?.takeIf { it.isNotBlank() },
                systemPrompt = config.classifierPrompt.takeIf { it.isNotBlank() },
                cloudProvider = engineWire(config.engineProvider, source.cloudProvider),
            )
            is DecompositionConfig -> withJson.copy(
                maxSubtasks = config.maxSubtasks,
                systemPrompt = config.planningPrompt.takeIf { it.isNotBlank() },
                cloudProvider = engineWire(config.engineProvider, source.cloudProvider),
            )
            is EvaluationConfig -> withJson.copy(
                systemPrompt = config.criteriaPrompt.takeIf { it.isNotBlank() },
                cloudProvider = engineWire(config.engineProvider, source.cloudProvider),
            )
            // SUMMARY's prompt is required only when the format is CUSTOM, so a
            // blank value is a legitimate state here rather than an unreachable
            // one — which is precisely why it must not become `""`.
            is SummaryConfig -> withJson.copy(
                systemPrompt = config.customPrompt?.takeIf { it.isNotBlank() },
            )
            is QueueProcessorConfig -> withJson.copy(stopOnError = config.stopOnError)
            is InputConfig -> withJson
        }
    }

    /**
     * Maps a structured node's catalog engine selection to the flat
     * `cloudProvider` wire-id: `null` (on-device) stays `null`; a concrete cloud
     * provider yields its wire-id. `Auto` never reaches here — the structured
     * engine picker offers only on-device + concrete providers.
     *
     * [previousWireId] is the value already on the node, forwarded so an unchanged
     * `COMPATIBLE` selection keeps the provider it actually had (Ollama stays
     * Ollama) instead of collapsing to the tile's canonical DeepSeek — the same
     * round-trip hazard the CLOUD node has.
     */
    private fun engineWire(provider: CatalogCloudProvider?, previousWireId: String?): String? =
        provider?.let { CloudProviderMapper.toWireIdPreserving(it, previousWireId) }

    /**
     * Inverse of [engineWire]: maps a node's flat `cloudProvider` back to the
     * structured engine selection. A blank/`null` provider — or the `"auto"`
     * sentinel, which structured nodes do not support — resolves to on-device
     * (`null`); any concrete provider resolves to its catalog tile.
     */
    private fun engineProviderFromWire(cloudProvider: String?): CatalogCloudProvider? = cloudProvider
        ?.takeIf { it.isNotBlank() && !it.equals(CloudProvider.AUTO_KEY, ignoreCase = true) }
        ?.let { CloudProviderMapper.fromWireId(it) }

    /**
     * Decodes the structured engine selection from a node's rich payload,
     * preferring the persisted `engineProvider` name and falling back to the
     * flat `cloudProvider` wire-id for rows written before the field existed.
     */
    private fun decodeEngineProvider(p: JSONObject, fb: NodeModel): CatalogCloudProvider? =
        enumOrNull<CatalogCloudProvider>(p.optStringOrNull("engineProvider"))
            ?: engineProviderFromWire(fb.cloudProvider)

    /**
     * Builds a fresh default [NodeConfig] for [type] — used by the editor when the user picks
     * a node from the radial quick-add menu and the [NodeConfigSheet] opens for the first time.
     *
     * @param type the catalog node type the form will render for.
     * @param title initial title (typically the node's label or the type's display label).
     */
    fun defaultFor(type: CatalogNodeType, title: String): NodeConfig = when (type) {
        CatalogNodeType.INPUT -> InputConfig(title = title)
        CatalogNodeType.OUTPUT -> OutputConfig(title = title)
        CatalogNodeType.LITE_RT -> LiteRtConfig(
            title = title,
            systemPrompt = DefaultPrompts.getDefaultPromptForNodeType(DomainNodeType.LITE_RT).orEmpty(),
        )
        CatalogNodeType.CLOUD -> CloudConfig(
            title = title,
            systemPrompt = DefaultPrompts.getDefaultPromptForNodeType(DomainNodeType.CLOUD).orEmpty(),
        )
        CatalogNodeType.INTENT_ROUTER -> IntentRouterConfig(
            title = title,
            classifierPrompt = DefaultPrompts
                .getDefaultPromptForNodeType(DomainNodeType.INTENT_ROUTER)
                .orEmpty(),
            classes = listOf(IntentClass(name = "simple"), IntentClass(name = "complex")),
        )
        CatalogNodeType.IF_CONDITION -> IfConditionConfig(title = title)
        CatalogNodeType.CLARIFICATION -> ClarificationConfig(
            title = title,
            questionTemplate = DefaultPrompts
                .getDefaultPromptForNodeType(DomainNodeType.CLARIFICATION)
                .orEmpty(),
        )
        CatalogNodeType.TOOL -> ToolConfig(title = title)
        CatalogNodeType.DECOMPOSITION -> DecompositionConfig(
            title = title,
            planningPrompt = DefaultPrompts
                .getDefaultPromptForNodeType(DomainNodeType.DECOMPOSITION)
                .orEmpty(),
        )
        CatalogNodeType.QUEUE_PROCESSOR -> QueueProcessorConfig(title = title)
        CatalogNodeType.EVALUATION -> EvaluationConfig(
            title = title,
            criteriaPrompt = DefaultPrompts
                .getDefaultPromptForNodeType(DomainNodeType.EVALUATION)
                .orEmpty(),
        )
        CatalogNodeType.SUMMARY -> SummaryConfig(
            title = title,
            customPrompt = DefaultPrompts
                .getDefaultPromptForNodeType(DomainNodeType.SUMMARY),
        )
        // A fresh PIPELINE node has no target yet — the picker forces the
        // choice and the validator blocks Save until one is made.
        CatalogNodeType.PIPELINE -> PipelineConfig(title = title)
        // A fresh SKILL node has no skill yet — the picker forces the choice
        // and the validator blocks Save until one is made.
        CatalogNodeType.SKILL -> SkillConfig(title = title)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Decode dispatch
    // ─────────────────────────────────────────────────────────────────────

    private fun decodeFromJson(payload: JSONObject, fallback: NodeModel): NodeConfig {
        val title = payload.optString(TITLE_KEY).ifBlank { fallback.label }
        val description = payload.optStringOrNull(DESCRIPTION_KEY)
        return when (NodeTypeMapper.toCatalog(fallback.type)) {
            CatalogNodeType.INPUT -> decodeInput(title, description)
            CatalogNodeType.OUTPUT -> decodeOutput(payload, title, description)
            CatalogNodeType.LITE_RT -> decodeLiteRt(payload, title, description, fallback)
            CatalogNodeType.CLOUD -> decodeCloud(payload, title, description, fallback)
            CatalogNodeType.INTENT_ROUTER -> decodeIntentRouter(payload, title, description, fallback)
            CatalogNodeType.IF_CONDITION -> decodeIfCondition(payload, title, description, fallback)
            CatalogNodeType.CLARIFICATION -> decodeClarification(payload, title, description, fallback)
            CatalogNodeType.TOOL -> decodeTool(payload, title, description, fallback)
            CatalogNodeType.DECOMPOSITION -> decodeDecomposition(payload, title, description, fallback)
            CatalogNodeType.QUEUE_PROCESSOR -> decodeQueueProcessor(payload, title, description, fallback)
            CatalogNodeType.EVALUATION -> decodeEvaluation(payload, title, description, fallback)
            CatalogNodeType.SUMMARY -> decodeSummary(payload, title, description, fallback)
            CatalogNodeType.PIPELINE -> decodePipeline(payload, title, description, fallback)
            CatalogNodeType.SKILL -> decodeSkill(payload, title, description, fallback)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Legacy-field derivation (older saved rows)
    // ─────────────────────────────────────────────────────────────────────

    private fun deriveFromLegacy(node: NodeModel): NodeConfig {
        val title = node.label.ifBlank { node.type.name }
        // When a legacy node persists with
        // an empty `systemPrompt` (older pipelines created before
        // `DefaultPrompts.getDefaultPromptForNodeType` was wired into NodeModel
        // construction), fall back to the registered default prompt instead of
        // showing an empty field. Users were rightly confused that "standard
        // prompts disappeared" for these node types.
        val systemPromptOrDefault = node.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?: DefaultPrompts.getDefaultPromptForNodeType(node.type).orEmpty()
        return when (NodeTypeMapper.toCatalog(node.type)) {
            CatalogNodeType.INPUT -> InputConfig(title = title)
            // Legacy rows that pre-date F10 keep their persisted
            // `node.systemPrompt` here so the editor surfaces what they were
            // already sending to the LLM, instead of silently clearing it.
            CatalogNodeType.OUTPUT -> OutputConfig(
                title = title,
                systemPrompt = node.systemPrompt.orEmpty(),
            )
            CatalogNodeType.LITE_RT -> LiteRtConfig(
                title = title,
                systemPrompt = systemPromptOrDefault,
                modelId = node.modelPath.orEmpty(),
            )
            CatalogNodeType.CLOUD -> CloudConfig(
                title = title,
                systemPrompt = systemPromptOrDefault,
                provider = CloudProviderMapper.fromWireId(node.cloudProvider),
            )
            CatalogNodeType.INTENT_ROUTER -> IntentRouterConfig(
                title = title,
                classifierPrompt = systemPromptOrDefault,
                fallbackClass = node.fallbackClass,
                engineProvider = engineProviderFromWire(node.cloudProvider),
            )
            CatalogNodeType.IF_CONDITION -> IfConditionConfig(
                title = title,
                expression = node.conditionPrompt.orEmpty(),
                // Carried through here as well as in `decodeIfCondition`: a row
                // saved before the sheet had these controls has no `configJson`
                // at all, and that is exactly the pipeline whose branch was
                // being decided by a keyword the editor never showed.
                keywords = node.conditionKeywords.orEmpty(),
                complexityThreshold = node.conditionComplexity?.takeIf { it > 0 },
                branchOnImage = node.conditionHasImage == true,
                engineProvider = engineProviderFromWire(node.cloudProvider),
            )
            CatalogNodeType.CLARIFICATION -> ClarificationConfig(
                title = title,
                questionTemplate = systemPromptOrDefault,
                quickReplies = node.quickReplies?.split(",").orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                timeoutMs = node.clarificationTimeoutMs?.toInt(),
            )
            CatalogNodeType.TOOL -> ToolConfig(
                title = title,
                toolId = node.toolName.orEmpty(),
                alwaysConfirm = node.alwaysConfirm == true,
                engineProvider = engineProviderFromWire(node.cloudProvider),
            )
            CatalogNodeType.DECOMPOSITION -> DecompositionConfig(
                title = title,
                planningPrompt = systemPromptOrDefault,
                maxSubtasks = node.maxSubtasks ?: DEFAULT_MAX_SUBTASKS,
                engineProvider = engineProviderFromWire(node.cloudProvider),
            )
            CatalogNodeType.QUEUE_PROCESSOR -> QueueProcessorConfig(
                title = title,
                stopOnError = node.stopOnError ?: true,
            )
            CatalogNodeType.EVALUATION -> EvaluationConfig(
                title = title,
                criteriaPrompt = systemPromptOrDefault,
                engineProvider = engineProviderFromWire(node.cloudProvider),
            )
            CatalogNodeType.SUMMARY -> SummaryConfig(
                title = title,
                customPrompt = systemPromptOrDefault.takeIf { it.isNotBlank() },
            )
            CatalogNodeType.PIPELINE -> PipelineConfig(
                title = title,
                targetPipelineId = node.targetPipelineId.orEmpty(),
            )
            CatalogNodeType.SKILL -> SkillConfig(
                title = title,
                skillId = node.skillId.orEmpty(),
                engine = engineFromProvider(node.cloudProvider),
                alwaysConfirm = node.alwaysConfirm == true,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-type encoders
    // ─────────────────────────────────────────────────────────────────────

    private fun encodeOutput(json: JSONObject, c: OutputConfig) {
        json.put("systemPrompt", c.systemPrompt)
    }

    private fun encodeLiteRt(json: JSONObject, c: LiteRtConfig) {
        json.put("modelId", c.modelId)
        json.put("systemPrompt", c.systemPrompt)
        json.put("temperature", c.temperature.toDouble())
        json.put("topP", c.topP.toDouble())
        json.put("maxNewTokens", c.maxNewTokens)
        json.put("stopTokens", JSONArray(c.stopTokens))
    }

    private fun encodeCloud(json: JSONObject, c: CloudConfig) {
        json.put("provider", c.provider.name)
        json.put("model", c.model)
        json.put("systemPrompt", c.systemPrompt)
        json.put("temperature", c.temperature.toDouble())
        json.put("maxTokens", c.maxTokens)
        json.put("timeoutMs", c.timeoutMs)
    }

    private fun encodeIntentRouter(json: JSONObject, c: IntentRouterConfig) {
        val classes = JSONArray()
        c.classes.forEach { cls ->
            classes.put(
                JSONObject()
                    .put("name", cls.name)
                    .put("description", cls.description)
                    .put("examples", JSONArray(cls.examples)),
            )
        }
        json.put("classes", classes)
        json.put("classifierPrompt", c.classifierPrompt)
        c.fallbackClass?.let { json.put("fallbackClass", it) }
        c.engineProvider?.let { json.put("engineProvider", it.name) }
    }

    private fun encodeIfCondition(json: JSONObject, c: IfConditionConfig) {
        json.put("expression", c.expression)
        json.put("keywords", c.keywords)
        c.complexityThreshold?.let { json.put("complexityThreshold", it) }
        json.put("branchOnImage", c.branchOnImage)
        c.engineProvider?.let { json.put("engineProvider", it.name) }
    }

    private fun encodeClarification(json: JSONObject, c: ClarificationConfig) {
        json.put("questionTemplate", c.questionTemplate)
        json.put("quickReplies", JSONArray(c.quickReplies))
        c.timeoutMs?.let { json.put("timeoutMs", it) }
    }

    private fun encodeTool(json: JSONObject, c: ToolConfig) {
        json.put("toolId", c.toolId)
        json.put("alwaysConfirm", c.alwaysConfirm)
        c.engineProvider?.let { json.put("engineProvider", it.name) }
    }

    private fun encodeDecomposition(json: JSONObject, c: DecompositionConfig) {
        json.put("planningPrompt", c.planningPrompt)
        json.put("maxSubtasks", c.maxSubtasks)
        c.engineProvider?.let { json.put("engineProvider", it.name) }
    }

    private fun encodeQueueProcessor(json: JSONObject, c: QueueProcessorConfig) {
        json.put("stopOnError", c.stopOnError)
    }

    private fun encodeEvaluation(json: JSONObject, c: EvaluationConfig) {
        json.put("criteriaPrompt", c.criteriaPrompt)
        json.put("maxRetries", c.maxRetries)
        c.engineProvider?.let { json.put("engineProvider", it.name) }
    }

    private fun encodeSummary(json: JSONObject, c: SummaryConfig) {
        c.customPrompt?.let { json.put("customPrompt", it) }
    }

    // Only the target id is persisted; the display name is resolved live by
    // the editor from the saved-pipeline catalogue, never stored on the node.
    private fun encodePipeline(json: JSONObject, c: PipelineConfig) {
        json.put("targetPipelineId", c.targetPipelineId)
    }

    // Only the durable choices are persisted: the skill id, the engine and the
    // always-ask switch. `skillName` / `instructionPreview` /
    // `toolRestrictionSummary` are resolved from the live skill library when the
    // sheet opens, so persisting them would only risk going stale when the skill
    // is edited.
    private fun encodeSkill(json: JSONObject, c: SkillConfig) {
        json.put("skillId", c.skillId)
        json.put("engine", c.engine.name)
        json.put("alwaysConfirm", c.alwaysConfirm)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-type decoders
    // ─────────────────────────────────────────────────────────────────────

    // No `payload` parameter, unlike every sibling: INPUT carries nothing beyond
    // the shared title and description, and a parameter the body cannot use
    // would only invite someone to look for a field that is not there.
    private fun decodeInput(title: String, description: String?): InputConfig = InputConfig(
        title = title,
        description = description,
    )

    private fun decodeOutput(p: JSONObject, title: String, description: String?): OutputConfig = OutputConfig(
        title = title,
        description = description,
        // Optional — older persisted rows simply lack this key and fall back to the default
        // empty string (echo-through mode).
        systemPrompt = p.optString("systemPrompt"),
    )

    private fun decodeLiteRt(p: JSONObject, title: String, description: String?, fb: NodeModel): LiteRtConfig =
        LiteRtConfig(
            title = title,
            description = description,
            modelId = p.optString("modelId").ifBlank { fb.modelPath.orEmpty() },
            systemPrompt = p.optString("systemPrompt").ifBlank { fb.systemPrompt.orEmpty() },
            temperature = p.optDouble("temperature", DEFAULT_TEMPERATURE).toFloat(),
            topP = p.optDouble("topP", DEFAULT_TOP_P).toFloat(),
            maxNewTokens = p.optInt("maxNewTokens", DEFAULT_MAX_NEW_TOKENS),
            stopTokens = p.optStringList("stopTokens"),
        )

    private fun decodeCloud(p: JSONObject, title: String, description: String?, fb: NodeModel): CloudConfig =
        CloudConfig(
            title = title,
            description = description,
            provider = enumOrDefault(
                p.optStringOrNull("provider"),
                CloudProviderMapper.fromWireId(fb.cloudProvider),
            ),
            model = p.optString("model"),
            systemPrompt = p.optString("systemPrompt").ifBlank { fb.systemPrompt.orEmpty() },
            temperature = p.optDouble("temperature", DEFAULT_TEMPERATURE).toFloat(),
            maxTokens = p.optInt("maxTokens", DEFAULT_MAX_TOKENS),
            timeoutMs = p.optInt("timeoutMs", DEFAULT_TIMEOUT_MS),
        )

    private fun decodeIntentRouter(
        p: JSONObject,
        title: String,
        description: String?,
        fb: NodeModel,
    ): IntentRouterConfig = IntentRouterConfig(
        title = title,
        description = description,
        classes = p.optJSONArray("classes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                IntentClass(
                    name = obj.optString("name"),
                    description = obj.optString("description"),
                    examples = obj.optJSONArray("examples")?.toStringList().orEmpty(),
                )
            }
        }.orEmpty(),
        classifierPrompt = p.optString("classifierPrompt").ifBlank { fb.systemPrompt.orEmpty() },
        fallbackClass = p.optStringOrNull("fallbackClass") ?: fb.fallbackClass,
        engineProvider = decodeEngineProvider(p, fb),
    )

    private fun decodeIfCondition(
        p: JSONObject,
        title: String,
        description: String?,
        fb: NodeModel,
    ): IfConditionConfig = IfConditionConfig(
        title = title,
        description = description,
        expression = p.optString("expression").ifBlank { fb.conditionPrompt.orEmpty() },
        // Both deterministic checks read the flat NodeModel field first: they
        // were runtime inputs for far longer than they were editor fields, so an
        // imported pipeline's value is the authority over an absent JSON key.
        keywords = p.optString("keywords").ifBlank { fb.conditionKeywords.orEmpty() },
        complexityThreshold = (
            if (p.has("complexityThreshold")) p.optInt("complexityThreshold") else fb.conditionComplexity
            )?.takeIf { it > 0 },
        branchOnImage = p.optBoolean("branchOnImage", fb.conditionHasImage == true),
        engineProvider = decodeEngineProvider(p, fb),
    )

    private fun decodeClarification(
        p: JSONObject,
        title: String,
        description: String?,
        fb: NodeModel,
    ): ClarificationConfig = ClarificationConfig(
        title = title,
        description = description,
        questionTemplate = p.optString("questionTemplate").ifBlank { fb.systemPrompt.orEmpty() },
        quickReplies = p.optStringList("quickReplies")
            .ifEmpty { fb.quickReplies?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() } },
        timeoutMs = if (p.has("timeoutMs")) p.optInt("timeoutMs") else fb.clarificationTimeoutMs?.toInt(),
    )

    private fun decodeTool(p: JSONObject, title: String, description: String?, fb: NodeModel): ToolConfig = ToolConfig(
        title = title,
        description = description,
        toolId = p.optString("toolId").ifBlank { fb.toolName.orEmpty() },
        alwaysConfirm = p.optBoolean("alwaysConfirm", fb.alwaysConfirm == true),
        engineProvider = decodeEngineProvider(p, fb),
    )

    private fun decodeDecomposition(
        p: JSONObject,
        title: String,
        description: String?,
        fb: NodeModel,
    ): DecompositionConfig = DecompositionConfig(
        title = title,
        description = description,
        planningPrompt = p.optString("planningPrompt").ifBlank { fb.systemPrompt.orEmpty() },
        maxSubtasks = p.optInt("maxSubtasks", fb.maxSubtasks ?: DEFAULT_MAX_SUBTASKS),
        engineProvider = decodeEngineProvider(p, fb),
    )

    private fun decodeQueueProcessor(
        p: JSONObject,
        title: String,
        description: String?,
        fb: NodeModel,
    ): QueueProcessorConfig = QueueProcessorConfig(
        title = title,
        description = description,
        stopOnError = p.optBoolean("stopOnError", fb.stopOnError ?: true),
    )

    private fun decodeEvaluation(p: JSONObject, title: String, description: String?, fb: NodeModel): EvaluationConfig =
        EvaluationConfig(
            title = title,
            description = description,
            criteriaPrompt = p.optString("criteriaPrompt").ifBlank { fb.systemPrompt.orEmpty() },
            maxRetries = p.optInt("maxRetries", DEFAULT_MAX_RETRIES),
            engineProvider = decodeEngineProvider(p, fb),
        )

    private fun decodeSummary(p: JSONObject, title: String, description: String?, fb: NodeModel): SummaryConfig =
        SummaryConfig(
            title = title,
            description = description,
            customPrompt = p.optStringOrNull("customPrompt") ?: fb.systemPrompt,
        )

    private fun decodePipeline(p: JSONObject, title: String, description: String?, fb: NodeModel): PipelineConfig =
        PipelineConfig(
            title = title,
            description = description,
            targetPipelineId = p.optString("targetPipelineId").ifBlank { fb.targetPipelineId.orEmpty() },
        )

    private fun decodeSkill(p: JSONObject, title: String, description: String?, fb: NodeModel): SkillConfig =
        SkillConfig(
            title = title,
            description = description,
            skillId = p.optString("skillId").ifBlank { fb.skillId.orEmpty() },
            // Prefer the persisted engine; fall back to deriving it from the
            // node's `cloudProvider` for rows written before the field existed.
            engine = enumOrNull<SkillEngine>(p.optStringOrNull("engine")) ?: engineFromProvider(fb.cloudProvider),
            // Envelopes written before the switch existed fall back to the flat field.
            alwaysConfirm = p.optBoolean("alwaysConfirm", fb.alwaysConfirm == true),
        )

    /**
     * Maps a node's `cloudProvider` to the SKILL engine choice: any non-blank
     * provider (including the "auto" sentinel) means the cloud engine; `null`
     * means the on-device LiteRT engine.
     */
    private fun engineFromProvider(cloudProvider: String?): SkillEngine =
        if (cloudProvider.isNullOrBlank()) SkillEngine.LITE_RT else SkillEngine.CLOUD

    // ─────────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) {
        optString(key).takeIf { it.isNotEmpty() }
    } else {
        null
    }

    private fun JSONObject.optStringList(key: String): List<String> = try {
        optJSONArray(key)?.toStringList().orEmpty()
    } catch (e: JSONException) {
        Timber.w(e, "Failed to parse %s as list of strings", key)
        emptyList()
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T = if (raw == null) {
        default
    } else {
        runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? = raw?.let {
        runCatching { enumValueOf<T>(it) }.getOrNull()
    }

    // Numeric defaults for node configuration.
    private const val DEFAULT_TEMPERATURE = 0.7
    private const val DEFAULT_TOP_P = 0.9
    private const val DEFAULT_MAX_NEW_TOKENS = 512
    private const val DEFAULT_MAX_TOKENS = 1_024
    private const val DEFAULT_TIMEOUT_MS = 30_000
    private const val DEFAULT_MAX_SUBTASKS = 5
    private const val DEFAULT_MAX_RETRIES = 2
}
