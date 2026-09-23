package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.design.components.pipelineeditor.ClarificationConfig
import app.knotwork.design.components.pipelineeditor.CloudConfig
import app.knotwork.design.components.pipelineeditor.CloudProvider
import app.knotwork.design.components.pipelineeditor.DecompositionConfig
import app.knotwork.design.components.pipelineeditor.EvaluationConfig
import app.knotwork.design.components.pipelineeditor.IfConditionConfig
import app.knotwork.design.components.pipelineeditor.IntentRouterConfig
import app.knotwork.design.components.pipelineeditor.LiteRtConfig
import app.knotwork.design.components.pipelineeditor.PipelineConfig
import app.knotwork.design.components.pipelineeditor.SkillConfig
import app.knotwork.design.components.pipelineeditor.SkillEngine
import app.knotwork.design.components.pipelineeditor.SummaryConfig
import app.knotwork.design.components.pipelineeditor.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.knotwork.android.domain.models.CloudProvider as DomainCloudProvider
import app.knotwork.design.components.pipelineeditor.NodeType as CatalogNodeType

class NodeConfigCodecTest {

    // Timber call sites in the codec are no-ops without a planted tree, so no setup is needed.

    private fun node(type: NodeType, label: String = "Node"): NodeModel = NodeModel(
        id = "n-1",
        type = type,
        x = 0f,
        y = 0f,
        label = label,
    )

    @Test
    fun `given LiteRt config when encode-then-decode then payload preserved`() {
        val source = node(NodeType.LITE_RT, "Local")
        val config = LiteRtConfig(
            title = "Local",
            modelId = "gemma-2b-it",
            systemPrompt = "Answer concisely as of \$DATE.",
            temperature = 0.5f,
            topP = 0.85f,
            maxNewTokens = 1024,
            stopTokens = listOf("###", "END"),
        )
        val json = NodeConfigCodec.encode(config)
        val applied = source.copy(configJson = json)
        val decoded = NodeConfigCodec.decode(applied) as LiteRtConfig
        assertEquals("Local", decoded.title)
        assertEquals("gemma-2b-it", decoded.modelId)
        assertEquals(0.5f, decoded.temperature, 1e-3f)
        assertEquals(0.85f, decoded.topP, 1e-3f)
        assertEquals(1024, decoded.maxNewTokens)
        assertEquals(listOf("###", "END"), decoded.stopTokens)
    }

    @Test
    fun `given Cloud config when encode-then-decode then provider preserved`() {
        val source = node(NodeType.CLOUD, "Cloud")
        val config = CloudConfig(
            title = "Cloud",
            provider = app.knotwork.design.components.pipelineeditor.CloudProvider.ANTHROPIC,
            model = "claude-opus-4-7",
            systemPrompt = "You are helpful.",
            temperature = 0.3f,
            maxTokens = 2048,
            timeoutMs = 45_000,
        )
        val applied = source.copy(configJson = NodeConfigCodec.encode(config))
        val decoded = NodeConfigCodec.decode(applied) as CloudConfig
        assertEquals(app.knotwork.design.components.pipelineeditor.CloudProvider.ANTHROPIC, decoded.provider)
        assertEquals("claude-opus-4-7", decoded.model)
        assertEquals(2048, decoded.maxTokens)
        assertEquals(45_000, decoded.timeoutMs)
    }

    @Test
    fun `given Cloud AUTO provider when apply then flat field is the auto sentinel and decodes back to AUTO`() {
        val source = node(NodeType.CLOUD, "Cloud")
        val config = CloudConfig(title = "Cloud", provider = CloudProvider.AUTO)

        val applied = NodeConfigCodec.apply(source, config)

        // Saving the sheet must persist "auto" (not a concrete provider), so the
        // runtime keeps auto-routing.
        assertEquals("auto", applied.cloudProvider)
        // …and re-decoding the saved node round-trips back to AUTO.
        assertEquals(CloudProvider.AUTO, (NodeConfigCodec.decode(applied) as CloudConfig).provider)
    }

    @Test
    fun `given Ollama CLOUD node when decoded and re-applied then it stays Ollama`() {
        // The privacy regression: `COMPATIBLE` is one tile for two domain providers,
        // and the catalog->domain direction resolved it to DeepSeek. Opening an Ollama
        // node's config sheet and saving it — without touching the provider — pointed
        // a LAN-local node at a third-party endpoint, silently.
        val src = node(NodeType.CLOUD, "Cloud").copy(cloudProvider = "ollama")

        val decoded = NodeConfigCodec.decode(src) as CloudConfig
        assertEquals(CloudProvider.COMPATIBLE, decoded.provider)

        val applied = NodeConfigCodec.apply(src, decoded)

        assertEquals("ollama", applied.cloudProvider)
    }

