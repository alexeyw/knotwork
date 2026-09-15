package app.knotwork.android.domain.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CleartextPolicy] — the rule that replaced the hand-written IP
 * list in `network_security_config.xml`.
 *
 * The manifest now permits cleartext app-wide, so these assertions are the only
 * thing standing between the app and an unencrypted request to the open
 * internet. They are written accordingly: the refusals matter more than the
 * approvals.
 */
class CleartextPolicyTest {

    private val approved = setOf("http://192.168.1.42:11434")

    @Test
    fun `given an https url when classified then the policy does not gate it`() {
        assertEquals(
            CleartextPolicy.Verdict.NotCleartext,
            CleartextPolicy.classify("https://api.openai.com/v1", emptySet()),
        )
    }

    @Test
    fun `given cleartext to a public host when classified then it is refused`() {
        // The case the old fourteen-address list also refused, and the one that
        // must survive the manifest change.
        assertEquals(
            CleartextPolicy.Verdict.PublicRefused,
            CleartextPolicy.classify("http://api.example.com/v1", approved),
        )
    }

    @Test
    fun `given cleartext to a public host when it is in the approved set then it is still refused`() {
        // Approval must not be able to open a hole to the open internet: a public
        // origin can never reach `NeedsApproval`, so it can never be approved.
        val sneaky = setOf("http://api.example.com")
        assertEquals(
            CleartextPolicy.Verdict.PublicRefused,
            CleartextPolicy.classify("http://api.example.com/v1", sneaky),
        )
        assertFalse(CleartextPolicy.isAllowed("http://api.example.com/v1", sneaky))
    }

    @Test
    fun `given cleartext to an unapproved private address when classified then approval is required`() {
        val verdict = CleartextPolicy.classify("http://10.0.0.7:11434/api", emptySet())
        assertEquals(CleartextPolicy.Verdict.NeedsApproval("http://10.0.0.7:11434"), verdict)
        assertFalse(CleartextPolicy.isAllowed("http://10.0.0.7:11434/api", emptySet()))
    }

    @Test
    fun `given cleartext to an approved private address when classified then it is allowed`() {
        // The scenario the product promises and the old config could not express:
        // an arbitrary LAN address, working because the user said so.
        val verdict = CleartextPolicy.classify("http://192.168.1.42:11434/v1/chat", approved)
        assertEquals(CleartextPolicy.Verdict.ApprovedPrivate("http://192.168.1.42:11434"), verdict)
        assertTrue(CleartextPolicy.isAllowed("http://192.168.1.42:11434/v1/chat", approved))
    }

    @Test
    fun `given a different port on an approved host when classified then approval is required again`() {
        // A different port is a different server; approving Ollama must not
        // silently approve whatever else is listening on that machine.
        assertEquals(
            CleartextPolicy.Verdict.NeedsApproval("http://192.168.1.42:8080"),
            CleartextPolicy.classify("http://192.168.1.42:8080/", approved),
        )
    }

    @Test
    fun `given every private range when classified then each needs approval rather than being refused`() {
        // Refusal and "needs approval" are very different outcomes: the first is
        // a dead end, the second is a prompt. Every RFC-1918 range plus loopback
        // must land in the second.
        listOf(
            "http://localhost:11434",
            "http://127.0.0.1:11434",
            "http://10.1.2.3:11434",
            "http://172.16.0.9:11434",
            "http://172.31.255.1:11434",
            "http://192.168.0.5:11434",
        ).forEach { url ->
            assertTrue(
                "$url should be approvable, not refused outright",
                CleartextPolicy.classify(url, emptySet()) is CleartextPolicy.Verdict.NeedsApproval,
            )
        }
    }

    @Test
    fun `given a host just outside the private ranges when classified then it is refused`() {
        // 172.32 is outside 172.16/12 and 11.x is outside 10/8 — the off-by-one
        // neighbours of the ranges above.
        listOf("http://172.32.0.1:11434", "http://11.0.0.1:11434", "http://192.169.0.1:11434").forEach { url ->
            assertEquals(
                "$url should be refused",
                CleartextPolicy.Verdict.PublicRefused,
                CleartextPolicy.classify(url, emptySet()),
            )
        }
    }

    @Test
    fun `given userinfo in the url when the origin is derived then credentials are dropped`() {
        // The origin is an approval key; letting a password into it would both
        // store a secret in plain DataStore and make the key unmatchable later.
        assertEquals("http://192.168.1.42:11434", CleartextPolicy.originOf("http://user:pw@192.168.1.42:11434/x"))
        assertEquals("192.168.1.42", CleartextPolicy.hostOf("http://user:pw@192.168.1.42:11434/x"))
    }

    @Test
    fun `given a backslash before userinfo when classified then the private address is not trusted`() {
        // Ktor reads `\` as a path separator and connects to `evil.example`; splitting on
        // `@` alone would name — and, once approved, admit — the private address instead.
        val url = "http://evil.example\\@192.168.1.42:11434"
        assertNull(CleartextPolicy.hostOf(url))
        assertEquals(CleartextPolicy.Verdict.PublicRefused, CleartextPolicy.classify(url, approved))
    }

    @Test
    fun `given whitespace inside the authority when the host is derived then there is none`() {
        assertNull(CleartextPolicy.hostOf("http://evil.example @192.168.1.42:11434"))
    }

    @Test
    fun `given mixed case scheme and host when the origin is derived then it is canonical`() {
        assertEquals("http://192.168.1.42:11434", CleartextPolicy.originOf("HTTP://192.168.1.42:11434/API"))
    }

    @Test
    fun `given a refusing verdict when a message is requested then it says what to do`() {
        val needsApproval = CleartextPolicy.classify("http://10.0.0.7:11434", emptySet())
        assertNotNull(CleartextPolicy.refusalMessage(needsApproval))
        assertNotNull(CleartextPolicy.refusalMessage(CleartextPolicy.Verdict.PublicRefused))
        assertNull(CleartextPolicy.refusalMessage(CleartextPolicy.Verdict.NotCleartext))
        assertNull(CleartextPolicy.refusalMessage(CleartextPolicy.Verdict.ApprovedPrivate("http://x")))
    }

    @Test
    fun `given a malformed url when classified then it is refused rather than allowed`() {
        // Anything the parser cannot make sense of must fail closed.
        listOf("http://", "http://@", "http:///path").forEach { url ->
            assertFalse("$url must not be allowed", CleartextPolicy.isAllowed(url, approved))
        }
    }
}
