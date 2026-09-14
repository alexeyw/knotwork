package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.BrowserEditorConstantsGenerator.GenerationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserEditorConstantsGenerator].
 *
 * These exercise every parser, emitter, the marker injection round-trip,
 * idempotency, drift detection, and the metadata-vs-source cross-checks.
 * Run with `./gradlew -p buildSrc test`.
 */
class BrowserEditorConstantsGeneratorTest {

    // ---- Fixtures mirroring the shape of the real Android sources ---- //

    private val nodeTypeSource = """
        package app.knotwork.android.domain.models

        /** doc with a reference to [NodeType.OUTPUT] that must be ignored */
        enum class NodeType {
            LITE_RT,
            CLOUD,
            TOOL,
            IF_CONDITION,
            INTENT_ROUTER,
            DECOMPOSITION,
            QUEUE_PROCESSOR,
            EVALUATION,
            SUMMARY,
            CLARIFICATION,
            PIPELINE,
            SKILL,
            INPUT,
            OUTPUT,
        }
    """.trimIndent()

    private val promptModuleSource = """
        @Module
        abstract class PromptTemplateModule {
            @Binds @IntoSet
            abstract fun bindDateVariableProvider(impl: DateVariableProvider): PromptVariableProvider
            @Binds @IntoSet
            abstract fun bindTimeVariableProvider(impl: TimeVariableProvider): PromptVariableProvider
            @Binds @IntoSet
            abstract fun bindToolsVariableProvider(impl: ToolsVariableProvider): PromptVariableProvider
        }
    """.trimIndent()

    private val toolsModuleSource = """
        @Module
        abstract class LocalToolsModule {
            @Binds @IntoMap @StringKey(ScheduleTaskExecutor.TOOL_NAME)
            abstract fun bindScheduleTaskExecutor(executor: ScheduleTaskExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(DelegateTaskExecutor.TOOL_NAME)
            abstract fun bindDelegateTaskExecutor(executor: DelegateTaskExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(SearchTool.TOOL_NAME)
            abstract fun bindSearchToolExecutor(executor: SearchToolExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(ReadFileExecutor.TOOL_NAME)
            abstract fun bindReadFileExecutor(executor: ReadFileExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(ListFilesExecutor.TOOL_NAME)
            abstract fun bindListFilesExecutor(executor: ListFilesExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(FindFilesExecutor.TOOL_NAME)
            abstract fun bindFindFilesExecutor(executor: FindFilesExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(WriteFileExecutor.TOOL_NAME)
            abstract fun bindWriteFileExecutor(executor: WriteFileExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(EditFileExecutor.TOOL_NAME)
            abstract fun bindEditFileExecutor(executor: EditFileExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(AppendFileExecutor.TOOL_NAME)
            abstract fun bindAppendFileExecutor(executor: AppendFileExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(DeleteFileExecutor.TOOL_NAME)
            abstract fun bindDeleteFileExecutor(executor: DeleteFileExecutor): LocalToolExecutor
            @Binds @IntoMap @StringKey(HttpRequestExecutor.TOOL_NAME)
            abstract fun bindHttpRequestExecutor(executor: HttpRequestExecutor): LocalToolExecutor
        }
    """.trimIndent()

    private val defaultPromptsSource = """
        object DefaultPrompts {
            const val SYSTEM_PROMPT_PREFIX = "You are a helpful AI assistant running on an Android device."

            /** kdoc */
            const val INTENT_ROUTER_PROMPT = "You are an Intent Router. " +
                "Output one of:\n" +
                "- Simple (if it's a greeting)"

            const val DECOMPOSITION_PROMPT = "Decompose."
            const val EVALUATION_PROMPT = "Evaluate."
            const val SUMMARY_PROMPT = "Summarize."
            const val OUTPUT_FORMAT_PROMPT = "Format."
            const val CLARIFICATION_PROMPT = "Clarify with \"quotes\" and a brace }."

            object LiteRt {
                const val SYSTEM_FALLBACK = "You are a helpful AI assistant."
            }
        }
    """.trimIndent()