    @Test
    fun `given DeepSeek CLOUD node when decoded and re-applied then it stays DeepSeek`() {
        // The other half of the shared tile must be equally stable.
        val src = node(NodeType.CLOUD, "Cloud").copy(cloudProvider = "deepseek")

        val applied = NodeConfigCodec.apply(src, NodeConfigCodec.decode(src) as CloudConfig)

        assertEquals("deepseek", applied.cloudProvider)
    }

    @Test
    fun `given Ollama CLOUD node when the user switches to a different tile then the change sticks`() {
        // Preserving must not become "ignoring": a real provider change is still a change.
        val src = node(NodeType.CLOUD, "Cloud").copy(cloudProvider = "ollama")

        val applied = NodeConfigCodec.apply(src, CloudConfig(title = "Cloud", provider = CloudProvider.GOOGLE))

        assertEquals("google", applied.cloudProvider)
    }

    @Test
    fun `given a non-compatible node when the user picks COMPATIBLE then it resolves to DeepSeek`() {
        // A fresh pick of the shared tile has no prior compatible provider to preserve,
        // so it falls through to the tile's canonical instance.
        val src = node(NodeType.CLOUD, "Cloud").copy(cloudProvider = "google")

        val applied = NodeConfigCodec.apply(src, CloudConfig(title = "Cloud", provider = CloudProvider.COMPATIBLE))

        assertEquals("deepseek", applied.cloudProvider)
    }

    @Test
    fun `given structured node on Ollama when decoded and re-applied then it stays Ollama`() {
        // Same shared-tile hazard on the structured engine picker (TOOL / IF /
        // INTENT_ROUTER / DECOMPOSITION / EVALUATION), not just the CLOUD node.
        val src = node(NodeType.DECOMPOSITION, "Plan").copy(cloudProvider = "ollama")

        val applied = NodeConfigCodec.apply(src, NodeConfigCodec.decode(src) as DecompositionConfig)

        assertEquals("ollama", applied.cloudProvider)
    }

    @Test
    fun `given legacy auto CLOUD node with no configJson when decode then provider is AUTO`() {
        // Regression for the round-trip bug: a browser-edited / default CLOUD
        // node persists cloudProvider="auto" with no rich payload. It must decode
        // as AUTO, not silently fall back to OpenAI.
        val legacy = node(NodeType.CLOUD, "Cloud").copy(cloudProvider = "auto", configJson = null)

        val decoded = NodeConfigCodec.decode(legacy) as CloudConfig

        assertEquals(CloudProvider.AUTO, decoded.provider)
    }

    @Test
    fun `given IfCondition config when encode-then-decode then every check is preserved`() {
        val src = node(NodeType.IF_CONDITION, "Branch")
        val config = IfConditionConfig(
            title = "Branch",
            expression = "score > 0.8",
            keywords = "urgent, escalate",
            complexityThreshold = 800,
        )
        val decoded = NodeConfigCodec.decode(src.copy(configJson = NodeConfigCodec.encode(config))) as IfConditionConfig
        assertEquals("score > 0.8", decoded.expression)
        assertEquals("urgent, escalate", decoded.keywords)
        assertEquals(800, decoded.complexityThreshold)
        assertEquals(false, decoded.branchOnImage)
    }

    @Test
    fun `given an imported node carrying keywords when decoded then the sheet shows them`() {
        // The reverse-class case this repair exists for: `conditionKeywords` and
        // `conditionComplexity` were read by the engine long before they had a
        // control, so a pipeline imported with them decided every branch while
        // the sheet showed nothing. Decode reads the flat fields when the JSON
        // payload has no key for them.
        val imported = node(NodeType.IF_CONDITION, "Branch").copy(
            conditionKeywords = "refund, cancel",
            conditionComplexity = 250,
            configJson = null,
        )

        val decoded = NodeConfigCodec.decode(imported) as IfConditionConfig

        assertEquals("refund, cancel", decoded.keywords)
        assertEquals(250, decoded.complexityThreshold)
    }

    @Test
    fun `given IfCondition config when apply then both checks reach the node`() {
        val patched = NodeConfigCodec.apply(
            node(NodeType.IF_CONDITION),
            IfConditionConfig(title = "Branch", expression = "urgent?", keywords = "asap", complexityThreshold = 120),
        )

        assertEquals("asap", patched.conditionKeywords)
        assertEquals(120, patched.conditionComplexity)
    }

