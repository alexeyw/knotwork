package app.knotwork.android.presentation.ui.common

import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.design.components.misc.TestProbeTone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Every state of a test row resolved into its words — the copy the design hand-off fixed, per
 * subject: a hosted provider, a server the user runs, an MCP server.
 */
@RunWith(RobolectricTestRunner::class)
class TestSubjectTest {

    private val context = RuntimeEnvironment.getApplication()
    private val groq = TestSubject.Hosted("Groq")
    private val own = TestSubject.OwnServer("OpenAI-compatible server")

    private fun ConnectionTestState.ui(subject: TestSubject, elapsed: Long = 0) =
        toTestProbeUi(context, subject, elapsed)

    private fun failed(failure: ConnectionFailure, subject: TestSubject, host: String = "api.groq.com") =
        ConnectionTestState.Finished(ConnectionCheckResult.Failed(host, failure)).ui(subject).status

    @Test
    fun `given each reason the row is disabled then it says what to fix`() {
        fun disabled(reason: ConnectionRefusal, subject: TestSubject = own) =
            ConnectionTestState.Disabled(reason).ui(subject)

        assertEquals("Enter the server address to test.", disabled(ConnectionRefusal.MissingAddress).status)
        assertEquals(
            "Enter the server URL to test.",
            disabled(ConnectionRefusal.MissingAddress, TestSubject.Mcp).status,
        )
        assertEquals("Fix the address to test.", disabled(ConnectionRefusal.NotAnAddress).status)
        assertEquals("Enter the API key to test.", disabled(ConnectionRefusal.MissingKey, groq).status)
        assertEquals(
            "Approve the unencrypted connection above to test.",
            disabled(AddressRefusal.CleartextNeedsApproval("http://10.0.0.2")).status,
        )
        assertEquals(
            "This address is not allowed. The reason is above.",
            disabled(AddressRefusal.PublicCleartext("203.0.113.7")).status,
        )
        assertEquals(TestProbeTone.Disabled, disabled(ConnectionRefusal.MissingKey).tone)
        assertEquals("Test", disabled(ConnectionRefusal.MissingKey).actionLabel)
    }

    @Test
    fun `given an idle row then it says what the test does and what it costs`() {
        assertEquals(
            "Asks for the model list. Sends no prompt and spends no tokens.",
            ConnectionTestState.Idle.ui(groq).status,
        )
        assertEquals(
            "Connects with the values above and lists the tools. Saves nothing.",
            ConnectionTestState.Idle.ui(TestSubject.Mcp).status,
        )
    }

    @Test
    fun `given a running test then the seconds show from three on, and TalkBack hears the start once`() {
        val running = ConnectionTestState.Running("api.groq.com", TestTimeSource().markNow())

        assertEquals("Asking api.groq.com for its models", running.ui(groq, elapsed = 2).status)
        assertEquals("Asking api.groq.com for its models · 3 s", running.ui(groq, elapsed = 3).status)
        assertEquals(
            "Connecting to mcp.example.com · 7 s",
            running.copy(host = "mcp.example.com").ui(TestSubject.Mcp, 7).status,
        )
        assertEquals("Testing connection", running.ui(groq, elapsed = 9).accessibleStatus)
        assertEquals("Cancel", running.ui(groq).actionLabel)
    }

    @Test
    fun `given a reachable server then it counts models or tools, and says what to do with none`() {
        fun reachable(n: Int, subject: TestSubject) =
            ConnectionTestState.Finished(ConnectionCheckResult.Reachable(List(n) { "m$it" })).ui(subject)

        assertEquals("Connected · 412 models", reachable(412, groq).status)
        assertEquals("Connected · 1 model", reachable(1, own).status)
        assertEquals(
            "Connected · no models. The server is up but serves none yet: load one on it, then test again.",
            reachable(0, own).status,
        )
        assertEquals("Connected · 6 tools", reachable(6, TestSubject.Mcp).status)
        assertEquals("Test again", reachable(1, own).actionLabel)
    }

    @Test
    fun `given a refusal then it opens with Not sent and names the rule`() {
        fun refused(refusal: ConnectionRefusal, subject: TestSubject) =
            ConnectionTestState.Finished(ConnectionCheckResult.Refused(refusal)).ui(subject)

        val blocked = refused(ConnectionRefusal.BlockedByLocalOnlyMode, groq)
        assertEquals("Not sent.", blocked.head)
        assertEquals(TestProbeTone.Refused, blocked.tone)
        assertEquals(
            "Block network from local model is on, and Groq is on the internet. To use it, turn the " +
                "setting off in Settings → Tools & workspace.",
            blocked.status,
        )
        assertEquals(
            "203.0.113.7 is on the public internet and the address is unencrypted. Use https://.",
            refused(AddressRefusal.PublicCleartext("203.0.113.7"), own).status,
        )
        assertEquals(
            "Block network from local model allows only this device and private IP addresses, and " +
                "gpu.lan is neither. Use the server’s IP, or change the setting in Settings → Tools & workspace.",
            refused(AddressRefusal.HostNotLocal("gpu.lan"), own).status,
        )
    }

