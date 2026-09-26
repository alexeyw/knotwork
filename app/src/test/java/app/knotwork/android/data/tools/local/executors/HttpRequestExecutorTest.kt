package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [HttpRequestExecutor], the outbound HTTP tool.
 *
 * Uses MockWebServer (the OkHttp 5 `mockwebserver3` namespace) for the happy
 * and truncation paths, and asserts `requestCount == 0` for every refusal that
 * must short-circuit before a byte leaves the device (empty allowlist,
 * non-allowlisted host, cleartext public host, credential leak).
 */
class HttpRequestExecutorTest {

    private lateinit var server: MockWebServer
    private val settings = mockk<SettingsRepository>()
    private val apiKeys = mockk<ApiKeyRepository>(relaxed = true)
    private val client = OkHttpClient()
    private val networkActivity = NetworkActivityTrackerImpl()

    private lateinit var executor: HttpRequestExecutor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // No stored credentials by default.
        every { apiKeys.getOpenAIKey() } returns flowOf(null)
        every { apiKeys.getAnthropicKey() } returns flowOf(null)
        every { apiKeys.getGoogleKey() } returns flowOf(null)
        every { apiKeys.getDeepSeekKey() } returns flowOf(null)
        every { settings.httpToolMaxResponseBytes } returns
            flowOf(SettingsDefaults.HTTP_TOOL_MAX_RESPONSE_BYTES_DEFAULT)
        executor = HttpRequestExecutor(client, settings, apiKeys, networkActivity)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun allow(vararg domains: String) {
        every { settings.allowedHttpDomains } returns flowOf(domains.toList())
    }

    private suspend fun run(args: String): String = executor.execute(args, ToolExecutionContext.EMPTY)