    @Test
    fun `given blank keywords when apply then the node stores null rather than an empty string`() {
        // `EvaluateIfConditionUseCase` splits the string and treats an empty
        // result as "no keyword check", so an empty string and null behave the
        // same at run time — but only null reads as "not configured" everywhere
        // else, including an exported file.
        val patched = NodeConfigCodec.apply(
            node(NodeType.IF_CONDITION),
            IfConditionConfig(title = "Branch", expression = "urgent?", keywords = "   "),
        )

        assertNull(patched.conditionKeywords)
    }

    @Test
    fun `given IfCondition config with image branch when round-trip then flag preserved`() {
        val src = node(NodeType.IF_CONDITION, "Has image?")
        val config = IfConditionConfig(title = "Has image?", branchOnImage = true)

        val roundTripped = src.copy(configJson = NodeConfigCodec.encode(config))
        val decoded = NodeConfigCodec.decode(roundTripped) as IfConditionConfig

        assertEquals(true, decoded.branchOnImage)
        // The encode side must also project the flag onto the flat domain row so the
        // executor reads it even for older callers that ignore the JSON payload.
        assertEquals(true, NodeConfigCodec.apply(src, config).conditionHasImage)
    }

    @Test
    fun `given Clarification config with timeout when round-trip then timeout preserved`() {
        val src = node(NodeType.CLARIFICATION, "Ask")
        val config = ClarificationConfig(
            title = "Ask",
            questionTemplate = "What would you like to do?",
            quickReplies = listOf("Continue", "Skip"),
            timeoutMs = 30_000,
        )
        val decoded = NodeConfigCodec.decode(
            src.copy(configJson = NodeConfigCodec.encode(config)),
        ) as ClarificationConfig
        assertEquals("What would you like to do?", decoded.questionTemplate)
        assertEquals(listOf("Continue", "Skip"), decoded.quickReplies)
        assertEquals(30_000, decoded.timeoutMs)
    }

    @Test
    fun `given node without config JSON when decode then derived from legacy fields`() {
        val src = NodeModel(
            id = "n",
            type = NodeType.LITE_RT,
            x = 0f,
            y = 0f,
            label = "Local",
            modelPath = "gemma-2b-it",
            systemPrompt = "Hi",
        )
        val decoded = NodeConfigCodec.decode(src) as LiteRtConfig
        assertEquals("Local", decoded.title)
        assertEquals("gemma-2b-it", decoded.modelId)
        assertEquals("Hi", decoded.systemPrompt)
    }

    @Test
    fun `given malformed JSON when decode then falls back to legacy derivation`() {
        val src = NodeModel(
            id = "n",
            type = NodeType.LITE_RT,
            x = 0f,
            y = 0f,
            label = "Local",
            systemPrompt = "fallback",
            configJson = "not-json{",
        )
        val decoded = NodeConfigCodec.decode(src) as LiteRtConfig
        assertEquals("fallback", decoded.systemPrompt)
    }

    @Test
    fun `given every node type when defaultFor then yields the matching NodeConfig`() {
        CatalogNodeType.entries.forEach { type ->
            val config = NodeConfigCodec.defaultFor(type, title = "T")
            assertEquals(type, config.type)
            assertEquals("T", config.title)
        }
    }

    @Test
    fun `given config when apply then label and configJson are refreshed on the NodeModel`() {
        val src = node(NodeType.LITE_RT, "Old")
        val edited = LiteRtConfig(title = "New", modelId = "m", systemPrompt = "sp")
        val patched = NodeConfigCodec.apply(src, edited)
        assertEquals("New", patched.label)
        assertNotNull(patched.configJson)
        assertTrue(patched.configJson!!.contains("\"title\":\"New\""))
        assertEquals("sp", patched.systemPrompt)
    }

    @Test
    fun `given IntentRouter config when apply then the classifier prompt reaches systemPrompt`() {
        val patched = NodeConfigCodec.apply(
            node(NodeType.INTENT_ROUTER),
            IntentRouterConfig(title = "Router", classifierPrompt = "classify this"),
        )
        assertEquals("classify this", patched.systemPrompt)
    }

    @Test
    fun `given Decomposition config when apply then the planning prompt reaches systemPrompt`() {
        val patched = NodeConfigCodec.apply(
            node(NodeType.DECOMPOSITION),
            DecompositionConfig(title = "Plan", planningPrompt = "break it down"),
        )
        assertEquals("break it down", patched.systemPrompt)
    }

    @Test
    fun `given Evaluation config when apply then the criteria prompt reaches systemPrompt`() {
        val patched = NodeConfigCodec.apply(
            node(NodeType.EVALUATION),
            EvaluationConfig(title = "Judge", criteriaPrompt = "grade it"),
        )
        assertEquals("grade it", patched.systemPrompt)
    }

