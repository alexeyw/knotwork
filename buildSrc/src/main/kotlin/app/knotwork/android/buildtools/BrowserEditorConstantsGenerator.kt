package app.knotwork.android.buildtools

/**
 * Derives the JavaScript constant blocks that the browser pipeline editor
 * (`pipeline-editor.html`) mirrors from the Android domain layer, and injects
 * them between dedicated `AUTO-GEN` markers.
 *
 * The editor is a standalone single-file tool that deliberately duplicates a
 * slice of the app's data so it can run with no build step. Before this
 * automation that duplication was kept in sync purely by review, and it
 * drifted. This generator removes the
 * human from that loop for the drift-prone blocks:
 *
 *  - **`NODE_TYPES`** — the set and ordering guarantee comes from
 *    [parseNodeTypeNames] reading `domain/models/NodeType.kt`; the editor-only
 *    presentation metadata (label / colour / icon / port counts / palette
 *    order) has no Kotlin source of truth and therefore lives in
 *    [NODE_TYPE_META]. The two are cross-checked: adding or removing a
 *    `NodeType` without updating [NODE_TYPE_META] fails generation.
 *  - **`PROMPT_VARIABLES`** — fully derived: the active provider set and order
 *    come from the `@Binds @IntoSet` declarations in `di/PromptTemplateModule.kt`,
 *    each resolved to its `KEY` constant in the `*VariableProvider.kt` files under `data/prompt/`.
 *  - **`AVAILABLE_TOOLS`** — ids come from the `TOOL_NAME` constants referenced
 *    by `di/LocalToolsModule.kt`; the human-facing labels have no Kotlin source
 *    of truth and live in [TOOL_META], again cross-checked against the ids.
 *  - **`DEFAULT_SYSTEM_PROMPTS`** (and `SYSTEM_PROMPT_PREFIX`) — the prompt
 *    texts are evaluated straight out of `domain/constants/DefaultPrompts.kt`.
 *  - **`BUILTIN_PIPELINE_PRESETS`** — every bundled preset document under
 *    `assets/presets/pipelines/`, re-indented verbatim, in the app's display
 *    order. This block was the largest hand copy in the file and it drifted in
 *    exactly the way a copy does: a change to six presets' prompts reached the
 *    app and never reached the editor, which went on shipping the wording that
 *    change had been made to remove.
 *  - **`BUILTIN_PROMPT_TEMPLATES`** — every bundled prompt template under
 *    `assets/presets/prompts/`, grouped by node type in palette order.
 *
 * Everything outside the markers (drawflow wiring, popup structure, form
 * rendering, the rest of the editor's UI logic) is intentionally left untouched
 * and remains hand-maintained.
 *
 * The entry points are [render] (pure transform used by both Gradle tasks) and
 * [drift] (per-block comparison used by the verify task to report which blocks
 * are out of date). Every helper is a pure string transform to keep the module
 * unit-testable.
 */
object BrowserEditorConstantsGenerator {

    /** Thrown when the sources cannot be parsed or contradict [NODE_TYPE_META] / [TOOL_META]. */
    class GenerationException(message: String) : RuntimeException(message)

    /**
     * Editor-only presentation metadata for one [app.knotwork.android.domain.models.NodeType],
     * in the order the node should appear in the editor palette.
     *
     * @property id Must equal a `NodeType` enum constant name.
     * @property label Human-facing palette label.
     * @property color Palette accent colour (hex).
     * @property icon Single-glyph palette icon.
     * @property inputs Number of input ports the drawflow node exposes.
     * @property outputs Number of output ports the drawflow node exposes.
     */
    data class NodeTypeMeta(
        val id: String,
        val label: String,
        val color: String,
        val icon: String,
        val inputs: Int,
        val outputs: Int,
    )

