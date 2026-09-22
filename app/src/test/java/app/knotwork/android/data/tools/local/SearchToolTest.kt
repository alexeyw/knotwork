package app.knotwork.android.data.tools.local

import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.domain.engine.LlmInferenceEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToolTest {

    private val llmEngine = mockk<LlmInferenceEngine>(relaxed = true)
    private val networkGate = mockk<ModelNetworkGate>()
    private val searchTool = SearchTool(llmEngine, networkGate)

    @Test
    fun `asAgentTool should return correct tool definition`() {
        val tool = searchTool.asAgentTool()
        assertEquals("search_tool", tool.name)
        assertNotNull(tool.description)
        assertTrue(tool.parameters.contains("query"))
        assertTrue(tool.parameters.contains("lang"))
    }

    @Test
    fun `executeSearch should handle empty query gracefully`() = runTest {
        coEvery { networkGate.networkToolRefusal(any()) } returns null

        val result = searchTool.executeSearch("", "en")
        assertNotNull(result)
        // Should not crash, will likely return an error string or "No results found"
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `given the network restriction is on when executeSearch then the refusal is returned`() = runTest {
        // The refusal must arrive before anything is sent: this is the path that made
        // "Block network from local model" false by its own name.
        coEvery { networkGate.networkToolRefusal("search_tool") } returns REFUSAL

        val result = searchTool.executeSearch("Ada Lovelace", "en")

        assertEquals(REFUSAL, result)
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

    private companion object {
        const val INVALID_LANG_PREFIX = "Error: 'lang' must be"
        const val REFUSAL = "Error: search_tool is disabled — restriction on."
    }
}