    @Test
    fun `given Summary config when apply then the custom prompt reaches systemPrompt`() {
        val patched = NodeConfigCodec.apply(
            node(NodeType.SUMMARY),
            SummaryConfig(title = "Sum", customPrompt = "condense it"),
        )
        assertEquals("condense it", patched.systemPrompt)
    }

    @Test
    fun `given an edited prompt when apply then it survives a decode round-trip`() {
        // The bug this pins was invisible precisely because `decode` reads the
        // prompt back out of `configJson`: the sheet reopened showing the edit
        // while the run kept using the prompt the node was created with. Both
        // halves must agree, so the round-trip is asserted on both surfaces.
        val patched = NodeConfigCodec.apply(
            node(NodeType.INTENT_ROUTER),
            IntentRouterConfig(title = "Router", classifierPrompt = "edited"),
        )
        val decoded = NodeConfigCodec.decode(patched) as IntentRouterConfig
        assertEquals("edited", decoded.classifierPrompt)
        assertEquals("edited", patched.systemPrompt)
    }

    @Test
    fun `given a blank prompt when apply then systemPrompt is null so the executor falls back`() {
        // Not `""`: `SystemNodeExecutor` and `SummaryNodeExecutor` resolve their
        // default with `node.systemPrompt ?: FALLBACK`, so an empty string would
        // run the node with no instructions instead of its default prompt.
        val router = NodeConfigCodec.apply(
            node(NodeType.INTENT_ROUTER),
            IntentRouterConfig(title = "Router", classifierPrompt = "   "),
        )
        assertNull(router.systemPrompt)

        // SUMMARY reaches this state legitimately — its prompt is optional, and
        // blank means "keep the built-in summarisation prompt".
        val summary = NodeConfigCodec.apply(
            node(NodeType.SUMMARY),
            SummaryConfig(title = "Sum", customPrompt = null),
        )
        assertNull(summary.systemPrompt)
    }

    @Test
    fun `given Pipeline config when encode-then-decode then target id preserved`() {
        val source = node(NodeType.PIPELINE, "Run sub")
        val config = PipelineConfig(title = "Run sub", targetPipelineId = "target-123")
        val applied = source.copy(configJson = NodeConfigCodec.encode(config))
        val decoded = NodeConfigCodec.decode(applied) as PipelineConfig
        assertEquals("Run sub", decoded.title)
        assertEquals("target-123", decoded.targetPipelineId)
    }

    @Test
    fun `given Pipeline config when apply then targetPipelineId is written onto the NodeModel`() {
        val src = node(NodeType.PIPELINE, "Run sub")
        val patched = NodeConfigCodec.apply(src, PipelineConfig(title = "Run sub", targetPipelineId = "target-123"))
        assertEquals("target-123", patched.targetPipelineId)
    }

    @Test
    fun `given Pipeline config with blank target when apply then targetPipelineId is null`() {
        val src = node(NodeType.PIPELINE, "Run sub").copy(targetPipelineId = "stale")
        val patched = NodeConfigCodec.apply(src, PipelineConfig(title = "Run sub", targetPipelineId = ""))
        assertNull(patched.targetPipelineId)
    }

    @Test
    fun `given pre-existing PIPELINE node when decode then derives target from the domain field`() {
        val src = node(NodeType.PIPELINE, "Run sub").copy(targetPipelineId = "legacy-target")
        val decoded = NodeConfigCodec.decode(src) as PipelineConfig
        assertEquals("legacy-target", decoded.targetPipelineId)
    }

    @Test
    fun `given Skill config when encode-then-decode then skill id and engine preserved`() {
        val config = SkillConfig(title = "Translate", skillId = "skill-7", engine = SkillEngine.CLOUD)
        val applied = node(NodeType.SKILL, "Translate").copy(configJson = NodeConfigCodec.encode(config))
        val decoded = NodeConfigCodec.decode(applied) as SkillConfig
        assertEquals("Translate", decoded.title)
        assertEquals("skill-7", decoded.skillId)
        assertEquals(SkillEngine.CLOUD, decoded.engine)
    }

    @Test
    fun `given Skill config with LITE_RT engine when apply then skillId set and cloudProvider null`() {
        val src = node(NodeType.SKILL, "Translate")
        val patched = NodeConfigCodec.apply(src, SkillConfig(title = "Translate", skillId = "skill-7"))
        assertEquals("skill-7", patched.skillId)
        assertNull(patched.cloudProvider)
    }