    /**
     * Palette presentation table for [emitNodeTypes]. Order here is the editor
     * palette order (INPUT / OUTPUT first), which deliberately differs from the
     * `NodeType` enum declaration order. The *set* of ids must equal the enum.
     */
    val NODE_TYPE_META: List<NodeTypeMeta> = listOf(
        NodeTypeMeta("INPUT", "Input", "#607D8B", "▶", 0, 1),
        NodeTypeMeta("OUTPUT", "Output", "#F44336", "⏹", 1, 0),
        NodeTypeMeta("LITE_RT", "LiteRT", "#4CAF50", "🧠", 1, 1),
        NodeTypeMeta("CLOUD", "Cloud", "#2196F3", "☁", 1, 1),
        NodeTypeMeta("TOOL", "Tool", "#FF9800", "🛠", 1, 1),
        NodeTypeMeta("IF_CONDITION", "If Condition", "#FFC107", "❓", 1, 2),
        NodeTypeMeta("INTENT_ROUTER", "Intent Router", "#E91E63", "🧭", 1, 1),
        NodeTypeMeta("DECOMPOSITION", "Decomposition", "#3F51B5", "🧩", 1, 1),
        NodeTypeMeta("QUEUE_PROCESSOR", "Queue Processor", "#795548", "🔁", 1, 2),
        NodeTypeMeta("EVALUATION", "Evaluation", "#009688", "✅", 1, 3),
        NodeTypeMeta("SUMMARY", "Summary", "#8BC34A", "📝", 1, 1),
        NodeTypeMeta("CLARIFICATION", "Clarification", "#9C27B0", "💬", 1, 1),
        NodeTypeMeta("PIPELINE", "Pipeline", "#424DB2", "📦", 1, 1),
        NodeTypeMeta("SKILL", "Skill", "#FFB300", "⭐", 1, 1),
    )

    /**
     * Human-facing labels for the local tools, keyed by tool id (`TOOL_NAME`),
     * in editor display order. The *set* of ids must equal the tool names
     * discovered from `di/LocalToolsModule.kt`.
     */
    val TOOL_META: List<Pair<String, String>> = listOf(
        "search_tool" to "Search (Wikipedia)",
        "delegate_task" to "Delegate Task",
        "schedule_task" to "Schedule Task",
        "read_file" to "Read File",
        "list_files" to "List Files",
        "find_files" to "Find Files",
        "write_file" to "Write File",
        "edit_file" to "Edit File",
        "append_file" to "Append File",
        "delete_file" to "Delete File",
        "http_request" to "HTTP Request",
    )

    /**
     * Mapping from the JS `DEFAULT_SYSTEM_PROMPTS` keys to the `DefaultPrompts`
     * constant that supplies each one, mirroring
     * `DefaultPrompts.getDefaultPromptForNodeType`. `LITE_RT` / `CLOUD` reference
     * the shared `SYSTEM_PROMPT_PREFIX` and are emitted by reference, so they are
     * not listed here.
     */
    private val PROMPT_CONST_BY_JS_KEY: List<Pair<String, String>> = listOf(
        "INTENT_ROUTER" to "INTENT_ROUTER_PROMPT",
        "DECOMPOSITION" to "DECOMPOSITION_PROMPT",
        "EVALUATION" to "EVALUATION_PROMPT",
        "SUMMARY" to "SUMMARY_PROMPT",
        "OUTPUT" to "OUTPUT_FORMAT_PROMPT",
        "CLARIFICATION" to "CLARIFICATION_PROMPT",
    )

    /** Identifiers of the auto-generated blocks, used in the `AUTO-GEN` markers. */
    const val BLOCK_NODE_TYPES = "NODE_TYPES"
    const val BLOCK_PROMPT_VARIABLES = "PROMPT_VARIABLES"
    const val BLOCK_AVAILABLE_TOOLS = "AVAILABLE_TOOLS"
    const val BLOCK_DEFAULT_PROMPTS = "DEFAULT_PROMPTS"
    const val BLOCK_PIPELINE_PRESETS = "PIPELINE_PRESETS"
    const val BLOCK_PROMPT_TEMPLATES = "PROMPT_TEMPLATES"

