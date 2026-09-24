package app.knotwork.android.presentation.ui.chat.home

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [contentReportIssueUrl].
 *
 * The failure this guards against is invisible at the call site: an over-long
 * URL is not rejected loudly, it is clipped or dropped by the browser, and the
 * user believes they filed a report that nobody can read. So the length bound
 * and the truncation marker are asserted, not assumed.
 */
class ContentReportIssueUrlTest {

    @Test
    fun `given a report when the url is built then it targets the public issue tracker`() {
        val url = contentReportIssueUrl(subject = "Content report: other", body = "body text")

        assertTrue(
            "url must open the repository's new-issue form, was $url",
            url.startsWith("https://github.com/alexeyw/knotwork/issues/new?title="),
        )
    }

    @Test
    fun `given text with spaces and newlines when the url is built then it is percent encoded`() {
        val url = contentReportIssueUrl(subject = "a b", body = "line one\nline two")

        assertFalse("a raw space would break the link: $url", url.contains(' '))
        assertFalse("a raw newline would break the link: $url", url.contains('\n'))
        assertTrue("the body must survive encoding: $url", url.contains("line+one"))
    }

    @Test
    fun `given an oversized body when the url is built then it fits the limit and says it was cut`() {
        val url = contentReportIssueUrl(subject = "Content report: other", body = "x".repeat(OVERSIZED_CHARS))

        assertTrue("url is ${url.length} characters, over the browser-safe bound", url.length <= MAX_URL_CHARS)
        assertTrue("a shortened report must admit it: $url", url.contains("report+truncated+to+fit+the+link"))
    }

    @Test
    fun `given an oversized body when the url is built then it keeps as much as the limit allows`() {
        // A truncation that fits is not enough: a coarse search (halving) also
        // fits, while discarding most of a report the maintainer has to read.
        val url = contentReportIssueUrl(subject = "Content report: other", body = "x".repeat(OVERSIZED_CHARS))

        assertTrue(
            "url is only ${url.length} characters — the report was cut far shorter than the limit requires",
            url.length >= MAX_URL_CHARS * MIN_BUDGET_USED_PERCENT / 100,
        )
    }

    @Test
    fun `given multi byte text when the url is built then the encoded form still fits`() {
        // Percent-encoding expands a multi-byte character up to ninefold, so a
        // body well under the character bound can still blow past it encoded.
        val url = contentReportIssueUrl(subject = "Content report: other", body = "😀".repeat(MULTIBYTE_CHARS))

        assertTrue("url is ${url.length} characters, over the browser-safe bound", url.length <= MAX_URL_CHARS)
    }

    @Test
    fun `given a report when the url is built then the report travels in the link itself`() {
        // Opening this URL is a GET, so everything in it reaches github.com the moment the
        // browser loads the page — whether or not the user then files the issue. This pins
        // the shape the privacy wording below has to describe.
        val url = contentReportIssueUrl(subject = "Content report: other", body = "the flagged reply")

        assertEquals("the flagged reply", url.toHttpUrl().queryParameter("body"))
    }

    @Test
    fun `given the report travels in the link when the privacy policy describes it then it does not wait for a send`() {
        // PRIVACY 4 said the report "travels only if you send it". With the report in the
        // query string that was false: GitHub has the text once the page opens.
        val privacy = File(repositoryRoot(), "PRIVACY.md").readText().replace(Regex("""\s+"""), " ")

        assertFalse(
            "PRIVACY.md still says the flagged-response report waits for a send",
            privacy.contains("travels only if you send it"),
        )
        assertTrue(
            "PRIVACY.md must say that opening the prefilled issue hands the report to GitHub",
            privacy.contains("opening it hands that text to GitHub"),
        )
    }

    /**
     * Gradle runs unit tests from the module directory; `PRIVACY.md` sits one level up
     * and is a declared input of the test task, so an edit to it re-runs this class.
     */
    private fun repositoryRoot(): File {
        val working = File("").absoluteFile
        return if (File(working, "PRIVACY.md").isFile) working else working.parentFile
    }

    private companion object {
        /** Mirrors the private bound in the production file. */
        const val MAX_URL_CHARS = 8000

        /** Comfortably past the bound for a plain-ASCII body. */
        const val OVERSIZED_CHARS = 12_000

        /** Enough emoji that the encoded form exceeds the bound on its own. */
        const val MULTIBYTE_CHARS = 2_000

        /** How much of the URL budget a truncated report must still occupy. */
        const val MIN_BUDGET_USED_PERCENT = 90
    }
}
