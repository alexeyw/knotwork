package app.knotwork.android.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * The shared client's cleartext guard sees **every hop**, redirects included.
 *
 * OkHttp follows redirects inside the chain, below the application interceptors:
 * a guard registered as one saw only the first request, so an https call — or, as
 * here, a call to an approved private host — could be redirected to a public host
 * over plain http, which three in-repository statements said could not happen.
 */
class SharedHttpClientTest {

    private val server = MockWebServer()

    /** Names the client asked to resolve, in order. */
    private val lookups = mutableListOf<String>()

    /** Resolves every name to loopback, so the test can name hosts it controls. */
    private val loopbackDns = Dns { hostname ->
        lookups += hostname
        listOf(InetAddress.getLoopbackAddress())
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = SharedHttpClient.build(CleartextGuardInterceptor(isPrivateHost = { it == "start.test" }))
        .newBuilder()
        .dns(loopbackDns)
        .build()

    @Test
    fun `given a redirect to a public cleartext host when the shared client follows it then the hop is refused`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .setHeader("Location", "http://public.test:${server.port}/b")
                .build(),
        )
        server.enqueue(MockResponse.Builder().body("fetched over cleartext").build())

        try {
            client().newCall(Request.Builder().url("http://start.test:${server.port}/a").build()).execute().close()
            fail("the redirect to a public host over http was followed")
        } catch (e: IOException) {
            assertTrue(
                e.message.orEmpty(),
                e.message.orEmpty().startsWith("Refusing an unencrypted request to public.test"),
            )
        }
        assertEquals("the second hop must never be sent", 1, server.requestCount)
    }

    @Test
    fun `given a first request to a public cleartext host when sent then it is refused before any lookup`() {
        // Refusing after connecting would still resolve the name and open a socket
        // to the public host; the first hop is refused before either.
        try {
            client().newCall(Request.Builder().url("http://public.test:${server.port}/a").build()).execute().close()
            fail("a public host over http was reached")
        } catch (e: IOException) {
            assertTrue(
                e.message.orEmpty(),
                e.message.orEmpty().startsWith("Refusing an unencrypted request to public.test"),
            )
        }
        assertEquals(emptyList<String>(), lookups)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `given a cleartext request to an approved private host when sent then it goes through`() {
        server.enqueue(MockResponse.Builder().body("ok").build())

        val body = client().newCall(Request.Builder().url("http://start.test:${server.port}/a").build()).execute()
            .use { it.body.string() }

        assertEquals("ok", body)
    }
}
