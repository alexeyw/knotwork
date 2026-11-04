package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Census of how production code draws text: every text goes through the design system's
 * bounded `Text`; the screens that show a text whole, and every input field, are named here
 * with the reason.
 *
 * **Why.** Laying out one text costs time in its length and in the square of its longest
 * run without whitespace, whatever its line limit: a tool's or a model's 200 000-character
 * string without spaces held the main thread for 5 to 10 s in a single text, and four Play
 * reports caught the main thread stuck in text layout. The text could come from anywhere — a
 * tool observation in Monitoring, an exception in the console, a memory entry in a two-line
 * row — so fixing the screens found one by one would leave the next one open. The
 * catalog's `Text` (`components/text/Text.kt`) bounds what any text lays out; this guard
 * makes it the only way to draw one, so a new screen is bounded without deciding anything.
 *
 * What it cannot bound is named instead:
 * - [WHOLE_TEXT] — screens that show a long text whole (`WholeText`), each with why it may
 *   not cut. Their layout grows with the length, never with its square.
 * - [FIELDS] — input fields. A field lays out its whole value on every edit and cannot be
 *   drawn in pieces; none is cut (the user's own text or library), each is named.
 * - [EXEMPT] — the files that draw with Material's own text on purpose.
 *
 * **What it reads.** The production sources of `:app` (every source set) and `:catalog`,
 * comments removed. It sees imports, wildcard imports and fully qualified calls of
 * Material's, the foundation's and Material 2's text; a text reached through some other
 * library (the Markdown renderer's own) is not seen — the chat's Markdown components bound
 * theirs (`KnotworkMarkdownComponents.kt`). A field reached through the catalog's
 * `KnotworkTextField` / `KnotworkTextArea` is named once, at that component.
 */
class BoundedTextGuardTest {

    @Test
    fun `no production file draws text with Material's or the foundation's own text`() {
        val direct = sources.filterValues { DIRECT_TEXT.containsMatchIn(it) }.keys - CHOKE_POINT

        assertEquals(
            "a file draws text with Material's own Text (or BasicText / ClickableText). Import " +
                "app.knotwork.design.components.text.Text instead: it bounds what a text lays out however " +
                "long the string. If the file must lay out exactly as Material does, name it in " +
                "BoundedTextGuardTest.EXEMPT with the reason.",
            EXEMPT.keys,
            direct,
        )
    }

    @Test
    fun `every screen that shows a text whole is in the census`() {
        val whole = sources.filterValues { WHOLE_TEXT_CALL.containsMatchIn(it) }.keys - CHOKE_POINT

        assertEquals(
            "the set of files calling WholeText changed. WholeText shows every character however long; " +
                "use it only where cutting would hide what the screen is for, and name the file in " +
                "BoundedTextGuardTest.WHOLE_TEXT with that reason — or remove the one that is gone.",
            WHOLE_TEXT.keys,
            whole,
        )
    }

    @Test
    fun `every input field is in the census`() {
        val fields = sources.filterValues { FIELD_CALL.containsMatchIn(it) }.keys

        assertEquals(
            "the set of files composing an input field changed. A field lays out its whole value on " +
                "every edit and cannot be cut into pieces; name the file in BoundedTextGuardTest.FIELDS " +
                "with where its value comes from — or remove the one that is gone.",
            FIELDS.keys,
            fields,
        )
    }

    @Test
    fun `the census sees every spelling of Material's text`() {
        listOf(
            "import androidx.compose.material3.Text",
            "import androidx.compose.material3.Text as MaterialText",
            "import androidx.compose.material3.*",
            "import androidx.compose.material.Text",
            "import androidx.compose.foundation.text.BasicText",
            "import androidx.compose.foundation.text.ClickableText",
            "androidx.compose.material3.Text(text = value)",
        ).forEach { assertTrue("not seen: $it", DIRECT_TEXT.containsMatchIn(it)) }
        listOf(
            "import androidx.compose.material3.TextField",
            "import androidx.compose.material3.TextButton",
            "import androidx.compose.foundation.text.BasicTextField",
            "import androidx.compose.foundation.text.InlineTextContent",
            "import app.knotwork.design.components.text.Text",
        ).forEach { assertTrue("wrongly seen: $it", !DIRECT_TEXT.containsMatchIn(it)) }
    }

    @Test
    fun `the census sees every spelling of a field and of a whole text`() {
        listOf(
            "BasicTextField(",
            "OutlinedTextField(",
            "TextField(",
            "androidx.compose.foundation.text.BasicTextField(",
        ).forEach { assertTrue("not seen: $it", FIELD_CALL.containsMatchIn(it)) }
        listOf("KnotworkTextField(", "EditorTextField(", "TextFieldValue(", "rememberTextFieldState(")
            .forEach { assertTrue("wrongly seen: $it", !FIELD_CALL.containsMatchIn(it)) }
        assertTrue(WHOLE_TEXT_CALL.containsMatchIn("WholeText(text = body)"))
        assertTrue(!WHOLE_TEXT_CALL.containsMatchIn("SomeWholeText(text = body)"))
    }

    @Test
    fun `the choke point draws with Material and the census reads both modules`() {
        assertTrue(DIRECT_TEXT.containsMatchIn(checkNotNull(sources[CHOKE_POINT])))
        // Keeps the census from passing on an empty or one-module read.
        assertTrue(sources.keys.any { it.startsWith("main/") })
        assertTrue(sources.keys.count { it.startsWith("catalog/") } >= MIN_CATALOG_FILES)
    }

    private companion object {
        const val CATALOG = "catalog/java/app/knotwork/design"

        /** The design system's text, the one file that draws with Material's own. */
        const val CHOKE_POINT = "$CATALOG/components/text/Text.kt"

        /** Catalog files at the time of writing, rounded down. */
        const val MIN_CATALOG_FILES = 300

        /** Material's, Material 2's or the foundation's text: imported, wildcard-imported or called by name. */
        val DIRECT_TEXT = Regex(
            """androidx\.compose\.(?:material3|material)\.Text\b(?!\w)|""" +
                """androidx\.compose\.foundation\.text\.(?:BasicText|ClickableText)\b|""" +
                """import\s+androidx\.compose\.(?:material3|material|foundation\.text)\.\*""",
        )

        /** A call of the catalog's `WholeText`, not of a name ending in it. */
        val WHOLE_TEXT_CALL = Regex("""(?<![A-Za-z0-9_.])WholeText\(""")

        /** A field composed directly, by simple or fully qualified name. */
        val FIELD_CALL = Regex(
            """(?<![A-Za-z0-9_.])(?:androidx\.compose\.[a-z0-9.]+\.)?""" +
                """(?:BasicTextField|OutlinedTextField|TextField)\(""",
        )

        /** Files that draw with Material's own text on purpose, and why. */
        val EXEMPT = mapOf(
            "$CATALOG/screens/skills/SkillEditorContent.kt" to
                "The instruction editor's highlight layer is a transparent text laid over the field; it must " +
                "break lines exactly where the field does, and the app's text would continue a long run on a " +
                "new line where the field does not. It lays out the field's value once more — named with " +
                "the field in FIELDS.",
        )

        /** Screens that show a long text whole, and why cutting it would defeat them. */
        val WHOLE_TEXT = mapOf(
            "$CATALOG/components/chat/HitlConfirmationCard.kt" to
                "Expanded arguments of an approval: the user approves exactly what will run " +
                "(api-conventions.md § AppFunctions), so no argument may be shortened.",
            "$CATALOG/components/chat/ClarificationCard.kt" to
                "The model's question, which the user answers; when the model's reply does not parse, the " +
                "question is the whole reply.",
            "$CATALOG/components/chat/ChatMessageReader.kt" to
                "The message reader is where a long message is read whole; a plain-text line holds any " +
                "length of prose.",
            "$CATALOG/components/prompt/PromptPreviewSheet.kt" to
                "The rendered prompt, checked before it runs: every substituted value as the model gets it.",
            "$CATALOG/screens/files/FilesContent.kt" to
                "A file preview, already the file's first 64 KiB.",
            "$CATALOG/screens/memory/MemoryContent.kt" to
                "A memory entry's detail, where the entry is read.",
            "$CATALOG/screens/memory/MemoryHistorySection.kt" to
                "The other entry of a merged pair, and an expanded earlier version of an entry.",
        )

        /** Input fields, by where the value they open with comes from. */
        val FIELDS = mapOf(
            "$CATALOG/components/chat/ChatComposer.kt" to "The chat composer: typed, pasted or dictated.",
            "$CATALOG/components/chat/ClarificationCard.kt" to "The answer to a question: typed, one line.",
            "$CATALOG/components/chat/HitlConfirmationCard.kt" to "The typed confirmation: one word.",
            "$CATALOG/components/chips/KnotworkChipsInput.kt" to "A new chip: typed, one line.",
            "$CATALOG/components/console/ConsolePane.kt" to "The console search: typed, one line.",
            "$CATALOG/components/controls/KnotworkTextArea.kt" to
                "The catalog's text area: typed (the report note).",
            "$CATALOG/components/controls/KnotworkTextField.kt" to
                "The catalog's text field: names, searches, hosts — typed, one line.",
            "$CATALOG/components/dialogs/TypedConfirmDialog.kt" to "The typed confirmation: one word.",
            "$CATALOG/components/pipelineeditor/EditorToolbar.kt" to "The pipeline's name: typed, one line.",
            "$CATALOG/components/pipelineeditor/NodeConfigFields.kt" to
                "A node's title and prompts: typed, or a prompt from an imported pipeline file.",
            "$CATALOG/components/pipelineeditor/NodeConfigFlowForms.kt" to
                "Flow nodes' prompts, keywords and classes: typed or imported with a pipeline.",
            "$CATALOG/components/pipelineeditor/NodeConfigModelForms.kt" to
                "Model nodes' prompts and model ids: typed or imported with a pipeline.",
            "$CATALOG/components/pipelineeditor/NodeConfigTargetForms.kt" to
                "Tool and skill nodes' ids, and the skill's instruction preview — cut to 2 000 characters " +
                "where the editor builds it (asInstructionPreview).",
            "$CATALOG/components/pipelineeditor/NodeTypePickerSheetBody.kt" to "The node-type search: typed.",
            "$CATALOG/screens/memory/MemoryContent.kt" to
                "Memory search and tags (typed), a new entry (typed), and editing an entry, which opens with " +
                "the whole entry.",
            "$CATALOG/screens/models/ModelsContent.kt" to "A model's address and the access token: typed or pasted.",
            "$CATALOG/screens/onboarding/OnboardingContent.kt" to "A custom server address: typed, one line.",
            "$CATALOG/screens/prompts/PromptLibraryContent.kt" to
                "A prompt's name and body: typed, or from an imported prompt pack.",
            "$CATALOG/screens/settings/KnotworkMonoTextArea.kt" to "The catalog's monospaced text area: typed.",
            "$CATALOG/screens/settings/ModelPickerSheet.kt" to "The model search: typed.",
            "$CATALOG/screens/settings/ProviderDetailContent.kt" to "A provider's address, key and model: typed.",
            "$CATALOG/screens/settings/SettingsCommon.kt" to "The system instructions setting: typed.",
            "$CATALOG/screens/skills/SkillEditorContent.kt" to
                "A skill's name, description and instruction: typed, or restored with a backup.",
            "$CATALOG/screens/tools/McpServerConfigContent.kt" to "An MCP server's address, headers and name: typed.",
            "$CATALOG/screens/triggers/TriggerEditorContent.kt" to "A trigger's name, prompt and conditions: typed.",
        )

        /** Production Kotlin of `:app` (every source set) and `:catalog`, comments removed. */
        val sources: Map<String, String> by lazy {
            val catalog = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main")
            ProductionSources.code + catalog.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate {
                    "catalog/" + it.relativeTo(catalog).invariantSeparatorsPath to
                        ProductionSources.stripComments(it.readText())
                }
        }
    }
}
