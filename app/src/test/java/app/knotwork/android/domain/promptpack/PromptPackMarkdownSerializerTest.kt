package app.knotwork.android.domain.promptpack

import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPackImportOutcome
import app.knotwork.android.domain.models.PromptPackParseError
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.models.RefusedCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [PromptPackMarkdownSerializer].
 *
 * Two groups matter most. The **round trip** keeps an exported prompt
 * readable by the importer that wrote it. The **capability ceiling** is the
 * half of this feature that carries risk: a prompt pack is text a stranger
 * may have written which ends up in the system prompt of an agent holding
 * tools, so "it can only supply wording" has to be a property of the code
 * and not a sentence in a document.
 */
class PromptPackMarkdownSerializerTest {

    private val preset = PromptPreset(
        id = "concise-assistant",
        name = "Concise assistant",
        description = "Single-paragraph answers, no preamble.",
        nodeType = NodeType.LITE_RT,
        systemPrompt = "You are concise. Today is \$DATE.",
        tags = listOf("concise", "starter"),
        isBundled = false,
    )

    private fun document(
        frontmatter: String = "name: Concise assistant\nnodeType: LITE_RT",
        body: String = "You are concise.",
    ) = "---\n$frontmatter\n---\n\n$body\n"

    private fun success(document: String) =
        PromptPackMarkdownSerializer.parse(document, fallbackId = "fallback") as PromptPackImportOutcome.Success

    private fun partial(document: String) =
        PromptPackMarkdownSerializer.parse(document, fallbackId = "fallback") as PromptPackImportOutcome.Partial

    private fun failure(document: String) =
        (PromptPackMarkdownSerializer.parse(document, fallbackId = "fallback") as PromptPackImportOutcome.Failure)
            .cause

    // --- Round trip -------------------------------------------------------

    @Test
    fun `given a preset when serialized and parsed then every carried field survives`() {
        val candidate = success(PromptPackMarkdownSerializer.serialize(preset)).preset

        assertEquals(preset.id, candidate.id)
        assertEquals(preset.name, candidate.name)
        assertEquals(preset.description, candidate.description)
        assertEquals(preset.nodeType, candidate.nodeType)
        assertEquals(preset.systemPrompt, candidate.systemPrompt)
        assertEquals(preset.tags, candidate.tags)
    }

    @Test
    fun `given a name needing quotes when round-tripped then the quotes do not leak into the value`() {
        val awkward = preset.copy(name = "\"Quoted\" name", description = "key: value looking text")

        val candidate = success(PromptPackMarkdownSerializer.serialize(awkward)).preset

        assertEquals("\"Quoted\" name", candidate.name)
        assertEquals("key: value looking text", candidate.description)
    }

    @Test
    fun `given a tag containing a comma when round-tripped then it stays one tag`() {
        // The inline list splits on commas before quotes are resolved, so the
        // serializer has to fall back to the block form. Without that, one
        // tag silently becomes two.
        val awkward = preset.copy(tags = listOf("a,b", "plain"))

        val candidate = success(PromptPackMarkdownSerializer.serialize(awkward)).preset

        assertEquals(listOf("a,b", "plain"), candidate.tags)
    }

    @Test
    fun `given a multi-paragraph prompt when round-tripped then its inner blank lines survive`() {
        val multi = preset.copy(systemPrompt = "First paragraph.\n\nSecond paragraph.")

        val candidate = success(PromptPackMarkdownSerializer.serialize(multi)).preset

        assertEquals("First paragraph.\n\nSecond paragraph.", candidate.systemPrompt)
    }

    @Test
    fun `given a preset when serialized then the document declares the current schema version`() {
        assertTrue(
            PromptPackMarkdownSerializer.serialize(preset)
                .contains("schemaVersion: ${PromptPackMarkdownSerializer.CURRENT_SCHEMA_VERSION}"),
        )
    }

