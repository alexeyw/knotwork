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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the published claim "this configuration field reaches the run" against
 * what [NodeConfigCodec] actually does.
 *
 * `docs/cookbook.md` prints, for every field of every node configuration sheet,
 * whether saving it changes anything at run time. That column is the reason the
 * document is worth reading: a sheet shows fields the engine consults and
 * fields it does not, and the difference is invisible in the app — an ignored
 * field still accepts input, still survives a reopen, still exports.
 *
 * The column cannot be checked by the generator that writes it. A generator
 * paired with its own drift check agrees with itself precisely when it is
 * wrong, and no static reading of a declaration can tell whether the engine
 * later reads the value. So the check lives here, on the other side: this test
 * mutates every field of every configuration, pushes it through the real codec,
 * and observes which properties of the stored [NodeModel] actually changed.
 * Those observations are then compared against the committed Markdown — a
 * different file, produced by different code, read by a different parser.
 *
 * Consequences, both of which are the point:
 *
 *  - fixing the codec so an ignored field starts reaching the engine fails this
 *    test until `./gradlew :app:generateCookbookDocs` is re-run, so the fix and
 *    the documentation land together;
 *  - a verdict claiming a field works when it does not fails here rather than
 *    being published.
 *
 * Gradle's `:app:test` runs in the `app/` working directory, so the cookbook
 * resolves as `../docs/cookbook.md`.
 */
class CookbookRuntimeReachTest {

    private val cookbook = File("../docs/cookbook.md")

    @Test
    fun `given the cookbook when its run-time verdicts are read then the codec agrees with every one`() {
        val documented = parseDocumentedTargets()
        assertEquals(
            "The cookbook does not describe every node type — regenerate it before trusting this test.",
            NodeType.entries.map { it.name }.toSet(),
            documented.keys,
        )

        NodeType.entries.forEach { type ->
            val observed = observedTargets(type)
            assertEquals(
                "docs/cookbook.md and NodeConfigCodec disagree about $type. The document says the fields " +
                    "reaching the run are ${documented.getValue(type.name).sorted()}; saving the sheet " +
                    "actually changes ${observed.sorted()}. Fix whichever is wrong, then re-run " +
                    "`./gradlew :app:generateCookbookDocs`.",
                documented.getValue(type.name),
                observed,
            )
        }
    }

    @Test
    fun `given the cookbook when the four prompt fields are read then each one writes systemPrompt`() {
        // Inverted from the canary it replaces. While the codec dropped these
        // four, the test pinned the defect *by name* so a fix could not land
        // without updating the document that described it — which is exactly
        // how it behaved. It now pins the repair, for the same reason: the
        // claim a reader is least likely to believe is the one worth holding
        // against the code.
        val documented = parseDocumentedTargets()

        listOf(
            NodeType.INTENT_ROUTER,
            NodeType.DECOMPOSITION,
            NodeType.EVALUATION,
            NodeType.SUMMARY,
        ).forEach { type ->
            assertTrue(
                "${'$'}{type.name}'s prompt field no longer writes `systemPrompt`; the sheet would " +
                    "accept a prompt the run never sees. Fix `NodeConfigCodec.apply`, not this test.",
                "systemPrompt" in documented.getValue(type.name),
            )
        }
    }

    /**
     * Reads the generated appendix and returns, per node type, the `NodeModel`
     * properties the document claims a field reaches.
     *
     * Every node type appears as a key, including the three whose fields all
     * reach nothing — otherwise a type whose only wired field was lost would
     * simply vanish from the comparison instead of failing it.
     */
    private fun parseDocumentedTargets(): Map<String, Set<String>> {
        val markdown = cookbook.readText()
        val block = markdown.substringAfter("<!-- AUTO-GEN:FIELD_TABLE -->", "")
            .substringBefore("<!-- /AUTO-GEN:FIELD_TABLE -->", "")
        check(block.isNotBlank()) { "docs/cookbook.md has no generated FIELD_TABLE block." }

        val documented = mutableMapOf<String, MutableSet<String>>()
        block.lineSequence().forEach { line ->
            val type = ROW_TYPE_RE.find(line)?.groupValues?.get(1) ?: return@forEach
            val targets = documented.getOrPut(type) { mutableSetOf() }
            RUNTIME_ROW_RE.find(line)?.let { targets += it.groupValues[1] }
        }
        check(documented.isNotEmpty()) { "Parsed no rows from the appendix — the parse is wrong, not the document." }
        return documented
    }

    /**
     * Returns the `NodeModel` properties that actually move when every field of
     * this type's configuration is changed at once and the sheet is saved.
     */
    private fun observedTargets(type: NodeType): Set<String> {
        val base = baseNode(type)
        val applied = NodeConfigCodec.apply(base, mutatedConfig(type))
        val before = flatProperties(base)
        val after = flatProperties(applied)
        return before.keys.filter { before[it] != after[it] }.toSet()
    }

    /**
     * The stored properties the engine reads, excluding the ones this test is
     * not about: the node's identity and canvas position, its label (which
     * always follows the sheet's title) and `configJson` (which always changes,
     * being the serialized sheet itself).
     */
    private fun flatProperties(node: NodeModel): Map<String, Any?> = mapOf(
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
    private fun baseNode(type: NodeType): NodeModel = NodeModel(id = "node-1", type = type, x = 0f, y = 0f)

    /**
     * A configuration of [type] with every field moved off its default, so a
     * field that reaches the stored node cannot fail to show.
     *
     * Written out per type rather than derived reflectively: the point of the
     * test is to state, in code a reviewer can read, what "changed" means for
     * each field.
     */
    @Suppress("CyclomaticComplexMethod") // One arm per node type; each is a literal.
    private fun mutatedConfig(type: NodeType): NodeConfig = when (type) {
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

    private companion object {
        const val TITLE = "Mutated title"
        const val NOTE = "Mutated description"
        const val PROMPT = "Mutated prompt that no default prompt happens to equal."

        val ROW_TYPE_RE = Regex("""^\|\s*`([A-Z][A-Z0-9_]*)`\s*\|""")
        val RUNTIME_ROW_RE = Regex("""\*\*Yes\*\* — saved as the node's `(\w+)`""")
    }
}