    @Test
    fun `given empty allowlist when execute then refuses without a request`() = runTest {
        allow()
        val result = run("""{"method":"GET","url":"${server.url("/")}"}""")
        assertTrue(result.contains("no allowed domains"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given non-allowlisted host when execute then refuses without a request`() = runTest {
        allow("example.com")
        val result = run("""{"method":"GET","url":"${server.url("/data")}"}""")
        assertTrue(result.contains("not in the allowlist"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given unsupported method when execute then refuses`() = runTest {
        allow(server.hostName)
        val result = run("""{"method":"PATCH","url":"${server.url("/")}"}""")
        assertTrue(result.contains("unsupported method"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given cleartext public host when execute then refuses with https message`() = runTest {
        allow("example.com")
        val result = run("""{"method":"GET","url":"http://example.com/x"}""")
        assertTrue(result.contains("only https"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given allowlisted GET when execute then returns status and body`() = runTest {
        allow(server.hostName)
        server.enqueue(MockResponse.Builder().code(200).body("hello world").build())

        val result = run("""{"method":"GET","url":"${server.url("/ok")}"}""")

        assertTrue(result.contains("HTTP 200"))
        assertTrue(result.contains("hello world"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `given an allowlisted request when execute then the privacy indicator records it`() = runTest {
        allow(server.hostName)
        server.enqueue(MockResponse.Builder().code(200).body("hello world").build())

        run("""{"method":"GET","url":"${server.url("/ok")}"}""")

        assertNotNull("the request left without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given a request refused before sending when execute then nothing is recorded`() = runTest {
        allow("example.com")

        run("""{"method":"GET","url":"${server.url("/data")}"}""")

        assertEquals(0, server.requestCount)
        assertNull(networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given response larger than cap when execute then truncates with marker`() = runTest {
        allow(server.hostName)
        every { settings.httpToolMaxResponseBytes } returns flowOf(5L)
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())

        val result = run("""{"method":"GET","url":"${server.url("/big")}"}""")

        assertTrue("body should be cut at the cap", result.contains("01234"))
        assertFalse("bytes past the cap must not be returned", result.contains("56789"))
        assertTrue(result.contains("truncated at 5 bytes"))
    }

    @Test
    fun `given a stored key in a header when execute then refuses without a request`() = runTest {
        allow(server.hostName)
        every { apiKeys.getOpenAIKey() } returns flowOf("sk-leak-123")

        val result = run(
            """{"method":"GET","url":"${server.url("/")}","headers":{"Authorization":"Bearer sk-leak-123"}}""",
        )

        assertTrue(result.contains("stored credential"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given a stored key in the url query when execute then refuses without a request`() = runTest {
        allow(server.hostName)
        every { apiKeys.getOpenAIKey() } returns flowOf("sk-leak-123")

        // The easiest GET exfil channel: a key smuggled into the query string of
        // an allowlisted host. Must be refused before any socket opens.
        val result = run("""{"method":"GET","url":"${server.url("/log?k=sk-leak-123")}"}""")

        assertTrue(result.contains("stored credential"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given a stored key as a header name or percent-encoded when execute then refuses without a request`() =
        runTest {
            allow(server.hostName)
            every { apiKeys.getOpenAIKey() } returns flowOf("sk-leak-123")
            val url = server.url("/log")

            // As a header NAME, percent-encoded in the query, percent-encoded in a
            // form body — each ships the key while no header value holds it verbatim.
            val attempts = listOf(
                """{"method":"GET","url":"$url","headers":{"sk-leak-123":"1"}}""",
                """{"method":"GET","url":"$url?k=sk%2Dleak%2D123"}""",
                """{"method":"POST","url":"$url","body":"k=sk%2Dleak%2D123"}""",
            )
            for (args in attempts) {
                val result = run(args)
                assertTrue("$args → $result", result.contains("stored credential"))
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `given a Host header in the arguments when execute then refuses without a request`() = runTest {
        // The allowlist checks the URL's host; a Host header chooses the virtual
        // host behind that address instead — and so do other headers the transport owns.
        allow(server.hostName)

        for (header in listOf("Host", "host", "Transfer-Encoding", "Content-Length", "Connection")) {
            val result = run("""{"method":"GET","url":"${server.url("/")}","headers":{"$header":"x"}}""")
            assertTrue("$header → $result", result.startsWith("Error:") && result.contains(header))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given a response that trickles when the caller is cancelled then execute returns promptly`() = runBlocking {
        allow(server.hostName)
        server.enqueue(MockResponse.Builder().body("x".repeat(200)).throttleBody(1, 1, TimeUnit.SECONDS).build())

        val job = launch(Dispatchers.IO) { run("""{"method":"GET","url":"${server.url("/slow")}"}""") }
        delay(1_500)
        val started = System.nanoTime()
        // Stop has to end the call, not wait for the body or a 60 s idle timeout.
        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun `given a response that trickles past the call deadline when execute then it ends with an error`() =
        runBlocking {
            allow(server.hostName)
            server.enqueue(MockResponse.Builder().body("x".repeat(30)).throttleBody(1, 1, TimeUnit.SECONDS).build())
            val deadlined = HttpRequestExecutor(client, settings, apiKeys, networkActivity, callDeadlineMs = 1_000L)

            val result = withTimeout(10_000) {
                withContext(Dispatchers.IO) {
                    deadlined.execute("""{"method":"GET","url":"${server.url("/slow")}"}""", ToolExecutionContext.EMPTY)
                }
            }

            assertTrue(result, result.startsWith("Error: request to"))
        }

    @Test
    fun `given a stored key in the body when execute then refuses without a request`() = runTest {
        allow(server.hostName)
        every { apiKeys.getAnthropicKey() } returns flowOf("sk-ant-secret")

        val result = run(
            """{"method":"POST","url":"${server.url("/")}","body":"payload=sk-ant-secret"}""",
        )

        assertTrue(result.contains("stored credential"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given a redirect leaving the allowlist when execute then aborts`() = runTest {
        allow(server.hostName)
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "https://evil.example.org/steal").build(),
        )

        val result = run("""{"method":"GET","url":"${server.url("/start")}"}""")

        assertTrue(result.contains("redirect blocked"))
        // Only the first hop was issued; the off-allowlist target was never contacted.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `given an in-allowlist redirect when execute then follows it`() = runTest {
        allow(server.hostName)
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/final").build())
        server.enqueue(MockResponse.Builder().code(200).body("arrived").build())

        val result = run("""{"method":"GET","url":"${server.url("/start")}"}""")

        assertTrue(result.contains("HTTP 200"))
        assertTrue(result.contains("arrived"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `given malformed argument json when execute then refuses`() = runTest {
        allow(server.hostName)
        val result = run("not json")
        assertTrue(result.contains("must be a JSON object"))
    }
}
