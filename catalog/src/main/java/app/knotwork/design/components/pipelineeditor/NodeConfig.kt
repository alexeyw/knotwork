package app.knotwork.design.components.pipelineeditor

/**
 * Per-node configuration payload rendered inside [NodeConfigSheet]. The
 * sheet's chrome (drag handle, type pill, sticky action row) is shared;
 * the body switches on `when (config)` to a per-type form.
 *
 * Each variant captures the *value*; the matching form
 * composable in `forms/` captures the *rendering*.
 */
sealed interface NodeConfig {
    /** Display title shown both on the [NodeCard] and in the sheet header. */
    val title: String

    /** Node type — drives the colour strip and the type pill on the sheet. */
    val type: NodeType

    /** Optional free-form note rendered under the type pill. */
    val description: String?
}

/**
 * One installed local model the user can pick in the LiteRt config sheet. Defined here
 * (catalog side) as a plain pair of id / display name plus an `isActive` flag so the
 * design module does not depend on the `:app` `LocalModel` Room entity. The screen
 * maps `app.knotwork.android.domain.models.LocalModel` to this type at the sheet boundary.
 *
 * @property id canonical model identifier — typically the model file path. This is the
 * value written back into [LiteRtConfig.modelId] when the user picks the model.
 * @property displayName human-readable name shown in the dropdown row.
 * @property isActive whether this is the model that the runtime currently loads. The
 * picker badges this row with an "active" suffix.
 */
data class LocalModelOption(val id: String, val displayName: String, val isActive: Boolean)

/**
 * Cloud LLM provider for [CloudConfig].
 *
 * [AUTO] defers the choice to runtime — the executor picks a provider from the
 * configured API keys. It maps to the domain `CloudProvider.AUTO_KEY` wire
 * sentinel (`"auto"`) rather than a concrete provider.
 */
enum class CloudProvider { OPEN_AI, ANTHROPIC, GOOGLE, COMPATIBLE, AUTO }

/**
 * Configuration for [NodeType.INPUT] — pipeline entry contract.
 *
 * Carries no payload of its own. The entry contract is fixed — the run's text
 * arrives as it is — so the `inputName` and `schemaJson` fields that used to sit
 * here were removed rather than left as controls that changed nothing.
 *
 * @property title display title.
 * @property description optional one-line note.
 */
data class InputConfig(override val title: String, override val description: String? = null) : NodeConfig {
    override val type: NodeType get() = NodeType.INPUT
}

/**
 * Configuration for [NodeType.OUTPUT] — pipeline exit.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property systemPrompt optional system prompt sent to the model that
 *   formats the final response. When blank the node forwards the upstream
 *   text verbatim (the engine wraps a non-blank prompt around the LLM
 *   call). Supports `$DATE` / `$TIME` / `$TOOLS` / `$MODEL` /
 *   `$MEMORY_SUMMARY` variables.
 */
data class OutputConfig(
    override val title: String,
    override val description: String? = null,
    val systemPrompt: String = "",
) : NodeConfig {
    override val type: NodeType get() = NodeType.OUTPUT
}

/**
 * Configuration for [NodeType.LITE_RT] — on-device LiteRT inference.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property modelId installed local model identifier.
 * @property systemPrompt mono multi-line prompt; supports `$DATE` / `$TIME`
 * / `$TOOLS` / `$MODEL` / `$MEMORY_SUMMARY` variables resolved at runtime.
 * @property temperature sampling temperature, range `0.0..2.0`.
 * @property topP nucleus-sampling cumulative probability, range `0.0..1.0`.
 * @property maxNewTokens token-generation cap, range `32..4096`.
 * @property stopTokens optional list of stop sequences.
 */
data class LiteRtConfig(
    override val title: String,
    override val description: String? = null,
    val modelId: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxNewTokens: Int = 512,
    val stopTokens: List<String> = emptyList(),
) : NodeConfig {
    override val type: NodeType get() = NodeType.LITE_RT
}

