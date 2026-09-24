package app.knotwork.android.data.tools.local

import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import app.knotwork.android.domain.engine.LlmInferenceEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unit tests for [SearchTool].
 *
 * **Nothing here reaches the internet.** The tool opens its connection through
 * [SearchTool.ConnectionOpener], and the only opener in this class answers from a
 * local [MockWebServer] while recording the URL the tool asked for. That URL is the
 * real one — `https://<edition>.wikipedia.org/w/api.php?…`, built and validated by
 * the tool itself — so the checks the tool makes before opening are exercised
 * unchanged. (These tests used to call the live API; from the JVM it answers `403`
 * to the default `Java/…` user agent, so they asserted nothing about a real reply.)
 *
 * The response bodies copy the shape of the live API's answers, measured on
 * 22.09.2026, with synthetic article text.
 */
class SearchToolTest {

    private val llmEngine = mockk<LlmInferenceEngine>(relaxed = true)
    private val networkGate = mockk<ModelNetworkGate>()
    private val server = MockWebServer()

    /** Every URL the tool asked to open, in order. */
    private val opened = mutableListOf<URL>()

    /** Serves the tool's request from [server]; see the class KDoc. */
    private val localWikipedia = SearchTool.ConnectionOpener { url ->
        opened += url
        server.url(url.file).toUrl().openConnection() as HttpURLConnection
    }

    private val networkActivity = NetworkActivityTrackerImpl()