    private val classSources = mapOf(
        "DateVariableProvider" to providerSource("DATE"),
        "TimeVariableProvider" to providerSource("TIME"),
        "ToolsVariableProvider" to providerSource("TOOLS"),
        "ScheduleTaskExecutor" to toolSource("schedule_task"),
        "DelegateTaskExecutor" to toolSource("delegate_task"),
        "SearchTool" to toolSource("search_tool"),
        "ReadFileExecutor" to toolSource("read_file"),
        "ListFilesExecutor" to toolSource("list_files"),
        "FindFilesExecutor" to toolSource("find_files"),
        "WriteFileExecutor" to toolSource("write_file"),
        "EditFileExecutor" to toolSource("edit_file"),
        "AppendFileExecutor" to toolSource("append_file"),
        "DeleteFileExecutor" to toolSource("delete_file"),
        "HttpRequestExecutor" to toolSource("http_request"),
    )

    private fun providerSource(key: String) = """
        class Provider : PromptVariableProvider {
            override fun key(): String = KEY
            companion object { const val KEY = "$key" }
        }
    """.trimIndent()

    private fun toolSource(name: String) = """
        class Tool {
            companion object { const val TOOL_NAME = "$name" }
        }
    """.trimIndent()

    // ---- Parsers ---- //

    @Test
    fun `parseNodeTypeNames returns constants in declaration order ignoring kdoc`() {
        val names = BrowserEditorConstantsGenerator.parseNodeTypeNames(nodeTypeSource)
        assertEquals(
            listOf(
                "LITE_RT", "CLOUD", "TOOL", "IF_CONDITION", "INTENT_ROUTER", "DECOMPOSITION",
                "QUEUE_PROCESSOR", "EVALUATION", "SUMMARY", "CLARIFICATION", "PIPELINE", "SKILL", "INPUT", "OUTPUT",
            ),
            names,
        )
    }

    @Test(expected = GenerationException::class)
    fun `parseNodeTypeNames throws when enum is absent`() {
        BrowserEditorConstantsGenerator.parseNodeTypeNames("package x")
    }

    @Test
    fun `parseBoundProviderClassNames returns binds in order`() {
        assertEquals(
            listOf("DateVariableProvider", "TimeVariableProvider", "ToolsVariableProvider"),
            BrowserEditorConstantsGenerator.parseBoundProviderClassNames(promptModuleSource),
        )
    }

    @Test
    fun `parseKeyConst extracts the KEY literal`() {
        assertEquals("DATE", BrowserEditorConstantsGenerator.parseKeyConst(providerSource("DATE")))
    }

    @Test
    fun `parseBoundToolClassNames returns referenced classes in order`() {
        assertEquals(
            listOf(
                "ScheduleTaskExecutor",
                "DelegateTaskExecutor",
                "SearchTool",
                "ReadFileExecutor",
                "ListFilesExecutor",
                "FindFilesExecutor",
                "WriteFileExecutor",
                "EditFileExecutor",
                "AppendFileExecutor",
                "DeleteFileExecutor",
                "HttpRequestExecutor",
            ),
            BrowserEditorConstantsGenerator.parseBoundToolClassNames(toolsModuleSource),
        )
    }

    @Test
    fun `parseToolNameConst extracts the TOOL_NAME literal`() {
        assertEquals("search_tool", BrowserEditorConstantsGenerator.parseToolNameConst(toolSource("search_tool")))
    }

    @Test
    fun `parseStringConst evaluates concatenation and escapes`() {
        val value = BrowserEditorConstantsGenerator.parseStringConst(defaultPromptsSource, "INTENT_ROUTER_PROMPT")
        assertEquals("You are an Intent Router. Output one of:\n- Simple (if it's a greeting)", value)
    }

    @Test
    fun `parseStringConst handles embedded quotes and braces`() {
        val value = BrowserEditorConstantsGenerator.parseStringConst(defaultPromptsSource, "CLARIFICATION_PROMPT")
        assertEquals("Clarify with \"quotes\" and a brace }.", value)
    }