/**
 * Configuration for [NodeType.CLOUD] — external LLM API.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property provider cloud LLM provider.
 * @property model free-text model id; dropdown suggests known ids per provider.
 * @property systemPrompt mono multi-line prompt with the same variable
 * chips as [LiteRtConfig].
 * @property temperature sampling temperature, range `0.0..2.0`.
 * @property maxTokens hard cap on the response length.
 * @property timeoutMs request timeout in milliseconds.
 */
data class CloudConfig(
    override val title: String,
    override val description: String? = null,
    val provider: CloudProvider = CloudProvider.OPEN_AI,
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val timeoutMs: Int = 30_000,
) : NodeConfig {
    override val type: NodeType get() = NodeType.CLOUD
}

/**
 * Per-class declaration for [IntentRouterConfig.classes].
 *
 * @property name machine-readable class id rendered as the out-port label.
 * @property description human-readable description used by the classifier prompt.
 * @property examples optional list of training-side example strings.
 */
data class IntentClass(val name: String, val description: String = "", val examples: List<String> = emptyList())

/**
 * Configuration for [NodeType.INTENT_ROUTER] — classifier with one out-port
 * per declared class.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property classes 2..6 declared classes (validated at save).
 * @property classifierPrompt mono multi-line prompt consumed by the local LLM.
 * @property fallbackClass class name to route to when no match is found;
 * `null` = no fallback (the engine raises an error).
 * @property engineProvider optional cloud provider backing this node's
 * structured inference; `null` runs on-device (the default).
 */
data class IntentRouterConfig(
    override val title: String,
    override val description: String? = null,
    val classes: List<IntentClass> = emptyList(),
    val classifierPrompt: String = "",
    val fallbackClass: String? = null,
    val engineProvider: CloudProvider? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.INTENT_ROUTER
}

/**
 * Configuration for [NodeType.IF_CONDITION] — boolean branch.
 *
 * The four checks are a priority order, not alternatives: image presence, then
 * keywords, then input length, then the question put to the model. The first one
 * that matches decides the branch and the rest are never consulted — which is
 * also the order the sheet lists them in.
 *
 * The port labels are fixed at `True` / `False`, because that is what the engine
 * matches an outgoing edge against; the `labelTrue` / `labelFalse` fields that
 * used to suggest otherwise were removed.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property expression the yes/no question put to the model, in plain language.
 * Asked last, and only when none of the checks above it matched — it is the one
 * that costs a model call.
 * @property keywords comma-separated words matched against the run's text
 * before any model is asked; any match takes the True branch outright. Blank
 * disables the check.
 * @property complexityThreshold input length in characters at or above which the
 * True branch is taken without asking a model; `null` disables the check.
 * @property branchOnImage when `true`, the node takes the True branch whenever the
 * run input carries an image attachment — checked before everything else, so a
 * pipeline can fork on "did the user send a picture?".
 * @property engineProvider optional cloud provider backing this node's
 * structured inference; `null` runs on-device (the default).
 */
data class IfConditionConfig(
    override val title: String,
    override val description: String? = null,
    val expression: String = "",
    val keywords: String = "",
    val complexityThreshold: Int? = null,
    val branchOnImage: Boolean = false,
    val engineProvider: CloudProvider? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.IF_CONDITION
}

/**
 * Configuration for [NodeType.CLARIFICATION] — mid-pipeline question to
 * the user.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property questionTemplate mono multi-line template; supports `$INPUT`
 * and upstream variables.
 * @property quickReplies 0..4 quick-reply chips surfaced under the question.
 * @property timeoutMs optional wait timeout; `null` waits indefinitely.
 */
data class ClarificationConfig(
    override val title: String,
    override val description: String? = null,
    val questionTemplate: String = "",
    val quickReplies: List<String> = emptyList(),
    val timeoutMs: Int? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.CLARIFICATION
}

/**
 * Configuration for [NodeType.TOOL] — AppFunctions / MCP tool invocation.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property toolId fully-qualified tool identifier (e.g. `fs.write_file`).
 * @property alwaysConfirm ask for approval on every call through this node,
 * whatever the tool's risk. `false` inherits the tool's risk and the user's
 * settings. One-directional on purpose: the node can add a prompt, never remove
 * one — a pipeline file is a document that can be shared, and a node able to
 * waive approval would let somebody else's document past the gate.
 * @property engineProvider optional cloud provider backing this node's
 * structured tool-selection / argument inference; `null` runs on-device.
 */