    @Test
    fun `given a rejected key then the action depends on who rejected it and whether one was sent`() {
        assertEquals(
            "Key rejected (401). Paste it again, or make a new one in your Groq account.",
            failed(ConnectionFailure.Unauthorized(401, keySent = true), groq),
        )
        assertEquals(
            "Key rejected (403). Check the key the server expects.",
            failed(ConnectionFailure.Unauthorized(403, keySent = true), own),
        )
        assertEquals(
            "The server wants a key (401). Enter it under API key.",
            failed(ConnectionFailure.Unauthorized(401, keySent = false), own),
        )
        assertEquals(
            "Credentials rejected (401). Check the token under Authentication.",
            failed(ConnectionFailure.Unauthorized(401, keySent = true), TestSubject.Mcp),
        )
    }

    @Test
    fun `given each failure after sending then it says what happened and one action`() {
        assertEquals(
            "Nothing at http://10.0.0.2:8000/models (404). Most servers need the address to end in /v1.",
            failed(ConnectionFailure.NotFound("http://10.0.0.2:8000/models"), own, "10.0.0.2"),
        )
        assertEquals(
            "Rate limited. Groq asked to wait 7 s. Test again after that.",
            failed(ConnectionFailure.RateLimited(7.seconds), groq),
        )
        assertEquals("Rate limited by Groq. Test again in a minute.", failed(ConnectionFailure.RateLimited(null), groq))
        assertEquals(
            "Server error (503) at Groq. The problem is on their side: test again in a few minutes.",
            failed(ConnectionFailure.ServerError(503), groq),
        )
        assertEquals(
            "Server error (500). The server answered and then failed: check its log, then test again.",
            failed(ConnectionFailure.ServerError(500), own),
        )
        assertEquals(
            "Unexpected answer (418): “teapot”. Check that the address is the API, not a web page — or type " +
                "the model id below.",
            failed(ConnectionFailure.UnexpectedAnswer(418, "teapot"), own),
        )
        assertEquals("The server answered, but not with a model list.", failed(ConnectionFailure.NoModelList, own))
    }

    @Test
    fun `given a failure below HTTP then the host is named, and the advice fits the subject`() {
        assertEquals(
            "Can’t reach api.groq.com. Check that this phone is online, then test again.",
            failed(ConnectionFailure.UnknownHost, groq),
        )
        assertEquals(
            "Can’t find gpu.lan. Check the spelling of the address.",
            failed(ConnectionFailure.UnknownHost, own, "gpu.lan"),
        )
        assertEquals(
            "10.0.0.2 refused the connection. Check that the server is running and listening on that port.",
            failed(ConnectionFailure.ConnectionRefused, own, "10.0.0.2"),
        )
        assertEquals(
            "No answer from 10.0.0.2 in 30 s. Check that it is running and reachable from this phone.",
            failed(ConnectionFailure.ConnectTimeout(30.seconds), own, "10.0.0.2"),
        )
        assertEquals(
            "10.0.0.2 connected, then sent nothing for 60 s. Restart the server, then test again.",
            failed(ConnectionFailure.Silence(60.seconds), own, "10.0.0.2"),
        )
    }

    @Test
    fun `given an MCP failure then it names the endpoint problem`() {
        assertEquals(
            "mcp.example.com accepted the connection but did not finish the MCP handshake in 30 s. Check that " +
                "the URL is the server’s MCP endpoint.",
            failed(ConnectionFailure.HandshakeTimeout(30.seconds), TestSubject.Mcp, "mcp.example.com"),
        )
        assertEquals(
            "mcp.example.com answered, but not as an MCP server. Check the path: it is often /mcp or /sse.",
            failed(ConnectionFailure.NotMcp, TestSubject.Mcp, "mcp.example.com"),
        )
    }

    @Test
    fun `given a refused redirect or anything else then its own words are shown`() {
        assertEquals(
            "Stopped a request to 'x'.",
            failed(ConnectionFailure.RedirectRefused("Stopped a request to 'x'."), own),
        )
        assertEquals(
            "Couldn’t connect: Trust anchor not found",
            failed(ConnectionFailure.Other("Trust anchor not found"), own),
        )
    }
}