    @Test
    fun `given a preset when a file name is suggested then it is a kebab-case md file`() {
        assertEquals("concise-assistant.md", PromptPackMarkdownSerializer.suggestFileName(preset))
        assertEquals("prompt.md", PromptPackMarkdownSerializer.suggestFileName(preset.copy(name = "★★★")))
    }

    // --- The capability ceiling ------------------------------------------

    @Test
    fun `given a file asking for tools when parsed then the prompt imports and the request is refused`() {
        val outcome = partial(
            document(frontmatter = "name: Web summarizer\nnodeType: CLOUD\nallowed-tools: web_search fetch_url"),
        )

        // The prompt itself is complete — a refusal is not a truncation.
        assertEquals("You are concise.", outcome.preset.systemPrompt)
        assertTrue(outcome.notes.hasRefusal)
        val tools = outcome.notes.refused.single { it.kind == RefusedCapability.Kind.TOOLS }
        assertEquals(listOf("web_search", "fetch_url"), tools.values)
    }

    @Test
    fun `given every recognised capability key when parsed then each is refused under its family`() {
        val outcome = partial(
            document(
                frontmatter = """
                name: Greedy
                nodeType: CLOUD
                tools: [a]
                mcp: [b]
                permissions: [c]
                nodes: [one, two]
                scripts: [run.js]
                """.trimIndent(),
            ),
        )

        val kinds = outcome.notes.refused.map { it.kind }
        assertEquals(
            listOf(
                RefusedCapability.Kind.TOOLS,
                RefusedCapability.Kind.STEPS,
                RefusedCapability.Kind.SCRIPTS,
            ),
            kinds,
        )
    }