data class ToolConfig(
    override val title: String,
    override val description: String? = null,
    val toolId: String = "",
    val alwaysConfirm: Boolean = false,
    val engineProvider: CloudProvider? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.TOOL
}

/**
 * Configuration for [NodeType.DECOMPOSITION] — breaks a task into a list
 * of subtasks.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property planningPrompt mono multi-line planning prompt; supports `$INPUT`.
 * @property maxSubtasks generation cap, range `1..20`.
 * @property engineProvider optional cloud provider backing this node's
 * structured inference; `null` runs on-device (the default).
 */
data class DecompositionConfig(
    override val title: String,
    override val description: String? = null,
    val planningPrompt: String = "",
    val maxSubtasks: Int = 5,
    val engineProvider: CloudProvider? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.DECOMPOSITION
}

/**
 * Configuration for [NodeType.QUEUE_PROCESSOR] — iterates a list.
 *
 * The queue itself is not configured here: it is the subtask list produced
 * upstream, the current subtask arrives as the node's input, and subtasks always
 * run one at a time. The `inputList` / `itemVariable` / `parallelism` fields that
 * once said otherwise were removed rather than left saying it.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property stopOnError when `true`, the first failure short-circuits the loop.
 */
data class QueueProcessorConfig(
    override val title: String,
    override val description: String? = null,
    val stopOnError: Boolean = true,
) : NodeConfig {
    override val type: NodeType get() = NodeType.QUEUE_PROCESSOR
}

/**
 * Configuration for [NodeType.EVALUATION] — judges a step result.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property criteriaPrompt mono multi-line evaluation prompt; supports
 * `$INPUT` and `$ATTEMPT`.
 * @property maxRetries retry budget, range `0..5`. Surfaces the `Retry`
 * out-port only when greater than zero.
 * @property engineProvider optional cloud provider backing this node's
 * structured inference; `null` runs on-device (the default).
 */
data class EvaluationConfig(
    override val title: String,
    override val description: String? = null,
    val criteriaPrompt: String = "",
    val maxRetries: Int = 2,
    val engineProvider: CloudProvider? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.EVALUATION
}

/**
 * Configuration for [NodeType.SUMMARY] — condenses many node outputs.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property customPrompt mono multi-line prompt replacing the built-in
 * summarisation prompt; blank leaves the default in place. Shape and length
 * are asked for here, in words — the `format` and `targetLengthChars` fields
 * that used to promise them separately reached no executor and were removed.
 */
data class SummaryConfig(
    override val title: String,
    override val description: String? = null,
    val customPrompt: String? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.SUMMARY
}

/**
 * Why a candidate pipeline cannot be picked as a [PipelineConfig.targetPipelineId].
 * Each reason maps to a distinct inline message because the user's remedy differs:
 *
 *  - [SELF] — pick a different pipeline.
 *  - [CYCLE] — break the back-reference (the offending pipeline is named via
 *    [PipelineTargetOption.cycleCulprit]).
 *  - [DEPTH] — reduce the nesting depth (the ceiling is carried in
 *    [PipelineTargetOption.depthLimit]).
 */
enum class PipelineTargetDisabledReason { SELF, CYCLE, DEPTH }

/**
 * One row in the [PipelineConfig] target picker. The app resolves the full
 * catalogue of saved pipelines, classifies each against the pipeline being
 * edited (reusing the domain composition validator), and hands the result
 * down as a list of these options so the catalog stays free of any
 * cross-pipeline reachability logic.
 *
 * @property id the candidate pipeline's stable id, written into
 * [PipelineConfig.targetPipelineId] when picked.
 * @property name the candidate pipeline's display name shown in the row.
 * @property selectable `true` when the option may be chosen; `false` renders
 * the row disabled with [disabledReason].
 * @property disabledReason why the option is disabled; `null` when [selectable].
 * @property cycleCulprit for [PipelineTargetDisabledReason.CYCLE], the display
 * name of the pipeline that already calls the edited one (so the reason can
 * name it). `null` for the other reasons.
 * @property depthLimit for [PipelineTargetDisabledReason.DEPTH], the configured
 * maximum nesting depth. `null` for the other reasons.
 */