    /** Every block this generator owns, in a stable reporting order. */
    val BLOCKS: List<String> = listOf(
        BLOCK_NODE_TYPES,
        BLOCK_PROMPT_VARIABLES,
        BLOCK_AVAILABLE_TOOLS,
        BLOCK_DEFAULT_PROMPTS,
        BLOCK_PIPELINE_PRESETS,
        BLOCK_PROMPT_TEMPLATES,
    )

    /**
     * The bundled documents the preset blocks are generated from.
     *
     * @property pipelinePresets Content of every JSON file under `assets/presets/pipelines/`, keyed by file name.
     * @property presetCatalog Content of `domain/constants/BundledPresetCatalog.kt`, which owns the
     *   display order.
     * @property promptTemplates Content of every JSON file under `assets/presets/prompts/`, keyed by file name.
     */
    data class BundledPresetSources(
        val pipelinePresets: Map<String, String>,
        val presetCatalog: String,
        val promptTemplates: Map<String, String>,
    )

    // ------------------------------------------------------------------ //
    //  Public entry points
    // ------------------------------------------------------------------ //

    /**
     * Returns [html] with every auto-generated block regenerated from the
     * supplied Android sources. Pure and idempotent: `render(render(x)) == render(x)`.
     *
     * @param html Current `pipeline-editor.html` content (must already contain
     * the `AUTO-GEN` markers for every entry in [BLOCKS]).
     * @param nodeTypeSource Content of `domain/models/NodeType.kt`.
     * @param defaultPromptsSource Content of `domain/constants/DefaultPrompts.kt`.
     * @param promptTemplateModuleSource Content of `di/PromptTemplateModule.kt`.
     * @param localToolsModuleSource Content of `di/LocalToolsModule.kt`.
     * @param classSources Map from class simple-name to its `.kt` source, covering
     * every bound `PromptVariableProvider` and every class referenced as
     * `<Class>.TOOL_NAME` in the tools module.
     * @param presets The bundled preset and template documents.
     * @return The rewritten HTML.
     * @throws GenerationException on parse failure or metadata/source mismatch.
     */
    fun render(
        html: String,
        nodeTypeSource: String,
        defaultPromptsSource: String,
        promptTemplateModuleSource: String,
        localToolsModuleSource: String,
        classSources: Map<String, String>,
        presets: BundledPresetSources,
    ): String {
        val blocks = generateBlocks(
            nodeTypeSource = nodeTypeSource,
            defaultPromptsSource = defaultPromptsSource,
            promptTemplateModuleSource = promptTemplateModuleSource,
            localToolsModuleSource = localToolsModuleSource,
            classSources = classSources,
            presets = presets,
        )
        var result = html
        for ((name, content) in blocks) {
            result = injectBlock(result, name, content)
        }
        return result
    }

    /**
     * Returns the names of the blocks whose freshly-generated content differs
     * from what is currently committed in [html]. Empty list ⇒ no drift.
     *
     * Drives `verifyBrowserEditorConstants`: comparing per block lets the task
     * tell the user exactly which constants are stale.
     */
    fun drift(
        html: String,
        nodeTypeSource: String,
        defaultPromptsSource: String,
        promptTemplateModuleSource: String,
        localToolsModuleSource: String,
        classSources: Map<String, String>,
        presets: BundledPresetSources,
    ): List<String> {
        val blocks = generateBlocks(
            nodeTypeSource = nodeTypeSource,
            defaultPromptsSource = defaultPromptsSource,
            promptTemplateModuleSource = promptTemplateModuleSource,
            localToolsModuleSource = localToolsModuleSource,
            classSources = classSources,
            presets = presets,
        )
        return blocks.filter { (name, content) -> extractBlock(html, name) != content }
            .map { it.first }
    }