    @Test
    fun `parseStringConst stops at the next declaration boundary`() {
        // SYSTEM_PROMPT_PREFIX must not absorb the following INTENT_ROUTER_PROMPT literals.
        val value = BrowserEditorConstantsGenerator.parseStringConst(defaultPromptsSource, "SYSTEM_PROMPT_PREFIX")
        assertEquals("You are a helpful AI assistant running on an Android device.", value)
    }

    @Test(expected = GenerationException::class)
    fun `parseStringConst throws for unknown constant`() {
        BrowserEditorConstantsGenerator.parseStringConst(defaultPromptsSource, "NOPE")
    }

    // ---- String helpers ---- //

    @Test
    fun `unescapeKotlin handles standard escapes and unicode`() {
        assertEquals(
            "a\nb\tc\"d\\e\$é",
            BrowserEditorConstantsGenerator.unescapeKotlin("a\\nb\\tc\\\"d\\\\e\\\$\\u00e9"),
        )
    }

    @Test
    fun `jsonString escapes control characters and keeps unicode`() {
        assertEquals("\"a\\nb\\\"c\\\\d\"", BrowserEditorConstantsGenerator.jsonString("a\nb\"c\\d"))
        assertEquals("\"🧠\"", BrowserEditorConstantsGenerator.jsonString("🧠"))
    }

    // ---- Emitters & cross-checks ---- //

    @Test
    fun `emitNodeTypes lists palette order and includes all enum ids`() {
        val enumNames = BrowserEditorConstantsGenerator.parseNodeTypeNames(nodeTypeSource)
        val js = BrowserEditorConstantsGenerator.emitNodeTypes(enumNames)
        assertTrue(js.contains("const NODE_TYPES = ["))
        assertTrue(js.contains("{ id: \"INPUT\", label: \"Input\", color: \"#607D8B\", icon: \"▶\", inputs: 0, outputs: 1 },"))
        // INPUT must appear before LITE_RT (palette order, not enum order).
        assertTrue(js.indexOf("\"INPUT\"") < js.indexOf("\"LITE_RT\""))
    }

    @Test(expected = GenerationException::class)
    fun `emitNodeTypes throws when an enum id has no metadata`() {
        BrowserEditorConstantsGenerator.emitNodeTypes(listOf("INPUT", "BRAND_NEW_TYPE"))
    }

    @Test
    fun `emitPromptVariables joins keys`() {
        assertEquals(
            "    const PROMPT_VARIABLES = [\"DATE\", \"TIME\", \"TOOLS\"];",
            BrowserEditorConstantsGenerator.emitPromptVariables(listOf("DATE", "TIME", "TOOLS")),
        )
    }

    @Test
    fun `emitAvailableTools emits display order`() {
        val js = BrowserEditorConstantsGenerator.emitAvailableTools(
            listOf(
                "schedule_task", "search_tool", "delegate_task",
                "read_file", "list_files", "find_files",
                "write_file", "edit_file", "append_file", "delete_file", "http_request",
            ),
        )
        // Display order from TOOL_META: search, delegate, schedule, then the read
        // workspace tools, then the mutating ones, then the outbound HTTP tool.
        assertTrue(js.indexOf("search_tool") < js.indexOf("delegate_task"))
        assertTrue(js.indexOf("delegate_task") < js.indexOf("schedule_task"))
        assertTrue(js.indexOf("schedule_task") < js.indexOf("read_file"))
        assertTrue(js.indexOf("read_file") < js.indexOf("list_files"))
        assertTrue(js.indexOf("list_files") < js.indexOf("find_files"))
        assertTrue(js.indexOf("find_files") < js.indexOf("write_file"))
        assertTrue(js.indexOf("write_file") < js.indexOf("edit_file"))
        assertTrue(js.indexOf("edit_file") < js.indexOf("append_file"))
        assertTrue(js.indexOf("append_file") < js.indexOf("delete_file"))
        assertTrue(js.indexOf("delete_file") < js.indexOf("http_request"))
    }

