package app.knotwork.android.data.tools.local

import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.domain.engine.LlmInferenceEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private companion object {
        const val REFUSAL = "Error: search_tool is disabled — restriction on."
    }
}
