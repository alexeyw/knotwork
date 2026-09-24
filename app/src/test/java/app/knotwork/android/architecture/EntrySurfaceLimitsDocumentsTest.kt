package app.knotwork.android.architecture

import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.usecases.RunRateCeiling
import app.knotwork.android.domain.usecases.automation.HandleExternalAutomationRequestUseCase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins every number the public documents quote about the entry surfaces' limits to
 * the constant that enforces it.
 *
 * A caller writes a profile against the published contract, and a user reads the
 * share limit in the guide; a number that drifted from the code fails both
 * silently. The generated contract tables cannot carry these numbers themselves —
 * the generator renders a KDoc reference as the constant's name — so the sentences
 * are written out by hand and held here instead.
 */
class EntrySurfaceLimitsDocumentsTest {

    private val maxId = ExternalAutomationContract.MAX_REQUEST_ID_LENGTH
    private val maxAddress = ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH
    private val maxJournaled = HandleExternalAutomationRequestUseCase.MAX_JOURNALED_VALUE_LENGTH
    private val shareLimit = RunRateCeiling.SHARE.limitPerWindow

    @Test
    fun `given the published contract then it quotes the code's value ceilings`() {
        val contract = folded("docs/external-automation.md")

        assertQuotes(
            contract,
            "`request_id` may be at most $maxId characters, `return_action` and `return_package` at most $maxAddress",
        )
        // The generated refusal-reason row, whose KDoc spells the numbers out.
        assertQuotes(
            contract,
            "the request id beyond $maxId characters, or the callback action or package beyond $maxAddress",
        )
    }

    @Test
    fun `given the threat model then it quotes the code's limits`() {
        val security = folded("SECURITY.md")

        assertQuotes(
            security,
            "The request id is at most $maxId characters and the callback's action and package at most $maxAddress",
        )
        assertQuotes(security, "The journal keeps at most $maxJournaled characters")
        assertQuotes(security, "at most $shareLimit shares an hour start a run")
    }

    @Test
    fun `given the privacy policy then it quotes how many share times are kept`() {
        assertQuotes(folded("PRIVACY.md"), "the times of your most recent shares (at most $shareLimit, no content)")
    }

    @Test
    fun `given the user guide and the in-app key help then they quote the code's limits`() {
        assertQuotes(folded("docs/user-guide.md"), "At most **$shareLimit shares an hour** start a run")

        val keyHelp = File(ProductionSources.moduleDirectory(), "src/main/res/values/strings_external_automation.xml")
            .readText()
        assertQuotes(keyHelp, "Your own id, up to $maxId characters.")
        assertQuotes(keyHelp, "Action to send the answer with, up to $maxAddress characters.")
        assertQuotes(keyHelp, "Package to deliver the answer to, up to $maxAddress characters.")
    }

    private fun assertQuotes(text: String, sentence: String) {
        assertTrue("expected the document to say: $sentence", text.contains(sentence))
    }

    /** A repository document with every run of whitespace folded to one space. */
    private fun folded(path: String): String = File(repositoryRoot(), path).readText().replace(Regex("\\s+"), " ")

    private fun repositoryRoot(): File {
        var dir: File? = ProductionSources.moduleDirectory().absoluteFile
        while (dir != null) {
            if (File(dir, "SECURITY.md").isFile) return dir
            dir = dir.parentFile
        }
        error("SECURITY.md not found above the module directory")
    }
}