    @Test(expected = GenerationException::class)
    fun `emitAvailableTools throws on unknown tool id`() {
        BrowserEditorConstantsGenerator.emitAvailableTools(listOf("search_tool", "mystery_tool"))
    }

    @Test
    fun `emitDefaultPrompts references prefix and json-encodes prompts`() {
        val js = BrowserEditorConstantsGenerator.emitDefaultPrompts(defaultPromptsSource)
        assertTrue(js.contains("const SYSTEM_PROMPT_PREFIX = \"You are a helpful AI assistant running on an Android device.\";"))
        assertTrue(js.contains("LITE_RT: SYSTEM_PROMPT_PREFIX,"))
        assertTrue(js.contains("CLOUD: SYSTEM_PROMPT_PREFIX,"))
        assertTrue(js.contains("INTENT_ROUTER: \"You are an Intent Router. Output one of:\\n- Simple (if it's a greeting)\","))
    }

    // ---- Marker injection ---- //

    private fun wrap(block: String, body: String) =
        BrowserEditorConstantsGenerator.openMarker(block) + "\n" + body + "\n" +
            BrowserEditorConstantsGenerator.closeMarker(block)

    @Test
    fun `injectBlock replaces content and preserves markers and surroundings`() {
        val html = "head\n" + wrap("NODE_TYPES", "    const NODE_TYPES = [];") + "\ntail"
        val out = BrowserEditorConstantsGenerator.injectBlock(html, "NODE_TYPES", "    const NODE_TYPES = [1];")
        assertTrue(out.startsWith("head\n"))
        assertTrue(out.endsWith("\ntail"))
        assertTrue(out.contains("const NODE_TYPES = [1];"))
        assertFalse(out.contains("const NODE_TYPES = [];"))
        assertTrue(out.contains(BrowserEditorConstantsGenerator.openMarker("NODE_TYPES")))
        assertTrue(out.contains(BrowserEditorConstantsGenerator.closeMarker("NODE_TYPES")))
    }

    @Test
    fun `extractBlock round-trips injectBlock`() {
        val html = "x\n" + wrap("AVAILABLE_TOOLS", "old") + "\ny"
        val injected = BrowserEditorConstantsGenerator.injectBlock(html, "AVAILABLE_TOOLS", "    new content")
        assertEquals("    new content", BrowserEditorConstantsGenerator.extractBlock(injected, "AVAILABLE_TOOLS"))
    }

    @Test
    fun `extractBlock returns null when block missing`() {
        assertNull(BrowserEditorConstantsGenerator.extractBlock("nothing here", "NODE_TYPES"))
    }

    @Test(expected = GenerationException::class)
    fun `injectBlock throws when marker missing`() {
        BrowserEditorConstantsGenerator.injectBlock("no markers", "NODE_TYPES", "x")
    }

    // ---- End-to-end render / drift ---- //

    private fun skeletonHtml(): String = buildString {
        append("<script>\n")
        append(wrap("NODE_TYPES", "    const NODE_TYPES = [];")).append("\n")
        append(wrap("PROMPT_VARIABLES", "    const PROMPT_VARIABLES = [];")).append("\n")
        append(wrap("AVAILABLE_TOOLS", "    const AVAILABLE_TOOLS = [];")).append("\n")
        append(wrap("DEFAULT_PROMPTS", "    const SYSTEM_PROMPT_PREFIX = \"\";")).append("\n")
        append(wrap("PIPELINE_PRESETS", "    const BUILTIN_PIPELINE_PRESETS = [];")).append("\n")
        append(wrap("PROMPT_TEMPLATES", "    const BUILTIN_PROMPT_TEMPLATES = [];")).append("\n")
        append("</script>\n")
    }

    private fun render(html: String) = BrowserEditorConstantsGenerator.render(
        html = html,
        nodeTypeSource = nodeTypeSource,
        defaultPromptsSource = defaultPromptsSource,
        promptTemplateModuleSource = promptModuleSource,
        localToolsModuleSource = toolsModuleSource,
        classSources = classSources,
        presets = presetSources,
    )