    /** Builds every block's generated content, keyed by block name (no HTML mutation). */
    private fun generateBlocks(
        nodeTypeSource: String,
        defaultPromptsSource: String,
        promptTemplateModuleSource: String,
        localToolsModuleSource: String,
        classSources: Map<String, String>,
        presets: BundledPresetSources,
    ): List<Pair<String, String>> {
        val enumNames = parseNodeTypeNames(nodeTypeSource)
        val providerKeys = parseBoundProviderClassNames(promptTemplateModuleSource).map { className ->
            val src = classSources[className]
                ?: throw GenerationException("Missing source for prompt provider class '$className'")
            parseKeyConst(src)
        }
        val toolIds = parseBoundToolClassNames(localToolsModuleSource).map { className ->
            val src = classSources[className]
                ?: throw GenerationException("Missing source for tool class '$className'")
            parseToolNameConst(src)
        }
        return listOf(
            BLOCK_NODE_TYPES to emitNodeTypes(enumNames),
            BLOCK_PROMPT_VARIABLES to emitPromptVariables(providerKeys),
            BLOCK_AVAILABLE_TOOLS to emitAvailableTools(toolIds),
            BLOCK_DEFAULT_PROMPTS to emitDefaultPrompts(defaultPromptsSource),
            BLOCK_PIPELINE_PRESETS to emitPipelinePresets(presets.pipelinePresets, presets.presetCatalog),
            BLOCK_PROMPT_TEMPLATES to emitPromptTemplates(presets.promptTemplates),
        )
    }

    // ------------------------------------------------------------------ //
    //  Parsers
    // ------------------------------------------------------------------ //

    private val ENUM_CONSTANT = Regex("""(?m)^\s*([A-Z_][A-Z0-9_]*)\s*,\s*$""")
    private val PROVIDER_BIND = Regex("""fun\s+bind\w+\(\s*impl:\s*(\w+)\s*\)\s*:\s*PromptVariableProvider""")
    private val KEY_CONST = Regex("""const\s+val\s+KEY\s*=\s*"([^"]+)"""")
    private val TOOL_STRING_KEY = Regex("""@StringKey\(\s*(\w+)\.TOOL_NAME\s*\)""")
    private val TOOL_NAME_CONST = Regex("""const\s+val\s+TOOL_NAME\s*=\s*"([^"]+)"""")

    /**
     * Parses the `NodeType` enum-constant names, in declaration order, from
     * `NodeType.kt`. Only top-level `UPPER_SNAKE,` lines inside the enum body
     * match, so KDoc references (`[NodeType.OUTPUT]`) are ignored.
     */
    fun parseNodeTypeNames(source: String): List<String> {
        val bodyStart = source.indexOf("enum class NodeType")
        if (bodyStart < 0) throw GenerationException("'enum class NodeType' not found")
        val body = source.substring(bodyStart)
        val names = ENUM_CONSTANT.findAll(body).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) throw GenerationException("No NodeType enum constants found")
        return names
    }

    /**
     * Parses the bound [app.knotwork.android.domain.prompt.PromptVariableProvider]
     * implementation class names, in `@Binds @IntoSet` declaration order, from
     * `PromptTemplateModule.kt`.
     */
    fun parseBoundProviderClassNames(moduleSource: String): List<String> {
        val names = PROVIDER_BIND.findAll(moduleSource).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) throw GenerationException("No PromptVariableProvider bindings found")
        return names
    }

    /** Parses the `KEY` string constant from a `PromptVariableProvider` source. */
    fun parseKeyConst(providerSource: String): String =
        KEY_CONST.find(providerSource)?.groupValues?.get(1)
            ?: throw GenerationException("`const val KEY` not found in provider source")

