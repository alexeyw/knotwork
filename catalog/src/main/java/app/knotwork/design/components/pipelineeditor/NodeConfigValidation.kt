package app.knotwork.design.components.pipelineeditor

import org.json.JSONException
import org.json.JSONObject

/**
 * Stable identifier for one validatable field inside a [NodeConfig].
 *
 * The validator returns `Map<FieldId, ValidationFailure>` so forms can
 * look up the inline error for the field they are rendering without
 * re-checking the same rule. Using an enum (rather than string keys)
 * keeps every callsite refactor-safe.
 */
enum class FieldId {
    TITLE,
    DESCRIPTION,
    MODEL_ID,
    SYSTEM_PROMPT,
    TEMPERATURE,
    TOP_P,
    MAX_NEW_TOKENS,
    STOP_TOKENS,
    PROVIDER,
    MODEL,
    MAX_TOKENS,
    TIMEOUT_MS,
    CLASSES,
    CLASSIFIER_PROMPT,
    FALLBACK_CLASS,
    EXPRESSION,
    KEYWORDS,
    COMPLEXITY_THRESHOLD,
    QUESTION_TEMPLATE,
    QUICK_REPLIES,
    TIMEOUT_OPTIONAL,
    TOOL_ID,
    CONFIRM_OVERRIDE,
    PLANNING_PROMPT,
    MAX_SUBTASKS,
    STOP_ON_ERROR,
    CRITERIA_PROMPT,
    MAX_RETRIES,
    CUSTOM_PROMPT,
    TARGET_PIPELINE_ID,
    SKILL_ID,
}

/**
 * One inline validation error attached to a field. The string ids map to
 * `R.string.knotwork_node_validation_*` so forms can render the message
 * via `stringResource` without holding a `Context` here.
 */
enum class ValidationFailure(val stringRes: Int) {
    /** Title field is blank / whitespace-only. */
    TITLE_EMPTY(app.knotwork.design.R.string.knotwork_node_validation_title_empty),

    /** Title clashes with another node in the same pipeline. */
    TITLE_DUPLICATE(app.knotwork.design.R.string.knotwork_node_validation_title_duplicate),

    /** Field is required but currently empty. */
    REQUIRED(app.knotwork.design.R.string.knotwork_node_validation_field_required),

    /** Numeric value outside the spec's range. */
    OUT_OF_RANGE(app.knotwork.design.R.string.knotwork_node_validation_out_of_range),

    /** IntentRouter has fewer than 2 or more than 6 classes. */
    INTENT_CLASS_COUNT(app.knotwork.design.R.string.knotwork_node_validation_intent_class_count),

    /** JSON-Schema body failed to parse. */
    INVALID_JSON(app.knotwork.design.R.string.knotwork_node_validation_invalid_json),

    /**
     * IntentRouter `fallbackClass` references a name that is no longer in
     * the declared `classes` list — typically after the user renamed /
     * removed a class but forgot to update the fallback selection.
     * Surfaced inline under the fallback dropdown so the user can either
     * pick another class or clear the selection.
     */
    FALLBACK_NOT_IN_CLASSES(app.knotwork.design.R.string.knotwork_node_validation_fallback_unknown),

    /**
     * Two IntentRouter classes share the same name. Duplicate names would
     * collapse into a single canvas out-port and break unmatched-intent
     * routing, so the rule fires on `FieldId.CLASSES`.
     */
    CLASS_NAME_DUPLICATE(app.knotwork.design.R.string.knotwork_node_validation_class_duplicate),

    /**
     * A [PipelineConfig] has no target pipeline selected (its
     * `targetPipelineId` is blank). Fires on `FieldId.TARGET_PIPELINE_ID`
     * so the picker disables Save until the user chooses a sub-pipeline.
     */
    TARGET_PIPELINE_MISSING(app.knotwork.design.R.string.knotwork_node_validation_target_pipeline_missing),

    /**
     * A [SkillConfig] has no skill selected (its `skillId` is blank). Fires on
     * `FieldId.SKILL_ID` so the picker disables Save until the user chooses a skill.
     */
    TARGET_SKILL_MISSING(app.knotwork.design.R.string.knotwork_node_validation_target_skill_missing),
}

/** Allowed range for [DecompositionConfig.maxSubtasks]. */
private val MAX_SUBTASKS_RANGE = 1..20

/** Allowed range for [EvaluationConfig.maxRetries]. */
private val MAX_RETRIES_RANGE = 0..5

/** Inclusive bounds on [IntentRouterConfig.classes]. */
private val INTENT_CLASSES_RANGE = 2..6

/** Inclusive bounds on [ClarificationConfig.quickReplies]. */
private val QUICK_REPLIES_RANGE = 0..4

/** Min wait-timeout in milliseconds for [ClarificationConfig.timeoutMs]. */
private const val MIN_WAIT_TIMEOUT_MS = 0

