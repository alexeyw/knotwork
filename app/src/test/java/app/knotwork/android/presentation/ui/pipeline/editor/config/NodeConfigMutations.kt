package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.design.components.pipelineeditor.ClarificationConfig
import app.knotwork.design.components.pipelineeditor.CloudConfig
import app.knotwork.design.components.pipelineeditor.CloudProvider
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

/**
 * One configuration per node type with every field moved off its default,
 * plus the view of a stored node the engine reads.
 *
 * Shared by the two tests that need "every field of every node type":
 * [CookbookRuntimeReachTest] observes which stored properties saving the sheet
 * changes, and [NodeConfigRuntimeAuthorityTest] checks the sheet shows those
 * same properties back. One table means a field added to a configuration is
 * exercised by both, or by neither — never checked for reach and left out of
 * what the sheet displays.
 */
internal object NodeConfigMutations {

    /** Title every mutated configuration carries. */
    const val TITLE = "Mutated title"

    /** Description every mutated configuration carries. */
    const val NOTE = "Mutated description"

    /** Prompt every mutated prompt field carries; equal to no registered default. */
    const val PROMPT = "Mutated prompt that no default prompt happens to equal."

    /**
     * The stored properties the engine reads, excluding the ones this test is
     * not about: the node's identity and canvas position, its label (which
     * always follows the sheet's title) and `configJson` (which always changes,
     * being the serialized sheet itself).
     */
    fun flatProperties(node: NodeModel): Map<String, Any?> = mapOf(
        "toolName" to node.toolName,
        "targetPipelineId" to node.targetPipelineId,
        "skillId" to node.skillId,
        "modelPath" to node.modelPath,
        "conditionComplexity" to node.conditionComplexity,
        "conditionKeywords" to node.conditionKeywords,
        "conditionPrompt" to node.conditionPrompt,
        "conditionHasImage" to node.conditionHasImage,
        "systemPrompt" to node.systemPrompt,
        "cloudProvider" to node.cloudProvider,
        "clarificationTimeoutMs" to node.clarificationTimeoutMs,
        "fallbackClass" to node.fallbackClass,
        "quickReplies" to node.quickReplies,
        "alwaysConfirm" to node.alwaysConfirm,
        "maxSubtasks" to node.maxSubtasks,
        "stopOnError" to node.stopOnError,
    )

    /** A freshly created node of [type], exactly as the editor would place it. */
    fun baseNode(type: NodeType): NodeModel = NodeModel(id = "node-1", type = type, x = 0f, y = 0f)

    /**
     * A configuration of [type] with every field moved off its default, so a
     * field that reaches the stored node cannot fail to show.
     *
     * Written out per type rather than derived reflectively: the point of the
     * test is to state, in code a reviewer can read, what "changed" means for
     * each field.
     */
    @Suppress("CyclomaticComplexMethod") // One arm per node type; each is a literal.
    fun mutatedConfig(type: NodeType): NodeConfig = when (type) {
        NodeType.INPUT -> InputConfig(title = TITLE, description = NOTE)

        NodeType.OUTPUT -> OutputConfig(
            title = TITLE,
            description = NOTE,
            systemPrompt = PROMPT,
        )

        NodeType.LITE_RT -> LiteRtConfig(
            title = TITLE,
            description = NOTE,
            modelId = "mutated-model",
            systemPrompt = PROMPT,
            temperature = 1.5f,
            topP = 0.1f,
            maxNewTokens = 64,
            stopTokens = listOf("STOP"),
        )

        NodeType.CLOUD -> CloudConfig(
            title = TITLE,
            description = NOTE,
            provider = CloudProvider.ANTHROPIC,
            model = "mutated-cloud-model",
            systemPrompt = PROMPT,
            temperature = 1.5f,
            maxTokens = 99,
            timeoutMs = 1_234,
        )

        NodeType.INTENT_ROUTER -> IntentRouterConfig(
            title = TITLE,
            description = NOTE,
            classes = listOf(IntentClass("Alpha"), IntentClass("Beta")),
            classifierPrompt = PROMPT,
            fallbackClass = "Alpha",
            engineProvider = CloudProvider.ANTHROPIC,
        )

        NodeType.IF_CONDITION -> IfConditionConfig(
            title = TITLE,
            description = NOTE,
            expression = PROMPT,
            keywords = "urgent, now",
            complexityThreshold = 500,
            branchOnImage = true,
            engineProvider = CloudProvider.ANTHROPIC,
        )

        NodeType.CLARIFICATION -> ClarificationConfig(
            title = TITLE,
            description = NOTE,
            questionTemplate = PROMPT,
            quickReplies = listOf("Yes", "No"),
            timeoutMs = 4_321,
        )

        NodeType.TOOL -> ToolConfig(
            title = TITLE,
            description = NOTE,
            toolId = "mutated_tool",
            alwaysConfirm = true,
            engineProvider = CloudProvider.ANTHROPIC,
        )

        NodeType.DECOMPOSITION -> DecompositionConfig(
            title = TITLE,
            description = NOTE,
            planningPrompt = PROMPT,
            maxSubtasks = 9,
            engineProvider = CloudProvider.ANTHROPIC,
        )

        NodeType.QUEUE_PROCESSOR -> QueueProcessorConfig(
            title = TITLE,
            description = NOTE,
            stopOnError = false,
        )

        NodeType.EVALUATION -> EvaluationConfig(
            title = TITLE,
            description = NOTE,
            criteriaPrompt = PROMPT,
            maxRetries = 5,
            engineProvider = CloudProvider.ANTHROPIC,
        )

        NodeType.SUMMARY -> SummaryConfig(
            title = TITLE,
            description = NOTE,
            customPrompt = PROMPT,
        )

        NodeType.PIPELINE -> PipelineConfig(
            title = TITLE,
            description = NOTE,
            targetPipelineId = "mutated-target",
            targetPipelineName = "Mutated target",
        )

        NodeType.SKILL -> SkillConfig(
            title = TITLE,
            description = NOTE,
            skillId = "mutated-skill",
            skillName = "Mutated skill",
            instructionPreview = "Instruction",
            toolRestrictionSummary = "No tools",
            engine = SkillEngine.CLOUD,
            alwaysConfirm = true,
        )
    }
}
