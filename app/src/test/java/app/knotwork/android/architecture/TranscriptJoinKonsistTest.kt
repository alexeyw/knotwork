package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the idiom that let stored content forge a turn of its own inside a
 * prompt: a speaker label and a message body spliced into one string template,
 * `"${message.role.name}: ${message.content}"`.
 *
 * **Why it is a defect, not a style.** A body containing a line break followed by
 * `User: …` produces a line byte-identical to a real user turn, so a prompt whose
 * only defence is "extract what the user stated" is read against text a web page
 * wrote. Every transcript the app builds for a model goes through
 * `ChatTranscript.turn`, which indents continuation lines so that only a real turn
 * can open a line with a label. This test fails when the idiom reappears anywhere
 * in production code, which is how the three original sites were written.
 *
 * **What it cannot see.** It matches the idiom as it was written — a template
 * expression mentioning `role` directly followed by a colon. A join spelled
 * differently (`role.name + ": " + content`, a label held in another variable) is
 * not matched; review is the net for those. It is a census of one spelling, not a
 * proof that no transcript is built by hand.
 */
class TranscriptJoinKonsistTest {

    @Test
    fun `no production code splices a role label and a body into one template`() {
        val offenders = ProductionSources.code
            .mapValues { (_, code) -> ROLE_LABEL_TEMPLATE.findAll(code).map { it.value.trim() }.toList() }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "a string template joins a role label to a message body. Build prompt transcripts with " +
                "ChatTranscript.turn(label, content), which keeps content from opening a forged turn.",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `no production code renders a list entry from stored text in one template`() {
        // The same idiom in the lists: a numbered entry (`"${index + 1}. ${chunk.text}"`)
        // or a `name — description` line built by interpolation lets the text open a
        // forged entry. `$MEMORY_SUMMARY`, `$TOOLS` and the auto-select tool list were
        // written that way; render entries with ChatTranscript.entry(prefix, text).
        val offenders = ProductionSources.code
            .mapValues { (_, code) -> LIST_ENTRY_TEMPLATE.findAll(code).map { it.value.trim() }.toList() }
            .filterValues { it.isNotEmpty() }

        assertEquals(emptyMap<String, List<String>>(), offenders)
    }

    @Test
    fun `the census reads the production sources it claims to`() {
        // Keeps the rule above from passing vacuously on an empty or wrong tree.
        assertTrue(
            "ChatTranscript not found among ${ProductionSources.code.size} production files",
            ProductionSources.code.keys.any { it.endsWith("/domain/prompt/ChatTranscript.kt") },
        )
    }

    private companion object {
        /**
         * `${…role…}:` or `$role:` inside a template — the label half of a
         * `label: body` transcript line built by interpolation.
         */
        val ROLE_LABEL_TEMPLATE = Regex("""\${'$'}\{[^}]*\brole\b[^}]*}\s*:|\${'$'}role\b\s*:""")

        /**
         * A list entry built by interpolating stored text after its prefix: a
         * numbered one (`${index + 1}. ${x.text}`, `.content`, `.description`,
         * `.output`) or a `${name} — ${description}` one. A numbered **label** —
         * `${index + 1}. ${message.role.name}`, handed to `ChatTranscript.turn` —
         * is app-controlled and not matched.
         */
        val LIST_ENTRY_TEMPLATE = Regex(
            """\${'$'}\{index \+ 1}\. \${'$'}\{[^}]*\.(text|content|description|output)}|""" +
                """\${'$'}\{[^}]*name} — \${'$'}\{[^}]*description}""",
        )
    }
}