data class PipelineTargetOption(
    val id: String,
    val name: String,
    val selectable: Boolean = true,
    val disabledReason: PipelineTargetDisabledReason? = null,
    val cycleCulprit: String? = null,
    val depthLimit: Int? = null,
)

/**
 * Configuration for [NodeType.PIPELINE] — runs another saved pipeline as a
 * nested sub-call (composition).
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property targetPipelineId id of the pipeline this node runs. Blank means
 * "no target chosen yet" — the validator surfaces [ValidationFailure.TARGET_PIPELINE_MISSING]
 * and Save stays disabled until the user picks one.
 * @property targetPipelineName resolved display name of [targetPipelineId],
 * supplied by the app so the picker can show the current selection without the
 * catalog resolving ids. `null` when no target is set or the id no longer
 * resolves to a stored pipeline.
 */
data class PipelineConfig(
    override val title: String,
    override val description: String? = null,
    val targetPipelineId: String = "",
    val targetPipelineName: String? = null,
) : NodeConfig {
    override val type: NodeType get() = NodeType.PIPELINE
}

/** Inference engine a [SkillConfig] node runs the skill on. */
enum class SkillEngine { LITE_RT, CLOUD }

/**
 * One row in the [SkillConfig] skill picker. The app resolves the full skill
 * library (bundled + user) and hands the rows down so the catalog stays free
 * of any skill-storage knowledge.
 *
 * @property id the skill's stable id, written into [SkillConfig.skillId] when picked.
 * @property name the skill's display name shown in the row.
 * @property instructionPreview the skill's instruction text, copied into
 * [SkillConfig.instructionPreview] on pick so the read-only preview updates
 * immediately without a round-trip to the app.
 * @property toolRestrictionSummary one-line allowlist summary (e.g. "All tools",
 * "No tools", "3 tools"), copied into [SkillConfig.toolRestrictionSummary] on pick.
 */
data class SkillOption(
    val id: String,
    val name: String,
    val instructionPreview: String,
    val toolRestrictionSummary: String,
)

/**
 * Configuration for [NodeType.SKILL] — runs a reusable skill (fixed
 * instruction + visible-tool allowlist + default context) as an inference step.
 *
 * The skill supplies the instruction, the allowlist, and the default context;
 * the node only chooses *which* skill and *which engine* to run it on. The
 * instruction preview and allowlist summary are read-only — they are edited in
 * the Skill library, not here.
 *
 * @property title display title.
 * @property description optional one-line note.
 * @property skillId id of the skill this node runs. Blank means "no skill chosen
 * yet" — the validator surfaces [ValidationFailure.TARGET_SKILL_MISSING] and Save
 * stays disabled until the user picks one.
 * @property skillName resolved display name of [skillId], supplied by the app so
 * the picker can show the current selection. `null` when no skill is set or the id
 * no longer resolves to a stored skill.
 * @property instructionPreview resolved instruction text of the selected skill,
 * shown read-only. `null` when no skill is selected.
 * @property toolRestrictionSummary resolved one-line summary of the selected
 * skill's tool allowlist (e.g. "All tools", "No tools", "3 tools"), shown as the
 * allowlist indicator. `null` when no skill is selected.
 * @property engine which inference engine runs the skill ([SkillEngine.LITE_RT]
 * local or [SkillEngine.CLOUD]).
 * @property alwaysConfirm ask for approval on every tool call the skill makes
 * through this node, whatever the tool's risk — the same one-directional switch
 * as [ToolConfig.alwaysConfirm]: it can add a prompt, never remove one.
 */
data class SkillConfig(
    override val title: String,
    override val description: String? = null,
    val skillId: String = "",
    val skillName: String? = null,
    val instructionPreview: String? = null,
    val toolRestrictionSummary: String? = null,
    val engine: SkillEngine = SkillEngine.LITE_RT,
    val alwaysConfirm: Boolean = false,
) : NodeConfig {
    override val type: NodeType get() = NodeType.SKILL
}
