package app.knotwork.android.presentation.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.presentation.ui.common.openDocumentation
import app.knotwork.design.screens.tools.BuiltInToolRow
import app.knotwork.design.screens.tools.McpConnectionState
import app.knotwork.design.screens.tools.McpServerRow
import app.knotwork.design.screens.tools.McpToolEntry
import app.knotwork.design.screens.tools.ToolsCallbacks
import app.knotwork.design.screens.tools.ToolsContent
import app.knotwork.design.screens.tools.ToolsViewState
import app.knotwork.design.screens.tools.ToolsVisualState

/**
 * Tools screen — two-section list (built-in / MCP). The add and edit
 * affordances open a dedicated full-screen `McpServerConfigScreen`
 * instead of an inline form, so the tall configuration body (URL +
 * Display name + Transport + Headers) has room to breathe.
 */
@Suppress("UnusedParameter") // onBack kept for nav-graph compatibility.
@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenToolDetail: (String) -> Unit = {},
    onAddMcpServer: () -> Unit = {},
    onEditMcpServer: (originalUrl: String) -> Unit = {},
    onOpenAllowedDomains: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val builtInTools by remember(uiState) {
        derivedStateOf {
            uiState.localTools.map { tool ->
                BuiltInToolRow(
                    id = tool.name,
                    name = tool.name.toFriendlyToolName(),
                    description = tool.description,
                    risk = ToolRiskResolution.forLocalTool(tool, uiState.toolRiskOverrides),
                    enabled = tool.name !in uiState.disabledAppFunctions,
                    // http_request owns a domain allowlist; surface the editor sub-row
                    // (and its live host count) only under that tool.
                    allowedDomainsCount = if (tool.name == HTTP_REQUEST_TOOL_NAME) {
                        uiState.allowedHttpDomainCount
                    } else {
                        null
                    },
                )
            }
        }
    }
    val mcpServers by remember(uiState) {
        derivedStateOf {
            uiState.mcpServers.map { snapshot ->
                McpServerRow(
                    id = snapshot.url,
                    url = snapshot.config.displayName,
                    toolCount = snapshot.tools.size,
                    latencyLabel = snapshot.status.toLabel(),
                    state = snapshot.status.toCatalogState(),
                    tools = snapshot.tools.map {
                        it.toEntry(
                            disabled = uiState.disabledMcpTools,
                            overrides = uiState.toolRiskOverrides,
                        )
                    },
                    expanded = snapshot.url in uiState.expandedServerUrls,
                )
            }
        }
    }

    val visualState = if (builtInTools.isEmpty() && mcpServers.isEmpty()) {
        ToolsVisualState.Empty
    } else {
        ToolsVisualState.Default
    }

    val viewState = ToolsViewState(
        visualState = visualState,
        builtInTools = builtInTools,
        mcpServers = mcpServers,
    )

    val context = LocalContext.current
    val callbacks = ToolsCallbacks(
        onToolToggle = { id, enabled -> viewModel.toggleLocalTool(toolName = id, isEnabled = enabled) },
        onToolClick = onOpenToolDetail,
        onServerRemove = { serverId -> viewModel.removeMcpServer(url = serverId) },
        onServerEdit = onEditMcpServer,
        onServerExpandToggle = { serverId -> viewModel.toggleServerExpanded(serverUrl = serverId) },
        onServerRefresh = { serverId -> viewModel.refreshServer(serverUrl = serverId) },
        onMcpToolToggle = { id, enabled -> viewModel.toggleMcpTool(toolId = id, isEnabled = enabled) },
        onMcpToolClick = onOpenToolDetail,
        onAddServerOpen = onAddMcpServer,
        onOpenAllowedDomains = onOpenAllowedDomains,
        onErrorRetry = { /* unreachable: discovery errors surface per-server, not as a top-level state. */ },
        onOpenDocumentation = { openDocumentation(context, DocumentationLinks.ID_MCP_SETUP) },
    )

    ToolsContent(
        state = viewState,
        callbacks = callbacks,
        modifier = modifier.testTag(tag = TOOLS_ROOT_TEST_TAG),
    )
}

private fun McpConnectionStatus.toCatalogState(): McpConnectionState = when (this) {
    McpConnectionStatus.Connecting -> McpConnectionState.Syncing
    McpConnectionStatus.Connected -> McpConnectionState.Connected
    is McpConnectionStatus.Error -> McpConnectionState.Error
}

private fun McpConnectionStatus.toLabel(): String = when (this) {
    McpConnectionStatus.Connecting -> "connecting…"
    McpConnectionStatus.Connected -> "ok"
    is McpConnectionStatus.Error -> reason
}

/**
 * Projects an [McpTool] to the catalog's [McpToolEntry] for rendering as
 * a nested row underneath the server header.
 *
 * The risk goes through [ToolRiskResolution] rather than the advertised value,
 * so a level the user set on the detail screen shows here too. Reading the
 * server's own value would leave this row saying *Sensitive* about a tool the
 * detail screen — and the approval gate — already treat as read-only.
 *
 * @param disabled Ids the user has paused.
 * @param overrides The user's per-tool risk decisions.
 * @return The catalog row model.
 */
private fun McpTool.toEntry(disabled: Set<String>, overrides: Map<String, ToolRisk>): McpToolEntry = McpToolEntry(
    id = id,
    name = name,
    description = description,
    risk = ToolRiskResolution.forMcpTool(this, overrides),
    enabled = id !in disabled,
)

/**
 * Trims AppFunction-shaped tool ids (`<pkg>/<FQN>#invoke`) down to the
 * simple class name so the list row reads at a glance.
 */
private fun String.toFriendlyToolName(): String {
    val afterSlash = substringAfterLast(delimiter = "/")
    val beforeHash = afterSlash.substringBefore(delimiter = "#")
    val simple = beforeHash.substringAfterLast(delimiter = ".")
    return simple.ifBlank { this }
}

/** Built-in tool name that owns the domain allowlist editor sub-row. */
private const val HTTP_REQUEST_TOOL_NAME = "http_request"

/** TestTag applied to the tools screen root. */
internal const val TOOLS_ROOT_TEST_TAG = "tools_screen_root"
