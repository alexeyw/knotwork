package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.chat.HitlConfirmationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the sentences that describe the approval gate's mechanics to the code
 * that implements them.
 *
 * Both were wrong at the same time, in the direction a reader trusts most: the
 * guide named a confirmation word the field does not accept, and two documents
 * said every `http_request` stops for a confirmation while one setting let a
 * `GET` through. A reader following either would have been misled about the
 * app's strongest consent signal.
 *
 * **Reading the documents from a test.** They sit outside every source set, so
 * `app/build.gradle.kts` declares each as an input of the unit-test task —
 * without that, an edit to the text answers from a cached run.
 */
class ApprovalPolicyDocumentsTest {

    @Test
    fun `given the destructive confirmation when the guide describes it then it names the word the field accepts`() {
        val section = section(read(USER_GUIDE), "### Approving a tool call")
        val sentence = sentences(section).singleOrNull { it.contains("typing") }
            ?: error("'Approving a tool call' has no single sentence about typing the confirmation")

        assertTrue(
            "The guide must name the word the destructive confirmation accepts, was: $sentence",
            sentence.contains("**${ChatHomeHitlDelegate.DESTRUCTIVE_TYPED_CONFIRM_WORD}**"),
        )
    }

    @Test
    fun `given the two copies of the confirmation word when compared then they agree`() {
        // The delegate decides whether Approve is applied; the catalog card
        // decides whether the button is enabled. Two words would leave a button
        // that lights up for an answer the gate then rejects.
        assertEquals(
            HitlConfirmationState.DESTRUCTIVE_CONFIRM_WORD,
            ChatHomeHitlDelegate.DESTRUCTIVE_TYPED_CONFIRM_WORD,
        )
    }

    @Test
    fun `given http_request when the threat model describes its confirmation then it names the Never exception`() {
        val section = section(read(SECURITY), "### Outbound HTTP and the exfiltration chain")

        assertTrue(
            "SECURITY.md § Outbound HTTP must say that *Never* lets a GET through",
            section.contains("*$NEVER_OPTION*"),
        )
    }

    @Test
    fun `given http_request when the privacy policy describes its confirmation then it names the Never exception`() {
        val paragraph = sentences(section(read(PRIVACY), "### 3.4 Outbound requests from tools"))
            .dropWhile { !it.contains("`http_request`") }
            .joinToString(" ")

        assertTrue(
            "PRIVACY.md § 3.4 must say that *Never* lets an http_request GET through",
            paragraph.contains("*$NEVER_OPTION*"),
        )
    }

    private fun read(path: String): String = File(repositoryRoot(), path).readText()

    /** The text from [heading] to the next heading of the same or a higher level. */
    private fun section(markdown: String, heading: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trim() == heading }
        check(start >= 0) { "heading '$heading' not found" }
        val level = heading.takeWhile { it == '#' }.length
        val end = (start + 1 until lines.size).firstOrNull { index ->
            val hashes = lines[index].takeWhile { it == '#' }.length
            hashes in 1..level && lines[index].getOrNull(hashes) == ' '
        } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n")
    }

    /** Sentences of [text] with line breaks folded, so a sentence wrapped across lines stays whole. */
    private fun sentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?]) ")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun repositoryRoot(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir: File? = File(workingDir)
        while (dir != null) {
            if (File(dir, SECURITY).isFile && File(dir, USER_GUIDE).isFile) return dir
            dir = dir.parentFile
        }
        error("$SECURITY not found walking up from $workingDir")
    }

    private companion object {
        const val USER_GUIDE = "docs/user-guide.md"
        const val SECURITY = "SECURITY.md"
        const val PRIVACY = "PRIVACY.md"

        /** Label of the *Approve tool calls* option that quiets the non-destructive prompts. */
        const val NEVER_OPTION = "Never"
    }
}
