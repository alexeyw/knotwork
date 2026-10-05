package app.knotwork.android.presentation.ui.tools

import androidx.lifecycle.SavedStateHandle
import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.connection.McpConnectionChecker
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.UpdateMcpServerResult
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.presentation.ui.common.ConnectionTestState
import app.knotwork.design.screens.tools.McpAuthSelector
import app.knotwork.design.screens.tools.McpHeaderRow
import app.knotwork.design.screens.tools.McpTransportOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerConfigViewModelTest {

    private val settings: SettingsRepository = mockk(relaxed = true)
    private val mcp: McpServerRepository = mockk(relaxed = true)
    private val mcpServersFlow = MutableStateFlow<List<McpServerConfig>>(emptyList())
    private val approvedOriginsFlow = MutableStateFlow<Set<String>>(emptySet())

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settings.mcpServers } returns mcpServersFlow
        every { settings.approvedCleartextOrigins } returns approvedOriginsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        originalUrl: String? = null,
        checker: McpConnectionChecker = mockk(relaxed = true),
    ): McpServerConfigViewModel = McpServerConfigViewModel(
        savedStateHandle = SavedStateHandle(
            if (originalUrl != null) mapOf(McpServerConfigViewModel.EXTRA_ORIGINAL_URL to originalUrl) else emptyMap(),
        ),
        toolSettings = settings,
        networkSettings = settings,
        mcpServerRepository = mcp,
        connectionChecker = checker,
        timeSource = TestTimeSource(),
    )

    @Test
    fun `add mode starts with an empty form and editingUrl null`() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        val form = viewModel.form.value
        assertEquals("", form.url)
        assertNull(form.editingUrl)
        assertTrue(form.headers.isEmpty())
    }

    @Test
    fun `edit mode pre-fills the form from settingsRepository`() = runTest {
        val url = "https://prefill.example/mcp"
        mcpServersFlow.value = listOf(
            McpServerConfig(
                url = url,
                name = "Prefilled",
                transport = McpTransport.STREAMABLE_HTTP,
                headers = mapOf("Authorization" to "Bearer t"),
            ),
        )

        val viewModel = vm(originalUrl = url)
        advanceUntilIdle()

        val form = viewModel.form.value
        assertEquals(url, form.url)
        assertEquals("Prefilled", form.name)
        assertEquals(McpTransportOption.StreamableHttp, form.transport)
        assertEquals(McpHeaderRow(key = "Authorization", value = "Bearer t"), form.headers.single())
        assertEquals(url, form.editingUrl)
    }

    @Test
    fun `onSubmit in add mode persists the config and emits Saved`() = runTest {
        val viewModel = vm()
        viewModel.onUrlChange(value = "https://added.example/mcp")
        viewModel.onNameChange(value = "Added")
        viewModel.onHeaderAdd()
        viewModel.onHeaderChange(index = 0, key = "Authorization", value = "Bearer x")
        viewModel.onTransportSelect(option = McpTransportOption.StreamableHttp)

        viewModel.onSubmit()
        advanceUntilIdle()

        coVerify {
            settings.addMcpServer(
                config = McpServerConfig(
                    url = "https://added.example/mcp",
                    name = "Added",
                    transport = McpTransport.STREAMABLE_HTTP,
                    headers = mapOf("Authorization" to "Bearer x"),
                ),
            )
        }
        assertEquals(McpServerConfigViewModel.Event.Saved, viewModel.events.value)
    }

    @Test
    fun `onSubmit in edit mode disconnects old client and updates the config`() = runTest {
        val url = "https://edited.example/mcp"
        mcpServersFlow.value = listOf(McpServerConfig(url = url))
        val viewModel = vm(originalUrl = url)
        advanceUntilIdle()
        viewModel.onNameChange(value = "Renamed")

        viewModel.onSubmit()
        advanceUntilIdle()

        coVerify { mcp.disconnect(serverUrl = url) }
        coVerify {
            settings.updateMcpServer(
                originalUrl = url,
                updated = McpServerConfig(url = url, name = "Renamed"),
            )
        }
        assertEquals(McpServerConfigViewModel.Event.Saved, viewModel.events.value)
    }

    @Test
    fun `onSubmit with invalid URL keeps the form open and surfaces the error`() = runTest {
        val viewModel = vm()
        viewModel.onUrlChange(value = "not a url")

        viewModel.onSubmit()
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.addMcpServer(any()) }
        assertNull(viewModel.events.value)
        assertNotNull(viewModel.form.value.urlError)
    }

    @Test
    fun `bearer auth round-trips through edit mode`() = runTest {
        val url = "https://hf.example/mcp"
        mcpServersFlow.value = listOf(
            McpServerConfig(url = url, auth = McpAuth.Bearer(token = "secret")),
        )

        val viewModel = vm(originalUrl = url)
        advanceUntilIdle()

        val form = viewModel.form.value
        assertEquals(McpAuthSelector.BEARER, form.authType)
        assertEquals("secret", form.bearerToken)

        viewModel.onSubmit()
        advanceUntilIdle()

        coVerify {
            settings.updateMcpServer(
                originalUrl = url,
                updated = McpServerConfig(url = url, auth = McpAuth.Bearer(token = "secret")),
            )
        }
    }

    @Test
    fun `api key auth survives add-mode submission`() = runTest {
        val viewModel = vm()
        viewModel.onUrlChange(value = "https://api.example/mcp")
        viewModel.onAuthTypeSelect(option = McpAuthSelector.API_KEY)
        viewModel.onApiKeyHeaderNameChange(value = "X-API-Key")
        viewModel.onApiKeyValueChange(value = "v1")

        viewModel.onSubmit()
        advanceUntilIdle()

        coVerify {
            settings.addMcpServer(
                config = McpServerConfig(
                    url = "https://api.example/mcp",
                    auth = McpAuth.ApiKey(headerName = "X-API-Key", value = "v1"),
                ),
            )
        }
    }

    @Test
    fun `onSubmit in edit mode renders inline error when SettingsRepository returns UrlCollision`() = runTest {
        val originalUrl = "https://current.example/mcp"
        val collidingUrl = "https://existing.example/mcp"
        mcpServersFlow.value = listOf(
            McpServerConfig(url = originalUrl, name = "Current"),
            McpServerConfig(url = collidingUrl, name = "Existing"),
        )
        coEvery { settings.updateMcpServer(originalUrl = originalUrl, updated = any()) } returns
            UpdateMcpServerResult.UrlCollision(
                collidingUrl = collidingUrl,
                collidingDisplayName = "Existing",
            )

        val viewModel = vm(originalUrl = originalUrl)
        advanceUntilIdle()
        // User edits the URL to collide with the other server.
        viewModel.onUrlChange(value = collidingUrl)

        viewModel.onSubmit()
        advanceUntilIdle()

        // Saved is NOT emitted — form must stay open with the inline error.
        assertNull(viewModel.events.value)
        val form = viewModel.form.value
        assertNotNull(form.urlError)
        assertTrue(
            "Collision error must call out the colliding server by name; got: ${form.urlError}",
            form.urlError!!.contains("Existing"),
        )
        // submitting flag is released so the Save button is interactive again.
        assertEquals(false, form.submitting)
    }

    @Test
    fun `formatCollisionMessage falls back to URL when colliding row has no display name`() {
        val msg = McpServerConfigViewModel.formatCollisionMessage(
            UpdateMcpServerResult.UrlCollision(
                collidingUrl = "https://anonymous.example/mcp",
                collidingDisplayName = null,
            ),
        )
        assertTrue(msg.contains("https://anonymous.example/mcp"))
    }

    @Test
    fun `onHeaderRemove drops the row at the given index`() = runTest {
        val viewModel = vm()
        viewModel.onHeaderAdd()
        viewModel.onHeaderAdd()
        viewModel.onHeaderChange(index = 0, key = "K1", value = "V1")
        viewModel.onHeaderChange(index = 1, key = "K2", value = "V2")

        viewModel.onHeaderRemove(index = 0)

        val rows = viewModel.form.value.headers
        assertEquals(1, rows.size)
        assertEquals(McpHeaderRow(key = "K2", value = "V2"), rows[0])
    }

    @Test
    fun `given an unapproved private http url when typed then the consent notice names the origin`() =
        runTest(testDispatcher) {
            val viewModel = vm()
            advanceUntilIdle()

            viewModel.onUrlChange("http://192.168.1.42:8080/sse")

            assertEquals("http://192.168.1.42:8080", viewModel.form.value.cleartextConsentOrigin)
        }

    @Test
    fun `given an https url when typed then there is nothing to consent to`() = runTest(testDispatcher) {
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.onUrlChange("https://mcp.example.com/sse")

        assertNull(viewModel.form.value.cleartextConsentOrigin)
    }

    @Test
    fun `given the notice is showing when submitted then it refuses instead of saving`() = runTest(testDispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onUrlChange("http://192.168.1.42:8080/sse")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertNotNull(viewModel.form.value.urlError)
        coVerify(exactly = 0) { settings.addMcpServer(any()) }
    }

    @Test
    fun `given approval is granted when submitted then the notice clears and the server saves`() =
        runTest(testDispatcher) {
            coEvery { settings.approveCleartextOrigin(any()) } answers {
                approvedOriginsFlow.value = approvedOriginsFlow.value + firstArg<String>()
            }
            val viewModel = vm()
            advanceUntilIdle()
            viewModel.onUrlChange("http://192.168.1.42:8080/sse")

            viewModel.onApproveCleartext()
            advanceUntilIdle()
            assertNull(viewModel.form.value.cleartextConsentOrigin)

            viewModel.onSubmit()
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.addMcpServer(any()) }
        }

    @Test
    fun `given editing a server whose url needs approval when the form loads then the notice is shown`() =
        runTest(testDispatcher) {
            // Regression: the notice used to be computed by a collector that could
            // run before the existing config was loaded, so it saw an empty URL and
            // produced no banner — leaving a Save that refused with "approve the
            // connection above" while pointing at nothing.
            val url = "http://192.168.1.42:8080/sse"
            mcpServersFlow.value = listOf(McpServerConfig(url = url))

            val viewModel = vm(originalUrl = url)
            advanceUntilIdle()

            assertEquals("http://192.168.1.42:8080", viewModel.form.value.cleartextConsentOrigin)
        }

    @Test
    fun `given an empty form when opened then the Test row waits for a URL`() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        assertEquals(
            ConnectionTestState.Disabled(ConnectionRefusal.MissingAddress),
            viewModel.connectionTestState.value,
        )
    }

    @Test
    fun `given URLs the form or the rules refuse when typed then the Test row says why`() = runTest {
        val viewModel = vm()

        viewModel.onUrlChange("ftp://mcp.example.com/mcp")
        advanceUntilIdle()
        val scheme = viewModel.connectionTestState.value
        viewModel.onUrlChange("http://mcp.example.com/mcp")
        advanceUntilIdle()
        val public = viewModel.connectionTestState.value
        viewModel.onUrlChange("http://192.168.1.20:8080/mcp")
        advanceUntilIdle()
        val unapproved = viewModel.connectionTestState.value

        assertEquals(ConnectionTestState.Disabled(ConnectionRefusal.NotAnAddress), scheme)
        assertEquals(ConnectionTestState.Disabled(AddressRefusal.PublicCleartext("mcp.example.com")), public)
        assertEquals(
            ConnectionTestState.Disabled(AddressRefusal.CleartextNeedsApproval("http://192.168.1.20:8080")),
            unapproved,
        )
    }

    @Test
    fun `given a URL and a token when Test is pressed then the form's values are checked and nothing is saved`() =
        runTest {
            val checker = mockk<McpConnectionChecker>()
            coEvery { checker.check(any()) } returns ConnectionCheckResult.Reachable(listOf("echo"))
            val viewModel = vm(checker = checker)
            viewModel.onUrlChange("https://mcp.example.com/mcp")
            viewModel.onAuthTypeSelect(McpAuthSelector.BEARER)
            viewModel.onBearerTokenChange("tok")
            advanceUntilIdle()

            viewModel.onTestConnection()
            advanceUntilIdle()

            assertEquals(
                ConnectionTestState.Finished(ConnectionCheckResult.Reachable(listOf("echo"))),
                viewModel.connectionTestState.value,
            )
            coVerify {
                checker.check(match { it.url == "https://mcp.example.com/mcp" && it.auth == McpAuth.Bearer("tok") })
            }
            coVerify(exactly = 0) { settings.addMcpServer(any()) }
        }

    @Test
    fun `given a result when the name changes then it stays, and when the token changes then it is dropped`() =
        runTest {
            val checker = mockk<McpConnectionChecker>()
            coEvery { checker.check(any()) } returns ConnectionCheckResult.Reachable(listOf("echo"))
            val viewModel = vm(checker = checker)
            viewModel.onUrlChange("https://mcp.example.com/mcp")
            advanceUntilIdle()
            viewModel.onTestConnection()
            advanceUntilIdle()

            viewModel.onNameChange("My server")
            advanceUntilIdle()
            val afterRename = viewModel.connectionTestState.value
            viewModel.onAuthTypeSelect(McpAuthSelector.BEARER)
            viewModel.onBearerTokenChange("tok")
            advanceUntilIdle()

            assertTrue("$afterRename", afterRename is ConnectionTestState.Finished)
            assertEquals(ConnectionTestState.Idle, viewModel.connectionTestState.value)
        }
}
