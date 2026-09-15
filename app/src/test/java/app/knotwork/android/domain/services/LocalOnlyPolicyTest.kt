package app.knotwork.android.domain.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocalOnlyPolicy] — which model endpoints stay reachable while
 * "Block network from local model" is on.
 *
 * As with [CleartextPolicyTest], the refusals matter more than the admissions: a
 * wrong `true` here sends a prompt off the user's network under a setting that
 * promised it would not.
 */
class LocalOnlyPolicyTest {

    @Test
    fun `given loopback and private addresses under either scheme when judged then they are local`() {
        listOf(
            "http://localhost:11434",
            "https://localhost:11434",
            "http://127.0.0.1:11434",
            "https://10.0.0.7:11434/api",
            "http://172.16.0.1:11434",
            "https://172.31.255.254",
            "http://192.168.1.42:11434",
            "HTTPS://192.168.1.42:11434",
            "https://user:pw@192.168.1.42:11434",
        ).forEach { url -> assertTrue("$url should be local", LocalOnlyPolicy.isLocalEndpoint(url)) }
    }

    @Test
    fun `given an encrypted public address when judged then it is not local`() {
        // The case the cleartext rule waves through without reading the host.
        assertFalse(LocalOnlyPolicy.isLocalEndpoint("https://ollama.example.com"))
        assertFalse(LocalOnlyPolicy.isLocalEndpoint("https://203.0.113.7:11434"))
    }

    @Test
    fun `given addresses just outside the private ranges when judged then they are not local`() {
        listOf("https://172.15.255.255", "https://172.32.0.1", "https://192.169.0.1", "https://11.0.0.1")
            .forEach { url -> assertFalse("$url must not be local", LocalOnlyPolicy.isLocalEndpoint(url)) }
    }

    @Test
    fun `given a host name that may resolve to the LAN when judged then it is not local`() {
        // A name says nothing about where DNS sends the request, so none is trusted.
        listOf("https://ollama.lan:11434", "http://nas.local:11434", "https://ollama.home.arpa", "https://localhost.")
            .forEach { url -> assertFalse("$url must not be local", LocalOnlyPolicy.isLocalEndpoint(url)) }
    }

    @Test
    fun `given a private address used as userinfo when judged then the real host decides`() {
        assertFalse(LocalOnlyPolicy.isLocalEndpoint("https://192.168.1.42:11434@ollama.example.com"))
        assertFalse(LocalOnlyPolicy.isLocalEndpoint("https://ollama.example.com\\@192.168.1.42"))
    }

    @Test
    fun `given addresses outside the supported literal forms when judged then they are not local`() {
        // Tailscale's CGNAT range, IPv6 and non-decimal IPv4 spellings are refused rather
        // than parsed: the documented rule is localhost plus private IPv4 in plain decimal.
        listOf(
            "https://100.100.1.1:11434",
            "https://[::1]:11434",
            "https://[fd00::1]:11434",
            "https://010.0.0.1",
            "https://0x7f.0.0.1",
            "https://127.1",
        ).forEach { url -> assertFalse("$url must not be local", LocalOnlyPolicy.isLocalEndpoint(url)) }
    }

    @Test
    fun `given a url without a parsable host when judged then it is not local`() {
        listOf("", "   ", "192.168.1.42:11434", "https://", "https://@", "not a url")
            .forEach { url -> assertFalse("'$url' must not be local", LocalOnlyPolicy.isLocalEndpoint(url)) }
    }
}