    private val searchTool = SearchTool(llmEngine, networkGate, localWikipedia, networkActivity)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        // Whatever a test did, the tool may only ever have asked for Wikipedia over https.
        for (url in opened) {
            assertEquals("https", url.protocol)
            assertTrue("search_tool opened $url", url.host.endsWith(".wikipedia.org"))
        }
    }

    @Test
    fun `asAgentTool should return correct tool definition`() {
        val tool = searchTool.asAgentTool()
        assertEquals("search_tool", tool.name)
        assertNotNull(tool.description)
        assertTrue(tool.parameters.contains("query"))
        assertTrue(tool.parameters.contains("lang"))
    }

    @Test
    fun `given an empty query when executeSearch then no results are reported`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        serve(MISSING_PARAM_BODY)

        val result = searchTool.executeSearch("", "en")

        assertEquals("No results found for ''.", result)
    }

    @Test
    fun `given Wikipedia finds an article when executeSearch then its extract is returned`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        serve(articleBody(SHORT_EXTRACT))

        val result = searchTool.executeSearch("Ada Lovelace", "en")

        assertEquals(SHORT_EXTRACT, result)
        assertEquals("en.wikipedia.org", opened.single().host)
        assertEquals("Ada Lovelace", server.takeRequest().url.queryParameter("gsrsearch"))
    }

    @Test
    fun `given no article matches when executeSearch then no results are reported`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        serve(NO_MATCH_BODY)

        val result = searchTool.executeSearch("qqzzxx", "simple")

        assertEquals("No results found for 'qqzzxx'.", result)
        assertEquals("simple.wikipedia.org", opened.single().host)
    }

    @Test
    fun `given the API refuses the request when executeSearch then the status is reported`() = runTest {
        // 403 is what the live API answers a user agent it does not accept.
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        server.enqueue(MockResponse.Builder().code(403).body("Please set a user-agent").build())

        val result = searchTool.executeSearch("Ada Lovelace", "en")

        assertEquals("Failed to fetch data: HTTP 403", result)
    }

    @Test
    fun `given an over-long extract and no local model when executeSearch then it is cut at a sentence`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        val longExtract = "A sentence of synthetic article text. ".repeat(80)
        serve(articleBody(longExtract))

        val result = searchTool.executeSearch("Ada Lovelace", "en")

        assertTrue("not shortened: ${result.length}", result.length in 1..2_000)
        assertTrue("not cut at a sentence: …${result.takeLast(20)}", result.endsWith("."))
        assertTrue(longExtract.startsWith(result))
    }

    @Test
    fun `given the network restriction is on when executeSearch then the refusal is returned`() = runTest {
        // The refusal must arrive before anything is sent: this is the path that made
        // "Block network from local model" false by its own name.
        coEvery { networkGate.networkToolRefusal("search_tool") } returns REFUSAL

        val result = searchTool.executeSearch("Ada Lovelace", "en")

        assertEquals(REFUSAL, result)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `given the network restriction is on when executeSearch then the local model is not consulted`() = runTest {
        // Nothing downstream of the connection may run either — the summariser included,
        // since it would only ever see an extract that was not fetched.
        coEvery { networkGate.networkToolRefusal(any()) } returns REFUSAL

        searchTool.executeSearch("Ada Lovelace", "en")

        coVerify(exactly = 0) { llmEngine.generateResponseStream(any()) }
    }

    @Test
    fun `given the network restriction is off when executeSearch then the gate is asked for this tool by name`() =
        runTest {
            coEvery { networkGate.networkToolRefusal(any()) } returns null
            serve(NO_MATCH_BODY)

            searchTool.executeSearch("Ada Lovelace", "en")

            coVerify(exactly = 1) { networkGate.networkToolRefusal("search_tool") }
        }

    @Test
    fun `given a lang that is not a single DNS label when executeSearch then it is refused before any connection`() =
        runTest {
            // `lang` sits in the authority of the request URL. Anything beyond the
            // characters of one DNS label lets the caller — the model, or another app
            // through AppFunctions — move the request, and the search term with it, to a
            // host of its choosing.
            coEvery { networkGate.networkToolRefusal(any()) } returns null
            val hostile = listOf(
                "evil.example/",
                "evil.example#",
                "evil.example?",
                "user@evil.example",
                "evil.example:443",
                "en.evil",
                "a/b",
                "",
                " ",
            )

            for (lang in hostile) {
                val result = searchTool.executeSearch("words from the conversation", lang)

                assertTrue("'$lang' was not refused: $result", result.startsWith(INVALID_LANG_PREFIX))
            }
            assertTrue("a connection was opened: $opened", opened.isEmpty())
        }

    @Test
    fun `given the gate admits the call when executeSearch then the privacy indicator records it`() = runTest {
        // The More tab's "no network calls" pill reads this tracker. `search_tool` is on
        // by default and was the one path it never heard of.
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        serve(NO_MATCH_BODY)

        searchTool.executeSearch("Ada Lovelace", "en")

        assertNotNull("the lookup reached the network without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given the call is refused before a connection when executeSearch then nothing is recorded`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns REFUSAL
        searchTool.executeSearch("Ada Lovelace", "en")

        coEvery { networkGate.networkToolRefusal(any()) } returns null
        searchTool.executeSearch("Ada Lovelace", "evil.example/")

        assertNull(networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given a lookup when executeSearch then it names the app and not the device in its user agent`() = runTest {
        // Left to the platform, the header is `Dalvik/2.1.0 (Linux; U; Android 16; <model>
        // Build/<id>)` — the phone model and the exact Android build go to Wikimedia with
        // every lookup. Wikimedia's user-agent policy asks for `<client>/<version>
        // (<contact>)` instead, and its API answers that with 200 (measured 24.09.2026).
        coEvery { networkGate.networkToolRefusal(any()) } returns null
        serve(NO_MATCH_BODY)

        searchTool.executeSearch("Ada Lovelace", "en")

        val userAgent = server.takeRequest().headers["User-Agent"].orEmpty()
        assertEquals(SearchTool.USER_AGENT, userAgent)
        assertTrue("not the app's own agent: $userAgent", userAgent.startsWith("Knotwork/"))
        for (deviceDetail in listOf("Dalvik", "Android", "Linux", "Java/")) {
            assertTrue("'$deviceDetail' in $userAgent", deviceDetail !in userAgent)
        }
    }

    @Test
    fun `given a real Wikipedia edition when the search URL is built then the host is that edition`() {
        // `simple` and the hyphenated editions are why the rule is "one DNS label" and
        // not "a two- or three-letter code".
        val editions = mapOf(
            "en" to "en.wikipedia.org",
            "simple" to "simple.wikipedia.org",
            "zh-min-nan" to "zh-min-nan.wikipedia.org",
            "be-tarask" to "be-tarask.wikipedia.org",
            " EN " to "en.wikipedia.org",
        )

        for ((lang, host) in editions) {
            assertEquals(host, searchTool.searchUrl("Ada Lovelace", lang)?.host)
        }
    }

    @Test
    fun `given a query carrying URL syntax when the search URL is built then it stays in the query string`() {
        val url = searchTool.searchUrl("a/b?c=d#e@f", "en")!!

        assertEquals("en.wikipedia.org", url.host)
        assertEquals("/w/api.php", url.path)
        assertNull(url.ref)
    }

    @Test
    fun `given the invalid lang refusal when compared with the test prefix then they agree`() {
        assertTrue(SearchTool.INVALID_LANG_ERROR.startsWith(INVALID_LANG_PREFIX))
    }

    private fun serve(json: String) {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(json).build(),
        )
    }

    private companion object {
        const val INVALID_LANG_PREFIX = "Error: 'lang' must be"
        const val REFUSAL = "Error: search_tool is disabled — restriction on."
        const val SHORT_EXTRACT = "Synthetic extract standing in for an article introduction."

        /** The live answer to a search that matched nothing. */
        const val NO_MATCH_BODY = """{"batchcomplete":""}"""

        /** The live answer to an empty `gsrsearch` — an error object and no `query`. */
        const val MISSING_PARAM_BODY =
            """{"error":{"code":"missingparam","info":"The \"gsrsearch\" parameter must be set."},""" +
                """"servedby":"mw-api-ext"}"""

        /** The live answer to a matching search, with [extract] as the article text. */
        fun articleBody(extract: String): String =
            """{"batchcomplete":"","continue":{"gsroffset":1,"continue":"gsroffset||"},""" +
                """"query":{"pages":{"974":{"pageid":974,"ns":0,"title":"Ada Lovelace","index":1,""" +
                """"extract":"$extract"}}}}"""
    }
}
