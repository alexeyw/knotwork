package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HttpRequestPolicy] — the pure security policy behind the
 * `http_request` tool. Each rule is exercised on both its accept and reject
 * paths so the executor's refusals are anchored to verified behaviour.
 */
class HttpRequestPolicyTest {

    @Test
    fun `given GET when methodRisk then SENSITIVE`() {
        assertEquals(ToolRisk.SENSITIVE, HttpRequestPolicy.methodRisk("GET"))
    }

    @Test
    fun `given lowercase get when methodRisk then SENSITIVE`() {
        assertEquals(ToolRisk.SENSITIVE, HttpRequestPolicy.methodRisk(" get "))
    }

    @Test
    fun `given POST PUT DELETE when methodRisk then DESTRUCTIVE`() {
        assertEquals(ToolRisk.DESTRUCTIVE, HttpRequestPolicy.methodRisk("POST"))
        assertEquals(ToolRisk.DESTRUCTIVE, HttpRequestPolicy.methodRisk("PUT"))
        assertEquals(ToolRisk.DESTRUCTIVE, HttpRequestPolicy.methodRisk("DELETE"))
    }

    @Test
    fun `given unsupported method when methodRisk then null`() {
        assertNull(HttpRequestPolicy.methodRisk("PATCH"))
        assertNull(HttpRequestPolicy.methodRisk("HEAD"))
        assertNull(HttpRequestPolicy.methodRisk(""))
    }

    @Test
    fun `given body methods when methodAllowsBody then true only for write verbs`() {
        assertTrue(HttpRequestPolicy.methodAllowsBody("POST"))
        assertTrue(HttpRequestPolicy.methodAllowsBody("put"))
        assertTrue(HttpRequestPolicy.methodAllowsBody("DELETE"))
        assertFalse(HttpRequestPolicy.methodAllowsBody("GET"))
    }

    @Test
    fun `given scheme path and case when normalizeDomain then bare lowercase host`() {
        assertEquals("api.example.com", HttpRequestPolicy.normalizeDomain("https://API.Example.com/v1/items"))
        assertEquals("example.com", HttpRequestPolicy.normalizeDomain("  example.com  "))
        assertEquals("localhost", HttpRequestPolicy.normalizeDomain("http://localhost:11434"))
        assertEquals("a.example.com", HttpRequestPolicy.normalizeDomain("a.example.com?x=1"))
    }

    @Test
    fun `given userinfo when normalizeDomain then strips credentials and keeps the host`() {
        // Regression: without stripping userinfo, `user:pass@example.com` survives
        // as the bogus host `user` (passes the single-label host pattern).
        assertEquals("example.com", HttpRequestPolicy.normalizeDomain("user:pass@example.com"))
        assertEquals("example.com", HttpRequestPolicy.normalizeDomain("https://user:pass@example.com:8443/path"))
        assertEquals("example.com", HttpRequestPolicy.normalizeDomain("user@example.com"))
    }

    @Test
    fun `given invalid input when normalizeDomain then null`() {
        assertNull(HttpRequestPolicy.normalizeDomain(""))
        assertNull(HttpRequestPolicy.normalizeDomain("https://"))
        assertNull(HttpRequestPolicy.normalizeDomain("has space.com"))
        assertNull(HttpRequestPolicy.normalizeDomain("bad_underscore.com"))
        assertNull(HttpRequestPolicy.normalizeDomain("-leadinghyphen.com"))
    }

    @Test
    fun `given an exact host when isHostAllowed then true`() {
        val allowed = listOf("example.com", "test.org")
        assertTrue(HttpRequestPolicy.isHostAllowed("example.com", allowed))
        assertTrue(HttpRequestPolicy.isHostAllowed("test.org", allowed))
    }

