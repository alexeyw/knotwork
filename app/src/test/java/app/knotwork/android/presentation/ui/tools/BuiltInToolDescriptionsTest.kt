package app.knotwork.android.presentation.ui.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.repositories.ToolRepositoryImpl
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The interface's copy of each built-in tool's description against what a model
 * reads: every built-in tool from the real catalogue has one, and its English text
 * is the model's, so a reworded tool cannot leave its translations describing the
 * old behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BuiltInToolDescriptionsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `given every built-in tool available when listed then each has a string equal to what the model reads`() =
        runTest {
            val tools = catalogue()

            val missing = tools.map { it.name } - BUILT_IN_TOOL_DESCRIPTIONS.keys
            assertTrue("built-in tools with no description string: $missing", missing.isEmpty())
            tools.forEach { tool ->
                val shown = context.getString(BUILT_IN_TOOL_DESCRIPTIONS.getValue(tool.name))
                assertEquals(tool.name, tool.description, shown)
            }
        }

    @Test
    fun `given the strings when listed then each names a tool the app has`() = runTest {
        assertEquals(catalogue().map { it.name }.toSet(), BUILT_IN_TOOL_DESCRIPTIONS.keys)
    }

    /** The real built-in catalogue with every conditional tool switched on and nothing discovered. */
    private suspend fun catalogue(): List<AgentTool> {
        val settings = mockk<SettingsRepository>()
        every { settings.mcpServers } returns flowOf(emptyList())
        every { settings.disabledAppFunctions } returns flowOf(emptySet())
        every { settings.disabledMcpTools } returns flowOf(emptySet())
        every { settings.toolRiskOverrides } returns flowOf(emptyMap())
        every { settings.approvedCleartextOrigins } returns flowOf(emptySet())
        // `http_request` is offered only with a domain allowed, `delegate_task` only with a provider.
        every { settings.allowedHttpDomains } returns flowOf(listOf("example.org"))
        every { settings.blockNetworkFromLocalModel } returns flowOf(false)

        val apiKeys = mockk<ApiKeyRepository>()
        CloudProvider.entries.forEach { provider ->
            every { apiKeys.getApiKey(provider) } returns flowOf("key-${provider.id}")
            every { apiKeys.getBaseUrl(provider) } returns flowOf("http://10.0.0.2:1")
            every { apiKeys.getModel(provider) } returns flowOf("model-${provider.id}")
        }

        val appFunctions = mockk<LocalAppFunctionManager>()
        coEvery { appFunctions.getAvailableFunctions() } returns emptyList()
        coEvery { appFunctions.isDiscovered(any()) } returns false

        val searchTool = mockk<SearchTool>()
        every { searchTool.asAgentTool() } returns
            AgentTool(SearchTool.TOOL_NAME, SearchTool.TOOL_DESCRIPTION, "{}")

        return ToolRepositoryImpl(
            settings,
            settings,
            McpConnectionPool(mockk<McpClientFactory>(), settings),
            appFunctions,
            apiKeys,
            searchTool,
            emptyMap(),
        ).getAvailableTools()
    }
}
