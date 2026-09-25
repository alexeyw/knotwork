package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserEditorImportParityGuard]. The guard itself runs over the real
 * `pipeline-editor.html`, `PipelineJsonSerializer.kt` and `CloudProvider.kt` in
 * `:app:verifyBrowserEditorConstants`, which declares all three as inputs.
 */
class BrowserEditorImportParityGuardTest {

    private val serializer = """
        private val CONFIG_KEYS = setOf(
            "systemPrompt", "cloudProvider", "stopOnError",
        )
        private val CONTEXT_CONFIG_KEYS = setOf(
            "chatHistory",
            "nodeInput",
            "longTermMemory",
        )
    """.trimIndent()

    private val cloudProvider = """
        fun fromId(id: String?): CloudProvider? = when (id?.lowercase()) {
            null -> null
            "anthropic" -> ANTHROPIC
            "google", "gemini" -> GOOGLE
            else -> null
        }
    """.trimIndent()

    /** An editor that reads a file by the app's rules; each argument replaces one piece. */
    private fun editor(
        importBody: String = "const flatIn = { systemPrompt: jn.config?.systemPrompt, " +
            "stopOnError: jn.config?.stopOnError === undefined ? undefined : wireBool(jn.config.stopOnError) };\n" +
            "const ctx = readContextConfig(jn.type, jn.contextConfig);",
        reader: String = "const flag = v => wireBool(v) ?? true;\n" +
            "return { chatHistory: flag(raw.chatHistory), nodeInput: true, longTermMemory: flag(raw.longTermMemory) };",
        resolver: String = "switch (String(flatId ?? '').toLowerCase()) {\n" +
            " case 'anthropic': return 'ANTHROPIC';\n case 'google':\n case 'gemini': return 'GOOGLE';\n" +
            " default: return null; }",
        cloud: String = "return wireToTile(flatId) ?? 'AUTO';",
        engine: String = "return wireToTile(flatId) ?? '';",
    ) = """
        function importFromJson(doc) {
            rawNodes.forEach(jn => {
                $importBody
            });
        }
        function wireBool(v) { return typeof v === 'boolean' ? v : null; }
        function readContextConfig(typeId, raw) {
            $reader
        }
        function wireToTile(flatId) {
            $resolver
        }
        function flatToRichCloud(flatId) {
            $cloud
        }
        function flatToEngineProvider(flatId) {
            $engine
        }
    """.trimIndent()

    private fun mismatches(html: String) = BrowserEditorImportParityGuard.mismatches(html, serializer, cloudProvider)

    @Test
    fun `given an editor that reads a file by the app's rules when checked then nothing is reported`() {
        assertTrue(mismatches(editor()).isEmpty())
    }

    @Test
    fun `given an import reading a config key the app does not read when checked then it is reported`() {
        val html = editor(
            importBody = "const p = jn.config?.routerPrompt; readContextConfig(jn.type, jn.contextConfig);",
        )

        assertEquals(
            listOf("importFromJson reads config.routerPrompt, which the app's importer does not read"),
            mismatches(html),
        )
    }

    @Test
    fun `given the legacy intentRouterPrompt key when checked then it is allowed`() {
        val html = editor(
            importBody = "if (jn.config?.intentRouterPrompt) {} readContextConfig(jn.type, jn.contextConfig);",
        )

        assertTrue(mismatches(html).isEmpty())
    }

    @Test
    fun `given contextConfig read outside readContextConfig when checked then it is reported`() {
        val html = editor(
            importBody = "const ctx = Object.assign(defaults, jn.contextConfig || {});\n" +
                "const again = readContextConfig(jn.type, jn.contextConfig);",
        )

        assertEquals(listOf("importFromJson reads contextConfig outside readContextConfig"), mismatches(html))
    }

    @Test
    fun `given a reader that skips a flag the app reads when checked then the flag is reported`() {
        val html = editor(
            reader = "const flag = v => wireBool(v) ?? true;\nreturn { chatHistory: flag(raw.chatHistory) };",
        )

        assertEquals(listOf("readContextConfig does not read contextConfig.longTermMemory"), mismatches(html))
    }

    @Test
    fun `given a reader that coerces flags itself when checked then it is reported`() {
        val html = editor(reader = "return { chatHistory: !!raw.chatHistory, longTermMemory: !!raw.longTermMemory };")

        assertEquals(listOf("readContextConfig does not read flags through wireBool"), mismatches(html))
    }

    @Test
    fun `given a case-sensitive resolver missing an alias when checked then both are reported`() {
        val html = editor(
            resolver = "switch (flatId) { case 'anthropic': return 'ANTHROPIC'; case 'google': return 'GOOGLE'; }",
        )

        assertEquals(
            listOf(
                "wireToTile does not know provider id 'gemini'",
                "wireToTile matches provider ids case-sensitively",
            ),
            mismatches(html),
        )
    }

    @Test
    fun `given a provider reader with its own switch when checked then it is reported`() {
        val html = editor(engine = "switch (flatId) { case 'anthropic': return 'ANTHROPIC'; default: return ''; }")

        assertEquals(listOf("flatToEngineProvider does not resolve provider ids through wireToTile"), mismatches(html))
    }

    @Test(expected = IllegalStateException::class)
    fun `given an editor without readContextConfig when checked then it fails loudly`() {
        mismatches(editor().replace("function readContextConfig(", "function readContext("))
    }

    @Test(expected = IllegalStateException::class)
    fun `given a serializer without CONFIG_KEYS when checked then it fails loudly`() {
        val renamed = serializer.replace("CONFIG_KEYS = ", "KEYS = ")

        BrowserEditorImportParityGuard.mismatches(editor(), renamed, cloudProvider)
    }
}