    @Test
    fun `given a subdomain of an allowlisted host when isHostAllowed then false`() {
        // Matching is exact — sub-domains are NOT implied (least privilege).
        val allowed = listOf("example.com")
        assertFalse(HttpRequestPolicy.isHostAllowed("api.example.com", allowed))
        assertFalse(HttpRequestPolicy.isHostAllowed("deep.api.example.com", allowed))
    }

    @Test
    fun `given non-matching or suffix-trick host when isHostAllowed then false`() {
        val allowed = listOf("example.com")
        assertFalse(HttpRequestPolicy.isHostAllowed("other.com", allowed))
        // notexample.com must NOT match example.com (suffix without dot boundary)
        assertFalse(HttpRequestPolicy.isHostAllowed("notexample.com", allowed))
        assertFalse(HttpRequestPolicy.isHostAllowed("evil-example.com", allowed))
    }

    @Test
    fun `given empty allowlist when isHostAllowed then false`() {
        assertFalse(HttpRequestPolicy.isHostAllowed("example.com", emptyList()))
        assertFalse(HttpRequestPolicy.isHostAllowed("", listOf("example.com")))
    }

    @Test
    fun `given loopback and private hosts when isLoopbackOrPrivateHost then true`() {
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("localhost"))
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("127.0.0.1"))
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("10.0.2.2"))
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("192.168.1.100"))
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("172.17.0.1"))
    }

    @Test
    fun `given public host or out-of-range octets when isLoopbackOrPrivateHost then false`() {
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("example.com"))
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("8.8.8.8"))
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("172.32.0.1")) // just outside 16..31
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("999.1.1.1"))
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("10.0.0"))
    }

    @Test
    fun `given an octet with a leading zero when isLoopbackOrPrivateHost then false`() {
        // `inet_aton`-style resolvers read `010` as octal 8, so `010.0.0.1` can mean the
        // public `8.0.0.1` on the connection even though it reads as `10.0.0.1` here.
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("010.0.0.1"))
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("192.168.01.1"))
        assertFalse(HttpRequestPolicy.isLoopbackOrPrivateHost("127.0.0.00"))
        // A lone zero is an ordinary octet.
        assertTrue(HttpRequestPolicy.isLoopbackOrPrivateHost("10.0.0.1"))
    }

    @Test
    fun `given a same-host redirect when headersForRedirect then headers are unchanged`() {
        val headers = listOf("Authorization" to "Bearer x", "Accept" to "application/json")
        assertEquals(headers, HttpRequestPolicy.headersForRedirect(headers, "example.com", "example.com"))
        // Host comparison is case-insensitive.
        assertEquals(headers, HttpRequestPolicy.headersForRedirect(headers, "Example.com", "example.com"))
    }

    @Test
    fun `given a cross-host redirect when headersForRedirect then credential headers are dropped`() {
        val headers = listOf(
            "Authorization" to "Bearer x",
            "Cookie" to "s=1",
            "Proxy-Authorization" to "Basic y",
            "Accept" to "application/json",
        )
        assertEquals(
            listOf("Accept" to "application/json"),
            HttpRequestPolicy.headersForRedirect(headers, "a.example.com", "b.example.com"),
        )
    }

    @Test
    fun `given a secret present in a header or body when leaksCredential then true`() {
        val secrets = listOf("sk-secret-123", "")
        assertTrue(HttpRequestPolicy.leaksCredential(listOf("Bearer sk-secret-123"), secrets))
        assertTrue(HttpRequestPolicy.leaksCredential(listOf("{\"token\":\"sk-secret-123\"}"), secrets))
    }

    @Test
    fun `given no secret match or no secrets when leaksCredential then false`() {
        assertFalse(HttpRequestPolicy.leaksCredential(listOf("Bearer public"), listOf("sk-secret-123")))
        assertFalse(HttpRequestPolicy.leaksCredential(listOf("anything"), emptyList()))
        assertFalse(HttpRequestPolicy.leaksCredential(listOf("anything"), listOf("", "   ")))
    }
}