    /**
     * Parses the class names referenced as `<Class>.TOOL_NAME` in the
     * `@StringKey(...)` annotations of `LocalToolsModule.kt`, in declaration order.
     */
    fun parseBoundToolClassNames(moduleSource: String): List<String> {
        val names = TOOL_STRING_KEY.findAll(moduleSource).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) throw GenerationException("No @StringKey(<Class>.TOOL_NAME) bindings found")
        return names
    }

    /** Parses the `TOOL_NAME` string constant from a tool / executor source. */
    fun parseToolNameConst(toolSource: String): String =
        TOOL_NAME_CONST.find(toolSource)?.groupValues?.get(1)
            ?: throw GenerationException("`const val TOOL_NAME` not found in tool source")

    /**
     * Evaluates the value of a top-level `const val <name>` in `DefaultPrompts.kt`.
     *
     * The right-hand side is a Kotlin string-concatenation expression
     * (`"..." + "..." + ...`); this collects every double-quoted literal up to the
     * next declaration / comment boundary, unescapes each, and concatenates them.
     */
    fun parseStringConst(source: String, name: String): String {
        val anchor = Regex("""const\s+val\s+$name\s*=""").find(source)
            ?: throw GenerationException("`const val $name` not found")
        val rest = source.substring(anchor.range.last + 1)
        // The expression ends at the next line that starts a new declaration, a
        // KDoc/line comment, or the enclosing brace close.
        val boundary = Regex("""(?m)^\s*(/\*\*|//|const |val |var |fun |object |class |private |\})""")
            .find(rest)
        val region = if (boundary != null) rest.substring(0, boundary.range.first) else rest
        val literals = STRING_LITERAL.findAll(region).map { it.groupValues[1] }.toList()
        if (literals.isEmpty()) throw GenerationException("No string literal found for `$name`")
        return literals.joinToString(separator = "") { unescapeKotlin(it) }
    }

    private val STRING_LITERAL = Regex(""""((?:\\.|[^"\\])*)"""")

    // ------------------------------------------------------------------ //
    //  Emitters (all produce content at the editor's 4-space base indent)
    // ------------------------------------------------------------------ //

    /** Emits the `NODE_TYPES` array, validating [enumNames] against [NODE_TYPE_META]. */
    fun emitNodeTypes(enumNames: List<String>): String {
        val metaIds = NODE_TYPE_META.map { it.id }.toSet()
        val enumSet = enumNames.toSet()
        if (metaIds != enumSet) {
            val missing = enumSet - metaIds
            val extra = metaIds - enumSet
            throw GenerationException(
                "NODE_TYPE_META is out of sync with NodeType.kt." +
                    (if (missing.isNotEmpty()) " Missing metadata for: $missing." else "") +
                    (if (extra.isNotEmpty()) " Unknown node types in metadata: $extra." else ""),
            )
        }
        val rows = NODE_TYPE_META.joinToString(separator = "\n") { m ->
            "        { id: ${jsonString(m.id)}, label: ${jsonString(m.label)}, " +
                "color: ${jsonString(m.color)}, icon: ${jsonString(m.icon)}, " +
                "inputs: ${m.inputs}, outputs: ${m.outputs} },"
        }
        return "    const NODE_TYPES = [\n$rows\n    ];"
    }

    /** Emits the `PROMPT_VARIABLES` array from the resolved provider keys. */
    fun emitPromptVariables(keys: List<String>): String {
        val joined = keys.joinToString(separator = ", ") { jsonString(it) }
        return "    const PROMPT_VARIABLES = [$joined];"
    }

    /** Emits the `AVAILABLE_TOOLS` array, validating [toolIds] against [TOOL_META]. */
    fun emitAvailableTools(toolIds: List<String>): String {
        val metaIds = TOOL_META.map { it.first }.toSet()
        val idSet = toolIds.toSet()
        if (metaIds != idSet) {
            val missing = idSet - metaIds
            val extra = metaIds - idSet
            throw GenerationException(
                "TOOL_META is out of sync with LocalToolsModule.kt." +
                    (if (missing.isNotEmpty()) " Missing label for tool ids: $missing." else "") +
                    (if (extra.isNotEmpty()) " Unknown tool ids in metadata: $extra." else ""),
            )
        }
        val rows = TOOL_META.joinToString(separator = "\n") { (id, label) ->
            "        { id: ${jsonString(id)}, label: ${jsonString(label)} },"
        }
        return "    const AVAILABLE_TOOLS = [\n$rows\n    ];"
    }

    /** Emits `SYSTEM_PROMPT_PREFIX` and the `DEFAULT_SYSTEM_PROMPTS` map from `DefaultPrompts.kt`. */
    fun emitDefaultPrompts(defaultPromptsSource: String): String {
        val prefix = parseStringConst(defaultPromptsSource, "SYSTEM_PROMPT_PREFIX")
        val entries = PROMPT_CONST_BY_JS_KEY.map { (jsKey, constName) ->
            jsKey to parseStringConst(defaultPromptsSource, constName)
        }
        val builder = StringBuilder()
        builder.append("    const SYSTEM_PROMPT_PREFIX = ${jsonString(prefix)};\n")
        builder.append("\n")
        builder.append("    const DEFAULT_SYSTEM_PROMPTS = {\n")
        builder.append("        LITE_RT: SYSTEM_PROMPT_PREFIX,\n")
        builder.append("        CLOUD: SYSTEM_PROMPT_PREFIX,\n")
        for ((jsKey, value) in entries) {
            builder.append("        $jsKey: ${jsonString(value)},\n")
        }
        builder.append("    };")
        return builder.toString()
    }

    /**
     * Emits `BUILTIN_PIPELINE_PRESETS`: every preset document, verbatim, in the app's order.
     *
     * The documents are carried as text, not re-serialised: each canonical 2-space
     * JSON line is re-indented to the editor's 4-space nesting under an 8-space base,
     * which reproduces `JSON.stringify(document, null, 4)` inside the array without a
     * JSON library in the build. A file that is not in canonical 2-space form fails
     * generation rather than producing a block that merely looks different.
     *
     * Order: the ids of `BundledPresetCatalog.DISPLAY_ORDER`, then the internal
     * sub-pipelines alphabetically — the order the app lists them in. A preset that is
     * neither ranked nor internal fails, as the app's catalogue test does.
     *
     * @param presetSources Preset file content keyed by file name.
     * @param catalogSource Content of `BundledPresetCatalog.kt`.
     * @return The block content.
     * @throws GenerationException on a malformed file, an unranked public preset, or a
     *   ranked id with no file.
     */
    fun emitPipelinePresets(presetSources: Map<String, String>, catalogSource: String): String {
        if (presetSources.isEmpty()) throw GenerationException("No bundled pipeline presets found")
        val displayOrder = parseDisplayOrder(catalogSource)
        val documents = presetSources.map { (fileName, content) ->
            val id = TOP_LEVEL_ID.find(content)?.groupValues?.get(1)
                ?: throw GenerationException("Preset $fileName has no top-level \"id\"")
            Triple(id, INTERNAL_FLAG.containsMatchIn(content), reindentPreset(fileName, content))
        }
        val byId = documents.associateBy { it.first }
        val missing = displayOrder.filter { it !in byId }
        if (missing.isNotEmpty()) {
            throw GenerationException("BundledPresetCatalog.DISPLAY_ORDER names presets with no file: $missing")
        }
        val unranked = documents.filter { !it.second && it.first !in displayOrder }.map { it.first }
        if (unranked.isNotEmpty()) {
            throw GenerationException("Public presets missing from BundledPresetCatalog.DISPLAY_ORDER: $unranked")
        }
        val ordered = displayOrder.map { byId.getValue(it) } +
            documents.filter { it.first !in displayOrder }.sortedBy { it.first }
        return "    const BUILTIN_PIPELINE_PRESETS = [\n" +
            ordered.joinToString(separator = ",\n") { it.third } +
            "\n    ];"
    }

    /**
     * Emits `BUILTIN_PROMPT_TEMPLATES`: every template's id, node type, name,
     * description and prompt (`systemPrompt`, published as `content`), grouped by node
     * type in [NODE_TYPE_META] palette order and by id within a group.
     *
     * @param templateSources Template file content keyed by file name.
     * @return The block content.
     * @throws GenerationException on a missing field or a node type the palette lacks.
     */
    fun emitPromptTemplates(templateSources: Map<String, String>): String {
        if (templateSources.isEmpty()) throw GenerationException("No bundled prompt templates found")
        val paletteOrder = NODE_TYPE_META.map { it.id }
        val templates = templateSources.map { (fileName, content) ->
            val fields = TOP_LEVEL_STRING_FIELD.findAll(content)
                .associate { it.groupValues[1] to unescapeJson(it.groupValues[2]) }
            val required = listOf("id", "nodeType", "name", "description", "systemPrompt")
            val absent = required.filter { it !in fields }
            if (absent.isNotEmpty()) throw GenerationException("Prompt template $fileName lacks $absent")
            if (fields.getValue("nodeType") !in paletteOrder) {
                throw GenerationException(
                    "Prompt template $fileName targets unknown node type ${fields.getValue("nodeType")}",
                )
            }
            fields
        }.sortedWith(compareBy({ paletteOrder.indexOf(it.getValue("nodeType")) }, { it.getValue("id") }))
        val rows = templates.joinToString(separator = "\n") { t ->
            "        {\n" +
                "            id: ${scriptString(t.getValue("id"))},\n" +
                "            nodeType: ${scriptString(t.getValue("nodeType"))},\n" +
                "            name: ${scriptString(t.getValue("name"))},\n" +
                "            description: ${scriptString(t.getValue("description"))},\n" +
                "            content: ${scriptString(t.getValue("systemPrompt"))},\n" +
                "        },"
        }
        return "    const BUILTIN_PROMPT_TEMPLATES = [\n$rows\n    ];"
    }

    /**
     * Re-indents one canonical 2-space JSON document to the preset block's nesting.
     *
     * @throws GenerationException when a line is indented with tabs or an odd number of spaces.
     */
    private fun reindentPreset(fileName: String, content: String): String =
        content.trimEnd('\n').lines().joinToString(separator = "\n") { line ->
            val indent = line.length - line.trimStart(' ').length
            if (indent % 2 != 0 || line.startsWith("\t")) {
                throw GenerationException("Preset $fileName is not canonical 2-space JSON: \"$line\"")
            }
            " ".repeat(PRESET_BASE_INDENT + indent * 2) + escapeScriptClose(line.substring(indent))
        }

    /**
     * Parses the preset ids listed in `BundledPresetCatalog.DISPLAY_ORDER`, in order.
     *
     * @throws GenerationException when the list cannot be found.
     */
    fun parseDisplayOrder(catalogSource: String): List<String> {
        val anchor = catalogSource.indexOf("DISPLAY_ORDER")
        if (anchor < 0) throw GenerationException("`DISPLAY_ORDER` not found in BundledPresetCatalog.kt")
        val open = catalogSource.indexOf("listOf(", anchor)
        val close = catalogSource.indexOf(')', open)
        if (open < 0 || close < 0) throw GenerationException("`DISPLAY_ORDER = listOf(...)` not found")
        val ids = STRING_LITERAL.findAll(catalogSource.substring(open, close)).map { it.groupValues[1] }.toList()
        if (ids.isEmpty()) throw GenerationException("`DISPLAY_ORDER` lists no presets")
        return ids
    }

    /**
     * Decodes the body of a JSON string literal (the text between the quotes).
     */
    fun unescapeJson(body: String): String {
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val next = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        sb.append(body.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** A [jsonString] that is also safe inside an inline `<script>` element. */
    private fun scriptString(value: String): String = escapeScriptClose(jsonString(value))

    /**
     * Breaks every `</` so bundled text can never close the inline `<script>` element it
     * is embedded in; `<\/` reads as `</` in both JSON and JavaScript strings.
     */
    private fun escapeScriptClose(text: String): String = text.replace("</", "<\\/")

    private const val PRESET_BASE_INDENT = 8
    private val TOP_LEVEL_ID = Regex("""(?m)^  "id": "([^"]+)",?$""")
    private val INTERNAL_FLAG = Regex("""(?m)^  "internal": true,?$""")
    private val TOP_LEVEL_STRING_FIELD = Regex("""(?m)^  "(\w+)": "((?:\\.|[^"\\])*)",?$""")

    // ------------------------------------------------------------------ //
    //  Marker injection
    // ------------------------------------------------------------------ //

    /** Opening marker line for [block]. */
    fun openMarker(block: String): String =
        "    /* AUTO-GEN:$block — generated by :app:generateBrowserEditorConstants. DO NOT EDIT BY HAND. */"

    /** Closing marker line for [block]. */
    fun closeMarker(block: String): String = "    /* /AUTO-GEN:$block */"

    /**
     * Replaces the content between [block]'s `AUTO-GEN` markers with [content],
     * preserving the marker lines and everything outside them.
     *
     * @throws GenerationException if either marker is absent or mis-ordered.
     */
    fun injectBlock(html: String, block: String, content: String): String {
        val openToken = "/* AUTO-GEN:$block"
        val closeToken = "/* /AUTO-GEN:$block */"
        val openIdx = html.indexOf(openToken)
        if (openIdx < 0) throw GenerationException("Missing open marker for block '$block'")
        val openLineEnd = html.indexOf('\n', openIdx)
        if (openLineEnd < 0) throw GenerationException("Malformed open marker for block '$block'")
        val closeIdx = html.indexOf(closeToken, openLineEnd)
        if (closeIdx < 0) throw GenerationException("Missing close marker for block '$block'")
        val closeLineStart = html.lastIndexOf('\n', closeIdx) + 1
        return html.substring(0, openLineEnd + 1) +
            content + "\n" +
            html.substring(closeLineStart)
    }

    /**
     * Extracts the current content between [block]'s markers, used by [drift] to
     * compare against freshly-generated content. Returns `null` if the block is
     * absent.
     */
    fun extractBlock(html: String, block: String): String? {
        val openToken = "/* AUTO-GEN:$block"
        val closeToken = "/* /AUTO-GEN:$block */"
        val openIdx = html.indexOf(openToken)
        if (openIdx < 0) return null
        val openLineEnd = html.indexOf('\n', openIdx)
        if (openLineEnd < 0) return null
        val closeIdx = html.indexOf(closeToken, openLineEnd)
        if (closeIdx < 0) return null
        val closeLineStart = html.lastIndexOf('\n', closeIdx) + 1
        // Content sits between the open marker's trailing newline and the close
        // marker line, minus the single separating newline injectBlock adds.
        return html.substring(openLineEnd + 1, closeLineStart).removeSuffix("\n")
    }

    // ------------------------------------------------------------------ //
    //  String helpers
    // ------------------------------------------------------------------ //

    /**
     * Unescapes a Kotlin double-quoted string-literal body (the text between the
     * quotes), handling the standard escape sequences plus `\uXXXX`.
     */
    fun unescapeKotlin(literalBody: String): String {
        val sb = StringBuilder(literalBody.length)
        var i = 0
        while (i < literalBody.length) {
            val c = literalBody[i]
            if (c == '\\' && i + 1 < literalBody.length) {
                when (val next = literalBody[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '\\' -> sb.append('\\')
                    '$' -> sb.append('$')
                    'u' -> {
                        val hex = literalBody.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Encodes [value] as a JavaScript/JSON double-quoted string literal. Control
     * characters are escaped; printable Unicode (including emoji) is left as-is.
     */
    fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