/**
 * Pure-Kotlin validator that walks a [NodeConfig] and returns one entry
 * per offending field. An empty map means the form may enable Save.
 *
 * Pipeline-wide title uniqueness
 * is delegated to the caller, which supplies [peerTitles] (the set of
 * sibling node titles excluding the one currently being edited).
 *
 * Side-effect-free and Compose-free so it can be unit-tested against
 * pure Kotlin and also called from a non-Compose canvas worker.
 */
object NodeConfigValidation {

    /**
     * Validates [config] against the spec rules.
     *
     * @param config the configuration payload to check.
     * @param peerTitles the set of sibling node titles in the same
     * pipeline, excluding [config.title]. Pass an empty set when the
     * caller has not assembled the pipeline yet (e.g. catalog previews).
     * @return a map of failing fields. Save should be disabled when the
     * map is non-empty.
     */
    fun validate(config: NodeConfig, peerTitles: Set<String>): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        validateTitle(config.title, peerTitles)?.let { errors[FieldId.TITLE] = it }
        when (config) {
            is InputConfig -> Unit // No fields beyond title and description.
            is OutputConfig -> Unit // No type-specific rules beyond title.
            is LiteRtConfig -> errors += validateLiteRt(config)
            is CloudConfig -> errors += validateCloud(config)
            is IntentRouterConfig -> errors += validateIntentRouter(config)
            is IfConditionConfig -> errors += validateIfCondition(config)
            is ClarificationConfig -> errors += validateClarification(config)
            is ToolConfig -> errors += validateTool()
            is DecompositionConfig -> errors += validateDecomposition(config)
            is QueueProcessorConfig -> errors += validateQueueProcessor()
            is EvaluationConfig -> errors += validateEvaluation(config)
            is SummaryConfig -> errors += validateSummary()
            is PipelineConfig -> errors += validatePipeline(config)
            is SkillConfig -> errors += validateSkill(config)
        }
        return errors
    }

    /**
     * Stand-alone title rule. Surfaced separately so the `EditorToolbar`
     * inline-name field can validate the pipeline-level title with the
     * same algorithm.
     *
     * @param title the candidate title.
     * @param peerTitles set of sibling titles to check uniqueness against.
     * @return the failure or `null` when the title passes.
     */
    fun validateTitle(title: String, peerTitles: Set<String>): ValidationFailure? = when {
        title.isBlank() -> ValidationFailure.TITLE_EMPTY
        title in peerTitles -> ValidationFailure.TITLE_DUPLICATE
        else -> null
    }

    private fun validateLiteRt(config: LiteRtConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        // `modelId.isBlank()` means "use the currently-active model" — a
        // valid first-class choice. No
        // REQUIRED error in that case; the executor resolves the model at
        // run-time through `LoadModelUseCase`'s null fallback.
        //
        // `temperature` / `topP` / `maxNewTokens` are NOT validated, for the
        // same reason `model` is not validated on the CLOUD sheet below: ADR
        // 0005 removed their controls, so a range error on one of them names a
        // field the user cannot see, cannot reach and cannot correct — it just
        // refuses to save. The values still round-trip on `LiteRtConfig`, and
        // an imported pipeline is free to carry any of them, because nothing
        // reads them during a run.
        if (config.systemPrompt.isBlank()) errors[FieldId.SYSTEM_PROMPT] = ValidationFailure.REQUIRED
        return errors
    }

    private fun validateCloud(config: CloudConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        // `CloudConfig.model` is not part of the sheet (model ids live once
        // per provider in Settings → External providers, not per-node). The
        // validator therefore no
        // longer flags a blank model — the executor falls back to the
        // provider's configured model at runtime when this field is empty.
        // `temperature` / `maxTokens` / `timeoutMs` are unvalidated for the same
        // reason as `model`, and as their LITE_RT counterparts above: ADR 0005
        // took their controls off the sheet, so flagging them blocks Save on a
        // field that is not on screen.
        if (config.systemPrompt.isBlank()) errors[FieldId.SYSTEM_PROMPT] = ValidationFailure.REQUIRED
        return errors
    }

    private fun validateIntentRouter(config: IntentRouterConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        val names = config.classes.map { it.name }
        when {
            config.classes.size !in INTENT_CLASSES_RANGE -> {
                errors[FieldId.CLASSES] = ValidationFailure.INTENT_CLASS_COUNT
            }
            names.any { it.isBlank() } -> {
                errors[FieldId.CLASSES] = ValidationFailure.REQUIRED
            }
            // Surface CLASS_NAME_DUPLICATE last so it only fires once every
            // class has a non-blank name — otherwise a row the user just
            // started typing into would race REQUIRED and CLASS_NAME_DUPLICATE.
            names.distinct().size != names.size -> {
                errors[FieldId.CLASSES] = ValidationFailure.CLASS_NAME_DUPLICATE
            }
        }
        if (config.classifierPrompt.isBlank()) errors[FieldId.CLASSIFIER_PROMPT] = ValidationFailure.REQUIRED
        val fallback = config.fallbackClass
        if (!fallback.isNullOrBlank()) {
            val declared = names.toSet()
            if (fallback !in declared) {
                errors[FieldId.FALLBACK_CLASS] = ValidationFailure.FALLBACK_NOT_IN_CLASSES
            }
        }
        return errors
    }

    private fun validateIfCondition(config: IfConditionConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        // The text expression is only required when the deterministic image-presence
        // branch is off — with it on, the expression is ignored, so a blank one is fine.
        if (!config.branchOnImage && config.expression.isBlank()) {
            errors[FieldId.EXPRESSION] = ValidationFailure.REQUIRED
        }
        // Keywords are free text and a blank threshold means "off", so neither
        // deterministic check can be invalid. The slider's own range is the only
        // bound, and a value outside it cannot be produced by the sheet.
        config.complexityThreshold?.let { threshold ->
            if (threshold <= 0) errors[FieldId.COMPLEXITY_THRESHOLD] = ValidationFailure.OUT_OF_RANGE
        }
        return errors
    }

    private fun validateClarification(config: ClarificationConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        if (config.questionTemplate.isBlank()) errors[FieldId.QUESTION_TEMPLATE] = ValidationFailure.REQUIRED
        if (config.quickReplies.size !in QUICK_REPLIES_RANGE) {
            errors[FieldId.QUICK_REPLIES] = ValidationFailure.OUT_OF_RANGE
        }
        config.timeoutMs?.let { ms ->
            if (ms < MIN_WAIT_TIMEOUT_MS) errors[FieldId.TIMEOUT_OPTIONAL] = ValidationFailure.OUT_OF_RANGE
        }
        return errors
    }

    // A blank toolId is the "Auto" selection — the agent picks the tool at run
    // time — so it is valid and must NOT block Save, and nothing else on the
    // sheet can be invalid: the confirm-policy override is a closed set of
    // choices. Kept as an explicit empty verdict rather than dropped from the
    // dispatch, so the `when` over config types stays exhaustive.
    private fun validateTool(): Map<FieldId, ValidationFailure> = emptyMap()

    private fun validateDecomposition(config: DecompositionConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        if (config.planningPrompt.isBlank()) errors[FieldId.PLANNING_PROMPT] = ValidationFailure.REQUIRED
        if (config.maxSubtasks !in MAX_SUBTASKS_RANGE) errors[FieldId.MAX_SUBTASKS] = ValidationFailure.OUT_OF_RANGE
        return errors
    }

    // QUEUE_PROCESSOR has nothing left to validate: the queue comes from
    // upstream and the only field is a toggle. Kept as an explicit empty
    // verdict rather than dropped from the dispatch, so the `when` over config
    // types stays exhaustive and a new field cannot be added without a home.
    private fun validateQueueProcessor(): Map<FieldId, ValidationFailure> = emptyMap()

    private fun validateEvaluation(config: EvaluationConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        if (config.criteriaPrompt.isBlank()) errors[FieldId.CRITERIA_PROMPT] = ValidationFailure.REQUIRED
        if (config.maxRetries !in MAX_RETRIES_RANGE) errors[FieldId.MAX_RETRIES] = ValidationFailure.OUT_OF_RANGE
        return errors
    }

    // The custom prompt is optional — blank leaves the built-in summarisation
    // prompt in place, which is a first-class choice — so nothing on the sheet
    // can be invalid. Explicit empty verdict, same reason as [validateTool].
    private fun validateSummary(): Map<FieldId, ValidationFailure> = emptyMap()

    private fun validatePipeline(config: PipelineConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        // A blank target is the "not chosen yet" state. Unlike TOOL's blank
        // "Auto" id (a meaningful runtime choice), a PIPELINE node with no
        // target can never run, so it blocks Save. Cross-pipeline reference
        // validity (cycles / missing / depth) is owned by the domain
        // composition validator at persist time, not here.
        if (config.targetPipelineId.isBlank()) {
            errors[FieldId.TARGET_PIPELINE_ID] = ValidationFailure.TARGET_PIPELINE_MISSING
        }
        return errors
    }

    private fun validateSkill(config: SkillConfig): Map<FieldId, ValidationFailure> {
        val errors = mutableMapOf<FieldId, ValidationFailure>()
        // A blank skill id is the "not chosen yet" state: a SKILL node with no
        // skill has no instruction to run, so it blocks Save. Whether the id
        // still resolves to a stored skill is checked at run time
        // (`SkillNodeExecutor`) / by `PipelineGraph.validate`, not here.
        if (config.skillId.isBlank()) {
            errors[FieldId.SKILL_ID] = ValidationFailure.TARGET_SKILL_MISSING
        }
        return errors
    }

    /**
     * Returns `true` when [json] parses cleanly as a JSON object. The
     * spec only allows schemas to be objects — arrays / primitives are
     * rejected, so the parser uses [JSONObject] directly.
     *
     * Caller is expected to skip the check when the field is blank /
     * `null`; this helper presumes non-empty input.
     */
    private fun isValidJson(json: String): Boolean = try {
        JSONObject(json)
        true
    } catch (_: JSONException) {
        false
    }
}