    private fun drift(html: String) = BrowserEditorConstantsGenerator.drift(
        html = html,
        nodeTypeSource = nodeTypeSource,
        defaultPromptsSource = defaultPromptsSource,
        promptTemplateModuleSource = promptModuleSource,
        localToolsModuleSource = toolsModuleSource,
        classSources = classSources,
        presets = presetSources,
    )

    @Test
    fun `render is idempotent`() {
        val once = render(skeletonHtml())
        val twice = render(once)
        assertEquals(once, twice)
    }

    @Test
    fun `render populates every block`() {
        val out = render(skeletonHtml())
        assertTrue(out.contains("{ id: \"INPUT\""))
        assertTrue(out.contains("const PROMPT_VARIABLES = [\"DATE\", \"TIME\", \"TOOLS\"];"))
        assertTrue(out.contains("{ id: \"search_tool\", label: \"Search (Wikipedia)\" },"))
        assertTrue(out.contains("INTENT_ROUTER: \"You are an Intent Router."))
    }

    @Test
    fun `drift reports all blocks for an unpopulated skeleton then none after render`() {
        assertEquals(BrowserEditorConstantsGenerator.BLOCKS.toSet(), drift(skeletonHtml()).toSet())
        assertTrue(drift(render(skeletonHtml())).isEmpty())
    }

    @Test
    fun `drift pinpoints only the stale block`() {
        val rendered = render(skeletonHtml())
        // Corrupt just the PROMPT_VARIABLES block.
        val tampered = BrowserEditorConstantsGenerator.injectBlock(
            rendered, "PROMPT_VARIABLES", "    const PROMPT_VARIABLES = [\"STALE\"];",
        )
        assertEquals(listOf("PROMPT_VARIABLES"), drift(tampered))
    }

    @Test(expected = GenerationException::class)
    fun `render throws when a bound provider source is missing`() {
        BrowserEditorConstantsGenerator.render(
            html = skeletonHtml(),
            nodeTypeSource = nodeTypeSource,
            defaultPromptsSource = defaultPromptsSource,
            promptTemplateModuleSource = promptModuleSource,
            localToolsModuleSource = toolsModuleSource,
            classSources = emptyMap(),
            presets = presetSources,
        )
    }

    // ---- Bundled presets and prompt templates ---- //

    private val catalogSource = """
        object BundledPresetCatalog {
            val DISPLAY_ORDER: List<String> = listOf(
                "styled_translation",
                "local_only_qa",
            )
        }
    """.trimIndent()

    private val translationPreset = """
        {
          "schemaVersion": 1,
          "id": "styled_translation",
          "name": "Styled Translation",
          "tags": [
            "translation"
          ],
          "nodes": []
        }

    """.trimIndent() + "\n"

    private val qaPreset = "{\n  \"id\": \"local_only_qa\",\n  \"name\": \"Q&A </script>\"\n}\n"
    private val subtaskB = "{\n  \"id\": \"subtask_b\",\n  \"internal\": true\n}\n"
    private val subtaskA = "{\n  \"id\": \"subtask_a\",\n  \"internal\": true\n}\n"

    private val presetSources = BrowserEditorConstantsGenerator.BundledPresetSources(
        pipelinePresets = mapOf(
            "subtask_b.json" to subtaskB,
            "local_only_qa.json" to qaPreset,
            "styled_translation.json" to translationPreset,
            "subtask_a.json" to subtaskA,
        ),
        presetCatalog = catalogSource,
        promptTemplates = mapOf(
            "out.json" to template("output_plain", "OUTPUT", "Plain", "Say \\\"hi\\\".\\n</b>"),
            "lite_b.json" to template("litert_b", "LITE_RT", "B", "Second"),
            "lite_a.json" to template("litert_a", "LITE_RT", "A", "First"),
        ),
    )

