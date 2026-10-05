package app.knotwork.android.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [EndpointRule] — the one decision the address gate, the per-hop check and the
 * settings form read.
 */
class EndpointRuleTest {

    private fun refusal(url: String, approved: Set<String> = emptySet(), localOnly: Boolean = false) =
        EndpointRule.refusal(url, approved, localOnly)

    @Test
    fun `given the restriction on when the host is public then it is refused, whatever the scheme`() {
        assertEquals(
            AddressRefusal.HostNotLocal("api.groq.com"),
            refusal("https://api.groq.com/openai/v1", localOnly = true),
        )
        assertEquals(AddressRefusal.HostNotLocal("my-box.lan"), refusal("http://my-box.lan:8000/v1", localOnly = true))
    }

    @Test
    fun `given the restriction on and a public unencrypted host when judged then the restriction is the reason`() {
        // Both rules refuse; the one the user switched on deliberately is the one named.
        assertEquals(AddressRefusal.HostNotLocal("203.0.113.7"), refusal("http://203.0.113.7:8000", localOnly = true))
    }

    @Test
    fun `given an address without a host when the restriction is on then the address itself is named`() {
        assertEquals(AddressRefusal.HostNotLocal("not a url"), refusal("not a url", localOnly = true))
    }

    @Test
    fun `given an unencrypted public host when judged then it is refused and cannot be approved`() {
        assertEquals(
            AddressRefusal.PublicCleartext("203.0.113.7"),
            refusal("http://203.0.113.7:8000/v1", approved = setOf("http://203.0.113.7:8000")),
        )
    }

    @Test
    fun `given an unencrypted private address not approved when judged then approval is what it needs`() {
        assertEquals(
            AddressRefusal.CleartextNeedsApproval("http://192.168.1.20:8000"),
            refusal("http://192.168.1.20:8000/v1", localOnly = true),
        )
    }

    @Test
    fun `given addresses the rules allow when judged then nothing is refused`() {
        assertNull(refusal("https://api.groq.com/openai/v1"))
        assertNull(refusal("http://192.168.1.20:8000/v1", approved = setOf("http://192.168.1.20:8000")))
        assertNull(
            refusal("http://192.168.1.20:8000/v1", approved = setOf("http://192.168.1.20:8000"), localOnly = true),
        )
        assertNull(refusal("https://192.168.1.20:8443/v1", localOnly = true))
    }
}