    @Test
    fun `given Skill config with CLOUD engine when apply then cloudProvider is the auto sentinel`() {
        val src = node(NodeType.SKILL, "Translate")
        val patched = NodeConfigCodec.apply(
            src,
            SkillConfig(title = "Translate", skillId = "skill-7", engine = SkillEngine.CLOUD),
        )
        assertEquals(DomainCloudProvider.AUTO_KEY, patched.cloudProvider)
    }

    @Test
    fun `given Skill config with blank skill when apply then skillId is null`() {
        val src = node(NodeType.SKILL, "Translate").copy(skillId = "stale")
        val patched = NodeConfigCodec.apply(src, SkillConfig(title = "Translate", skillId = ""))
        assertNull(patched.skillId)
    }

    @Test
    fun `given pre-existing SKILL node when decode then derives skill and engine from domain fields`() {
        val src = node(NodeType.SKILL, "Translate").copy(skillId = "legacy-skill", cloudProvider = "auto")
        val decoded = NodeConfigCodec.decode(src) as SkillConfig
        assertEquals("legacy-skill", decoded.skillId)
        assertEquals(SkillEngine.CLOUD, decoded.engine)
    }

    @Test
    fun `given a Skill node that always confirms when encode-then-decode then the switch survives`() {
        val config = SkillConfig(title = "Translate", skillId = "skill-7", alwaysConfirm = true)
        val applied = node(NodeType.SKILL, "Translate").copy(configJson = NodeConfigCodec.encode(config))

        assertTrue((NodeConfigCodec.decode(applied) as SkillConfig).alwaysConfirm)
    }

    @Test
    fun `given a Skill node that always confirms when apply then the flat field the executor reads is set`() {
        // `SkillNodeExecutor` reads the flat `alwaysConfirm`, not the envelope:
        // a switch that reached only the envelope would be shown and ignored.
        val src = node(NodeType.SKILL, "Translate")

        val on = NodeConfigCodec.apply(src, SkillConfig(title = "Translate", skillId = "s", alwaysConfirm = true))
        val off = NodeConfigCodec.apply(on, SkillConfig(title = "Translate", skillId = "s", alwaysConfirm = false))

        assertEquals(true, on.alwaysConfirm)
        assertNull("false is stored as null, as for TOOL", off.alwaysConfirm)
    }

    @Test
    fun `given a SKILL node with only the flat switch when decode then the sheet shows it`() {
        // An imported file may carry the flat field without an envelope, or an
        // envelope written before the switch existed.
        val flatOnly = node(NodeType.SKILL, "Translate").copy(skillId = "s", alwaysConfirm = true)
        val oldEnvelope = flatOnly.copy(
            configJson = """{"v":1,"type":"SKILL","title":"Translate","skillId":"s","engine":"LITE_RT"}""",
        )

        assertTrue((NodeConfigCodec.decode(flatOnly) as SkillConfig).alwaysConfirm)
        assertTrue((NodeConfigCodec.decode(oldEnvelope) as SkillConfig).alwaysConfirm)
    }

    @Test
    fun `given Tool engineProvider when apply then flat cloudProvider set and decodes back`() {
        val src = node(NodeType.TOOL, "Tool")
        val config = ToolConfig(title = "Tool", toolId = "fs.write", engineProvider = CloudProvider.GOOGLE)

        val applied = NodeConfigCodec.apply(src, config)

        assertEquals("google", applied.cloudProvider)
        assertEquals(CloudProvider.GOOGLE, (NodeConfigCodec.decode(applied) as ToolConfig).engineProvider)
    }

    @Test
    fun `given structured node defaulting to on-device when apply then cloudProvider cleared`() {
        // A node that previously ran on a cloud provider; selecting on-device
        // (engineProvider = null) must clear the stale flat provider so the
        // engine runs the gate locally again.
        val src = node(NodeType.DECOMPOSITION, "Plan").copy(cloudProvider = "openai")

        val applied = NodeConfigCodec.apply(src, DecompositionConfig(title = "Plan", engineProvider = null))

        assertNull(applied.cloudProvider)
        assertNull((NodeConfigCodec.decode(applied) as DecompositionConfig).engineProvider)
    }

    @Test
    fun `given Decomposition engineProvider when encode-then-decode then preserved via rich payload`() {
        val src = node(NodeType.DECOMPOSITION, "Plan")
        val config =
            DecompositionConfig(title = "Plan", planningPrompt = "split it", engineProvider = CloudProvider.COMPATIBLE)

        val applied = src.copy(configJson = NodeConfigCodec.encode(config))
        val decoded = NodeConfigCodec.decode(applied) as DecompositionConfig

        assertEquals(CloudProvider.COMPATIBLE, decoded.engineProvider)
    }
}
