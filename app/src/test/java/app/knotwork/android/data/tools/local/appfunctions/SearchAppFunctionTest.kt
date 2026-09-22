package app.knotwork.android.data.tools.local.appfunctions

import androidx.appfunctions.AppFunctionContext
import app.knotwork.android.data.tools.local.SearchTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SearchAppFunction].
 *
 * The wrapper is intentionally thin — its job is to validate the externally-supplied
 * arguments and forward them to [SearchTool.executeSearch]. The tests pin three contracts
 * that the callee-side AppFunctions surface relies on:
 *  1. Valid arguments reach [SearchTool] verbatim and the result is returned unchanged.
 *  2. A blank `query` raises [IllegalArgumentException] (which the AppFunctions framework
 *     maps to `ERROR_INVALID_ARGUMENT` on the platform path).
 *  3. A blank `lang` is normalised to the default (`"en"`) so external callers can pass an
 *     empty string when they don't know which language to ask for.
 *  4. A `lang` that cannot name a Wikipedia edition raises [IllegalArgumentException]
 *     before [SearchTool] is reached, like a blank `query`.
 */
class SearchAppFunctionTest {

    private val searchTool: SearchTool = mockk()
    private val searchAppFunction = SearchAppFunction(searchTool)
    private val appFunctionContext: AppFunctionContext = mockk(relaxed = true)

    @Test
    fun `given valid arguments when invoke then delegates to SearchTool and returns result`() = runTest {
        coEvery { searchTool.executeSearch("kotlin", "en") } returns "Kotlin is a programming language."

        val result = searchAppFunction.invoke(appFunctionContext, query = "kotlin", lang = "en")

        assertEquals("Kotlin is a programming language.", result)
        coVerify(exactly = 1) { searchTool.executeSearch("kotlin", "en") }
    }

    @Test
    fun `given blank query when invoke then throws IllegalArgumentException`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { searchAppFunction.invoke(appFunctionContext, query = "  ", lang = "en") }
        }
        assertEquals("search_tool requires a non-blank 'query' argument", exception.message)
    }

    @Test
    fun `given blank lang when invoke then falls back to default en`() = runTest {
        coEvery { searchTool.executeSearch("kotlin", "en") } returns "ok"

        val result = searchAppFunction.invoke(appFunctionContext, query = "kotlin", lang = "")

        assertEquals("ok", result)
        coVerify(exactly = 1) { searchTool.executeSearch("kotlin", "en") }
    }

    @Test
    fun `given a lang that cannot name a Wikipedia edition when invoke then throws and never searches`() {
        // SearchTool refuses such a value too; rejecting it here gives the external caller
        // the same typed ERROR_INVALID_ARGUMENT outcome a blank query gets.
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { searchAppFunction.invoke(appFunctionContext, query = "kotlin", lang = "evil.example/") }
        }

        assertTrue(exception.message.orEmpty().contains("'lang'"))
        coVerify(exactly = 0) { searchTool.executeSearch(any(), any()) }
    }
}
