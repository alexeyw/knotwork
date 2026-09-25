package app.knotwork.android.data.logging

import app.knotwork.android.domain.repositories.CrashReportingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException

/**
 * Unit tests for [CrashlyticsTimberTree].
 *
 * Cover: severity filtering (only `WARN` / `ERROR` reach the repository) and
 * what a report may carry — the type and stack frames of every link of a
 * throwable's cause chain and the call site's message *template* with its tag,
 * never the text of a throwable nor the values formatted into the template.
 * PRIVACY §3.5 promises "the stack trace" and nothing of the user's; a
 * throwable's message and a format argument are exactly where the user's
 * paths, file contents and tool arguments travel (an Android `JSONException`
 * ends with the whole document it failed to parse).
 *
 * The tree is exercised through Timber's public API because
 * `Timber.Tree.log` and `isLoggable` are protected by design.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashlyticsTimberTreeTest {

    private val crashReportingRepository = mockk<CrashReportingRepository>(relaxed = true)
    private lateinit var dispatcher: TestDispatcher
    private lateinit var scope: TestScope
    private lateinit var tree: CrashlyticsTimberTree

    @Before
    fun setup() {
        dispatcher = StandardTestDispatcher()
        scope = TestScope(dispatcher)
        tree = CrashlyticsTimberTree(crashReportingRepository, scope)
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    @Test
    fun `verbose info and debug records do not reach the repository`() = runTest {
        coEvery { crashReportingRepository.recordException(any(), any()) } returns Unit

        Timber.v("verbose message")
        Timber.d("debug message")
        Timber.i("info message")
        scope.advanceUntilIdle()

        coVerify(exactly = 0) { crashReportingRepository.recordException(any(), any()) }
    }

    @Test
    fun `given a throwable and a message then the type, frames, template and tag are reported`() = runTest {
        val reported = slot<Throwable>()
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(capture(reported), capture(extras)) } returns Unit
        val throwable = IllegalStateException("boom")

        Timber.tag("Engine").e(throwable, "node crashed")
        scope.advanceUntilIdle()

        assertEquals(IllegalStateException::class.java.name, reported.captured.message)
        assertArrayEquals(throwable.stackTrace, reported.captured.stackTrace)
        assertEquals(mapOf("timber_message" to "node crashed", "timber_tag" to "Engine"), extras.captured)
    }

    @Test
    fun `given a throwable whose text carries a path and file content then neither is reported`() = runTest {
        // What `write_file` with malformed arguments throws on a device: Android's
        // JSONException ends with the entire document, path and content included.
        val reported = slot<Throwable>()
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(capture(reported), capture(extras)) } returns Unit
        val parseError = IllegalArgumentException(
            "Unterminated string at character 58 of {\"path\":\"$USER_PATH\",\"content\":\"$USER_CONTENT",
        )

        Timber.tag(
            "PipelineDebug",
        ).e(parseError, "[NODE_ERR] type=%s id=%s error executing tool: %s", "TOOL", "n1", "write_file")
        scope.advanceUntilIdle()

        assertNoUserText(reported.captured, extras.captured)
    }

    @Test
    fun `given format arguments then only the template is reported`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit

        Timber.w("Workspace preview failed for %s: %s", USER_PATH, "NotFound")
        scope.advanceUntilIdle()

        assertEquals("Workspace preview failed for %s: %s", reported.captured.message)
    }

    @Test
    fun `given format arguments with a throwable then the extras carry the template only`() = runTest {
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(any(), capture(extras)) } returns Unit

        Timber.w(IOException("rename failed"), "Workspace import threw for %s", USER_PATH)
        scope.advanceUntilIdle()

        assertEquals(mapOf("timber_message" to "Workspace import threw for %s"), extras.captured)
    }

    @Test
    fun `given a throwable and no message then its text does not reach the extras either`() = runTest {
        // Timber substitutes the stack trace — whose first line is the throwable's
        // own text — for a missing message.
        val reported = slot<Throwable>()
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(capture(reported), capture(extras)) } returns Unit

        Timber.w(IOException("$USER_PATH (No such file or directory)"))
        scope.advanceUntilIdle()

        assertNoUserText(reported.captured, extras.captured)
        assertEquals(emptyMap<String, String>(), extras.captured)
    }

    @Test
    fun `given a cause chain then every link keeps its type and frames and loses its text`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit
        val cause = IOException("$USER_PATH (Permission denied)")
        val wrapper = RuntimeException("read failed for $USER_PATH", cause)

        Timber.e(wrapper, "embedding failed")
        scope.advanceUntilIdle()

        val chain = generateSequence(reported.captured) { it.cause }.toList()
        assertEquals(listOf(RuntimeException::class.java.name, IOException::class.java.name), chain.map { it.message })
        assertArrayEquals(wrapper.stackTrace, chain[0].stackTrace)
        assertArrayEquals(cause.stackTrace, chain[1].stackTrace)
    }

    @Test
    fun `given a suppressed exception then it is not reported`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit
        val leaking = IllegalStateException("close failed").apply {
            addSuppressed(IOException(LEAKING_PROVIDER_ERROR))
        }

        Timber.e(leaking, "download failed")
        scope.advanceUntilIdle()

        assertNotSame(leaking, reported.captured)
        assertTrue(reported.captured.suppressed.isEmpty())
    }

    @Test
    fun `given a throwable whose message carries a credential then no part of it is reported`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit

        Timber.e(RuntimeException("wrapper", IOException(LEAKING_PROVIDER_ERROR)), "embedding failed")
        scope.advanceUntilIdle()

        val texts = generateSequence(reported.captured) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(texts.none { it.contains(LEAKED_KEY) })
    }

    @Test
    fun `warn without throwable wraps the template in a synthetic exception with its tag`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit

        Timber.tag("TestTag").w("something looks off")
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { crashReportingRepository.recordException(any(), emptyMap()) }
        assertEquals("[TestTag] something looks off", reported.captured.message)
    }

    @Test
    fun `error without tag still produces readable synthetic message`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit

        Timber.e("untagged failure")
        scope.advanceUntilIdle()

        assertEquals("untagged failure", reported.captured.message)
    }

    @Test
    fun `given a template that itself spells out a credential then the backstop still masks it`() = runTest {
        // Templates are literals (TimberMessageTemplateKonsistTest), so this takes a
        // credential written into the source — the redaction stays as a backstop.
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit

        Timber.tag("PipelineDebug").e("[NODE_ERR] error=$LEAKING_PROVIDER_ERROR")
        scope.advanceUntilIdle()

        assertFalse(reported.captured.message.orEmpty().contains(LEAKED_KEY))
    }

    @Test
    fun `given a call-site template that spells out a credential then the extras are masked`() = runTest {
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(any(), capture(extras)) } returns Unit

        Timber.e(IllegalStateException("plain"), "failed with Authorization: Bearer sk-live-SECRET")
        scope.advanceUntilIdle()

        assertFalse(extras.captured.getValue("timber_message").contains("sk-live-SECRET"))
    }

    @Test(timeout = 5_000)
    fun `given a cause chain that loops when an error is logged then the report is still produced`() = runTest {
        val reported = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(reported), any()) } returns Unit
        val first = IllegalStateException(LEAKING_PROVIDER_ERROR)
        val second = RuntimeException("wrapper", first)
        first.initCause(second)

        Timber.e(first, "looping chain")
        scope.advanceUntilIdle()

        assertEquals(IllegalStateException::class.java.name, reported.captured.message)
    }

    /** Asserts that no user path or file content appears in [reported]'s chain or in [extras]. */
    private fun assertNoUserText(reported: Throwable, extras: Map<String, String>) {
        val texts = generateSequence(reported) { it.cause }.mapNotNull { it.message }.toList() + extras.values
        for (text in texts) {
            assertFalse("path reported: $text", text.contains(USER_PATH))
            assertFalse("content reported: $text", text.contains(USER_CONTENT))
        }
    }

    private companion object {
        const val USER_PATH = "notes/diary-2026.md"
        const val USER_CONTENT = "Dear diary"
        const val LEAKED_KEY = "AIzaSyTESTKEY"
        const val LEAKING_PROVIDER_ERROR =
            "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini:streamGenerateContent?alt=sse&key=$LEAKED_KEY]"
    }
}
