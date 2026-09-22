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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException

/**
 * Unit tests for [CrashlyticsTimberTree].
 *
 * Cover: severity filtering (only `WARN` / `ERROR` reach the repository)
 * and routing semantics — explicit throwables are forwarded as-is while
 * message-only records are wrapped in a synthetic exception whose message
 * preserves the original tag/body so Crashlytics still has something to
 * group on — and redaction: no credential quoted in a message, the extras or
 * any link of a cause chain reaches the repository.
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
    fun `error with explicit throwable forwards throwable plus message and tag as extras`() = runTest {
        val throwable = IllegalStateException("boom")
        coEvery { crashReportingRepository.recordException(any(), any()) } returns Unit

        Timber.tag("Engine").e(throwable, "node crashed")
        scope.advanceUntilIdle()

        coVerify(exactly = 1) {
            crashReportingRepository.recordException(
                throwable,
                mapOf("timber_message" to "node crashed", "timber_tag" to "Engine"),
            )
        }
    }

    @Test
    fun `error with throwable but no tag still forwards message extra`() = runTest {
        val throwable = IllegalStateException("boom")
        coEvery { crashReportingRepository.recordException(any(), any()) } returns Unit

        Timber.e(throwable, "untagged failure")
        scope.advanceUntilIdle()

        coVerify(exactly = 1) {
            crashReportingRepository.recordException(
                throwable,
                mapOf("timber_message" to "untagged failure"),
            )
        }
    }

    @Test
    fun `warn without throwable wraps message in synthetic exception with tag`() = runTest {
        val captured = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(captured), any()) } returns Unit

        Timber.tag("TestTag").w("something looks off")
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { crashReportingRepository.recordException(any(), emptyMap()) }
        assertEquals("[TestTag] something looks off", captured.captured.message)
    }

    @Test
    fun `error without tag still produces readable synthetic message`() = runTest {
        val captured = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(captured), any()) } returns Unit

        Timber.e("untagged failure")
        scope.advanceUntilIdle()

        assertEquals("untagged failure", captured.captured.message)
    }

    @Test
    fun `given a throwable whose message carries a credential then the report is scrubbed and keeps its stack`() =
        runTest {
            val captured = slot<Throwable>()
            coEvery { crashReportingRepository.recordException(capture(captured), any()) } returns Unit
            val leaking = IllegalStateException(LEAKING_PROVIDER_ERROR)

            Timber.tag("PipelineDebug").e(leaking, "node failed")
            scope.advanceUntilIdle()

            val reported = captured.captured
            assertFalse("key leaked: ${reported.message}", reported.message.orEmpty().contains(LEAKED_KEY))
            assertTrue(reported.message.orEmpty().contains("key=***"))
            // The type and the frames are what Crashlytics groups and triages on.
            assertTrue(reported.message.orEmpty().contains("IllegalStateException"))
            assertArrayEquals(leaking.stackTrace, reported.stackTrace)
        }

    @Test
    fun `given the credential sits in a cause then every link of the reported chain is scrubbed`() = runTest {
        val captured = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(captured), any()) } returns Unit

        Timber.e(RuntimeException("wrapper", IOException(LEAKING_PROVIDER_ERROR)), "embedding failed")
        scope.advanceUntilIdle()

        val chain = generateSequence(captured.captured) { it.cause }.toList()
        assertEquals(2, chain.size)
        for (link in chain) {
            assertFalse("key leaked: ${link.message}", link.message.orEmpty().contains(LEAKED_KEY))
        }
    }

    @Test
    fun `given a throwable without a credential then the original instance is reported`() = runTest {
        val clean = IllegalStateException("plain failure", IOException("timeout"))
        coEvery { crashReportingRepository.recordException(any(), any()) } returns Unit

        Timber.e(clean, "context")
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { crashReportingRepository.recordException(clean, mapOf("timber_message" to "context")) }
    }

    @Test
    fun `given the log message carries a credential then the extras are scrubbed`() = runTest {
        val extras = slot<Map<String, String>>()
        coEvery { crashReportingRepository.recordException(any(), capture(extras)) } returns Unit

        Timber.e(IllegalStateException("plain"), "failed with Authorization: Bearer sk-live-SECRET")
        scope.advanceUntilIdle()

        val message = extras.captured.getValue("timber_message")
        assertFalse("token leaked: $message", message.contains("sk-live-SECRET"))
        assertTrue(message.contains("Bearer ***"))
    }

    @Test
    fun `given a message-only record carries a credential then the synthetic exception is scrubbed`() = runTest {
        val captured = slot<Throwable>()
        coEvery { crashReportingRepository.recordException(capture(captured), any()) } returns Unit

        Timber.tag("PipelineDebug").e("[NODE_ERR] error=$LEAKING_PROVIDER_ERROR")
        scope.advanceUntilIdle()

        val message = captured.captured.message.orEmpty()
        assertFalse("key leaked: $message", message.contains(LEAKED_KEY))
        assertTrue(message.contains("key=***"))
    }

    private companion object {
        const val LEAKED_KEY = "AIzaSyTESTKEY"
        const val LEAKING_PROVIDER_ERROR =
            "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini:streamGenerateContent?alt=sse&key=$LEAKED_KEY]"
    }
}