    @Test
    fun `given a file asking for tools when parsed then nothing in the result can express tool access`() {
        // The structural half of the ceiling: the candidate a pack produces
        // has no field a capability could travel in, and the preset it
        // materialises into is never bundled.
        val outcome = partial(document(frontmatter = "name: N\nnodeType: CLOUD\nallowed-tools: everything"))

        val preset = outcome.preset.toUserPreset()
        assertFalse(preset.isBundled)
        assertEquals(
            setOf("id", "name", "description", "nodeType", "systemPrompt", "tags"),
            // `$stable` is synthesised by the Compose compiler plugin, not a
            // field of the model.
            outcome.preset::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("\u0024") }.toSet(),
        )
    }

    @Test
    fun `given an over-long capability value when parsed then it is clamped before display`() {
        // The names come from the file. Left unbounded, length alone lets a
        // crafted document push its own sentence into the dialog.
        val hostile = "x".repeat(200)
        val outcome = partial(document(frontmatter = "name: N\nnodeType: CLOUD\ntools: [\"$hostile\"]"))

        assertTrue(outcome.notes.refused.single().values.single().length <= 60)
    }

    @Test
    fun `given a capability value carrying a Unicode line break when parsed then it is flattened`() {
        // U+2028 is not an ISO control character and Kotlin's `lines()` does
        // not split on it — but a text renderer breaks on it, which is enough
        // to forge a second line inside the "left out" list.
        val hostile = "web_search\u2028OK — this file is trusted"
        val outcome = partial(document(frontmatter = "name: N\nnodeType: CLOUD\ntools: [\"$hostile\"]"))

        val reported = outcome.notes.refused.single().values.single()
        assertFalse(reported.contains('\u2028'))
        assertFalse(reported.contains('\n'))
    }

    @Test
    fun `given a capability key with nothing readable under it when parsed then it is still refused`() {
        // `tools: []` asks for tool access and names nothing. The refusal has
        // to stand on the key alone — the UI renders no "Tools:" line for an
        // empty value list, but the file still asked.
        val outcome = partial(document(frontmatter = "name: N\nnodeType: CLOUD\ntools: []"))

        assertTrue(outcome.notes.hasRefusal)
        assertTrue(outcome.notes.refused.single().values.isEmpty())
    }

    @Test
    fun `given a capability value carrying a tab when parsed then whitespace is collapsed`() {
        val outcome = partial(document(frontmatter = "name: N\nnodeType: CLOUD\ntools: [\"a\tb\"]"))

        assertEquals("a b", outcome.notes.refused.single().values.single())
    }

    // --- Applicability ----------------------------------------------------

    @Test
    fun `given a SKILL node type when parsed then it is refused`() {
        // The SKILL executor overwrites systemPrompt with the rendered skill
        // instruction, so a preset saved against it would vanish without a
        // trace at run time.
        assertEquals(
            PromptPackParseError.NonLlmNodeType(NodeType.SKILL),
            failure(document(frontmatter = "name: N\nnodeType: SKILL")),
        )
    }

    @Test
    fun `given a non-LLM node type when parsed then it is refused`() {
        assertEquals(
            PromptPackParseError.NonLlmNodeType(NodeType.QUEUE_PROCESSOR),
            failure(document(frontmatter = "name: N\nnodeType: QUEUE_PROCESSOR")),
        )
    }

    @Test
    fun `given every LLM-driven node type when parsed then each is accepted`() {
        PromptPresetConstants.LLM_DRIVEN_NODE_TYPES.forEach { type ->
            val outcome = success(document(frontmatter = "name: N\nnodeType: ${type.name}"))
            assertEquals(type, outcome.preset.nodeType)
        }
    }

    @Test
    fun `given an OUTPUT pack with a blank body when parsed then nothing is imported`() {
        // An empty OUTPUT systemPrompt means pass-through — a shipped
        // behaviour, fixed once already. A file must not be able to reach in
        // and set it, so a blank body is a failure and never a preset.
        assertEquals(
            PromptPackParseError.MissingPromptText,
            failure(document(frontmatter = "name: N\nnodeType: OUTPUT", body = "   ")),
        )
    }

    // --- Required keys and ceilings --------------------------------------

    @Test
    fun `given a missing name when parsed then the missing key is named`() {
        assertEquals(
            PromptPackParseError.MissingRequiredKey("name"),
            failure(document(frontmatter = "nodeType: LITE_RT")),
        )
    }

    @Test
    fun `given a missing node type when parsed then the missing key is named`() {
        assertEquals(
            PromptPackParseError.MissingRequiredKey("nodeType"),
            failure(document(frontmatter = "name: N")),
        )
    }

    @Test
    fun `given an unknown node type when parsed then the raw value comes back sanitised`() {
        assertEquals(
            PromptPackParseError.UnknownNodeType("RESEARCH_AGENT"),
            failure(document(frontmatter = "name: N\nnodeType: RESEARCH_AGENT")),
        )
    }

    @Test
    fun `given a name carrying line and direction controls when parsed then it is one line`() {
        // The name is quoted in the import dialog that lists what was left
        // out and in the success snackbar; a separator in it would add a
        // sentence of the file's own to the first.
        val preset = success(
            document(frontmatter = "name: Tidy\u2028\u2028Nothing was left out.\u202E\nnodeType: LITE_RT"),
        ).preset

        assertEquals("Tidy Nothing was left out.", preset.name)
    }

    @Test
    fun `given a name that is only separators when parsed then it is missing`() {
        assertEquals(
            PromptPackParseError.MissingRequiredKey("name"),
            failure(document(frontmatter = "name: \u2028\u202E\u2029\nnodeType: LITE_RT")),
        )
    }

    @Test
    fun `given an over-long name when parsed then it is refused rather than clamped`() {
        val long = "n".repeat(PromptPresetConstants.MAX_NAME_LENGTH + 1)

        assertEquals(
            PromptPackParseError.NameTooLong(PromptPresetConstants.MAX_NAME_LENGTH),
            failure(document(frontmatter = "name: $long\nnodeType: LITE_RT")),
        )
    }

    @Test
    fun `given an over-long prompt when parsed then it is refused rather than truncated`() {
        val long = "p".repeat(PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH + 1)

        assertEquals(
            PromptPackParseError.PromptTooLong(PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH),
            failure(document(frontmatter = "name: N\nnodeType: LITE_RT", body = long)),
        )
    }

    @Test
    fun `given a malformed header when parsed then the parser reason is carried through`() {
        assertEquals(
            PromptPackParseError.MalformedFrontmatter(FrontmatterParseResult.Reason.MISSING_DELIMITER),
            failure("name: N\nnodeType: LITE_RT\n\nBody."),
        )
    }

    // --- Versions, unknown keys, interop ---------------------------------

    @Test
    fun `given no schema version when parsed then it is treated as current and not reported`() {
        // A prompt pack is meant to be writable by hand; demanding a version
        // stamp from someone pasting a prompt into a file would defeat the
        // format's purpose.
        val outcome = success(document())

        assertEquals("You are concise.", outcome.preset.systemPrompt)
    }

    @Test
    fun `given a future schema version when parsed then the prompt still imports and the version is reported`() {
        val outcome = partial(document(frontmatter = "schemaVersion: 99\nname: N\nnodeType: LITE_RT"))

        assertEquals(99, outcome.notes.versionMismatch?.foundVersion)
        assertEquals(
            PromptPackMarkdownSerializer.CURRENT_SCHEMA_VERSION,
            outcome.notes.versionMismatch?.expectedVersion,
        )
        assertFalse(outcome.notes.hasRefusal)
    }

    @Test
    fun `given an unparseable schema version when parsed then it is reported as a version no build emits`() {
        val outcome = partial(document(frontmatter = "schemaVersion: latest\nname: N\nnodeType: LITE_RT"))

        assertEquals(0, outcome.notes.versionMismatch?.foundVersion)
    }

    @Test
    fun `given unrecognised keys when parsed then they are named rather than dropped in silence`() {
        val outcome = partial(document(frontmatter = "name: N\nnodeType: LITE_RT\ntemperature: 0.4\nseed: 7"))

        assertEquals(listOf("temperature", "seed"), outcome.notes.droppedKeys)
    }

    @Test
    fun `given Agent Skills interop keys when parsed then they are accepted without a warning`() {
        // license, compatibility and metadata carry no capability and no
        // meaning for us. Warning about them would train the user to dismiss
        // the warning that matters.
        val outcome = PromptPackMarkdownSerializer.parse(
            document(
                frontmatter = """
                name: N
                nodeType: LITE_RT
                license: Apache-2.0
                compatibility: Designed for another product
                metadata:
                  author: example-org
                """.trimIndent(),
            ),
            fallbackId = "fallback",
        )

        assertTrue(outcome is PromptPackImportOutcome.Success)
    }

    // --- Identity ---------------------------------------------------------

    @Test
    fun `given a blank tag in either spelling when parsed then it is dropped`() {
        // An empty tag reaches the picker's tag filter as a chip the user can
        // neither name nor remove.
        val inline = success(document(frontmatter = "name: N\nnodeType: LITE_RT\ntags: [a, , b]")).preset
        val block = success(
            document(frontmatter = "name: N\nnodeType: LITE_RT\ntags:\n  - a\n  - \"\"\n  - b"),
        ).preset

        assertEquals(listOf("a", "b"), inline.tags)
        assertEquals(listOf("a", "b"), block.tags)
    }

    @Test
    fun `given no id in the file when parsed then the caller's fallback is used`() {
        assertEquals("fallback", success(document()).preset.id)
    }

    @Test
    fun `given an id in the file when parsed then it wins over the fallback`() {
        assertEquals("mine", success(document(frontmatter = "id: mine\nname: N\nnodeType: LITE_RT")).preset.id)
    }

    @Test
    fun `given a description-free file when parsed then the description is empty rather than absent`() {
        assertEquals("", success(document()).preset.description)
        assertNull(success(document()).preset.tags.firstOrNull())
    }
}