    private fun template(id: String, nodeType: String, name: String, promptJson: String): String =
        "{\n  \"schemaVersion\": 1,\n  \"id\": \"$id\",\n  \"name\": \"$name\",\n" +
            "  \"description\": \"About $name\",\n  \"nodeType\": \"$nodeType\",\n" +
            "  \"systemPrompt\": \"$promptJson\",\n  \"tags\": [\"x\"]\n}\n"

    @Test
    fun `given presets when emitted then ranked ids come first and internal ones follow alphabetically`() {
        val block = BrowserEditorConstantsGenerator.emitPipelinePresets(
            presetSources.pipelinePresets,
            presetSources.presetCatalog,
        )

        val ids = Regex(""""id": "(\w+)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(listOf("styled_translation", "local_only_qa", "subtask_a", "subtask_b"), ids)
    }

    @Test
    fun `given a canonical document when emitted then it is re-indented to four spaces under an eight-space base`() {
        val block = BrowserEditorConstantsGenerator.emitPipelinePresets(
            mapOf("styled_translation.json" to translationPreset, "local_only_qa.json" to qaPreset),
            catalogSource,
        )

        assertTrue(block.startsWith("    const BUILTIN_PIPELINE_PRESETS = [\n        {\n            \"schemaVersion\": 1,"))
        assertTrue(block.contains("            \"tags\": [\n                \"translation\"\n            ],"))
        assertTrue(block.contains("        },\n        {"))
        assertTrue(block.endsWith("        }\n    ];"))
    }

    @Test
    fun `given text that would close the script element when emitted then it is broken up`() {
        val presets = BrowserEditorConstantsGenerator.emitPipelinePresets(presetSources.pipelinePresets, catalogSource)
        val templates = BrowserEditorConstantsGenerator.emitPromptTemplates(presetSources.promptTemplates)

        assertFalse(presets.contains("</"))
        assertTrue(presets.contains("Q&A <\\/script>"))
        assertFalse(templates.contains("</"))
    }

    @Test(expected = GenerationException::class)
    fun `given a public preset missing from DISPLAY_ORDER when emitted then generation fails`() {
        BrowserEditorConstantsGenerator.emitPipelinePresets(
            presetSources.pipelinePresets + ("orphan.json" to "{\n  \"id\": \"orphan\"\n}\n"),
            catalogSource,
        )
    }

    @Test(expected = GenerationException::class)
    fun `given DISPLAY_ORDER naming a preset with no file when emitted then generation fails`() {
        BrowserEditorConstantsGenerator.emitPipelinePresets(mapOf("local_only_qa.json" to qaPreset), catalogSource)
    }

    @Test(expected = GenerationException::class)
    fun `given a preset that is not canonical two-space JSON when emitted then generation fails`() {
        BrowserEditorConstantsGenerator.emitPipelinePresets(
            mapOf(
                "styled_translation.json" to "{\n   \"id\": \"styled_translation\"\n}\n",
                "local_only_qa.json" to qaPreset,
            ),
            catalogSource,
        )
    }

    @Test
    fun `given templates when emitted then they are grouped by palette order and decoded`() {
        val block = BrowserEditorConstantsGenerator.emitPromptTemplates(presetSources.promptTemplates)

        val ids = Regex("""id: "(\w+)"""").findAll(block).map { it.groupValues[1] }.toList()
        // OUTPUT precedes LITE_RT in the palette; within a group, by id.
        assertEquals(listOf("output_plain", "litert_a", "litert_b"), ids)
        assertTrue(block.contains("content: \"Say \\\"hi\\\".\\n<\\/b>\","))
        assertTrue(block.contains("nodeType: \"LITE_RT\","))
    }

    @Test(expected = GenerationException::class)
    fun `given a template for a node type the palette lacks when emitted then generation fails`() {
        BrowserEditorConstantsGenerator.emitPromptTemplates(
            mapOf("x.json" to template("x", "NOT_A_TYPE", "X", "p")),
        )
    }

    @Test
    fun `given DISPLAY_ORDER source when parsed then ids come back in order`() {
        assertEquals(
            listOf("styled_translation", "local_only_qa"),
            BrowserEditorConstantsGenerator.parseDisplayOrder(catalogSource),
        )
    }
}
