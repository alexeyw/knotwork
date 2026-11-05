package app.knotwork.android.presentation.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.data.repositories.McpServerRepositoryImpl
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.presentation.common.JsonLayout
import app.knotwork.design.screens.tools.ToolDetailCallbacks
import app.knotwork.design.screens.tools.ToolDetailContent
import app.knotwork.design.screens.tools.ToolDetailViewState
import app.knotwork.design.screens.tools.ToolDetailVisualState
import app.knotwork.design.screens.tools.ToolRiskUi

/**
 * Per-tool detail screen.
 *
 * Branches on the [toolId] prefix:
 *
 *  - `mcp:…` → resolves an [app.knotwork.android.domain.models.McpTool] from
 *    `ToolsViewModel.findMcpTool`. Renders the server-supplied JSON Schema
 *    verbatim and the enabled-toggle is wired to
 *    `ToolSettings.disabledMcpTools`.
 *  - anything else → treats the id as an AppFunction name and pulls the
 *    matching `AgentTool` from `ToolsViewModel.uiState.localTools`. The
 *    schema preview now renders the real `AgentTool.parameters` (was a
 *    cosmetic placeholder before this task).
 *
 * @param toolId stable tool identifier; route argument from `AppNavGraph`.
 * @param onBack pop the back stack.
 */
@Composable
fun ToolDetailScreen(
    toolId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isMcp = remember(toolId) { toolId.startsWith(McpServerRepositoryImpl.MCP_ID_PREFIX) }

    val viewState = if (isMcp) {
        val mcpTool = remember(toolId, uiState.mcpServers) { viewModel.findMcpTool(toolId = toolId) }
        val owningStatus = remember(toolId, uiState.mcpServers) { findOwningServerStatus(uiState, toolId) }
        val enabled by remember(toolId, uiState.disabledMcpTools) {
            derivedStateOf { toolId !in uiState.disabledMcpTools }
        }
        ToolDetailViewState(
            visualState = when {
                mcpTool != null -> mcpTool.inputSchemaJson.visualStateForSchema()
                owningStatus is McpConnectionStatus.Connecting -> ToolDetailVisualState.Loading
                else -> ToolDetailVisualState.SchemaError
            },
            toolName = mcpTool?.name ?: toolId,
            description = mcpTool?.description
                ?: owningStatus?.takeIf { it is McpConnectionStatus.Error || it == McpConnectionStatus.Interrupted }
                    ?.let { stringResource(R.string.tool_detail_server_error, it.toLabel(LocalContext.current)) }
                ?: "",
            serverDisplayName = mcpTool?.serverUrl ?: stringResource(R.string.tool_detail_mcp_tool),
            schemaJson = remember(mcpTool) { mcpTool?.inputSchemaJson?.let(::laidOutSchema) },
            lastUsed = null,
            enabled = enabled,
            // Keyed per server, exactly as `ToolRepositoryImpl.getRisk` looks it
            // up: the id already carries the server hash, so two servers
            // advertising the same tool name stay two separate decisions.
            risk = ToolRiskUi.Editable(ToolRiskResolution.forOverridable(toolId, uiState.toolRiskOverrides)),
        )
    } else {
        val tool = remember(toolId, uiState.localTools) {
            uiState.localTools.firstOrNull { it.name == toolId }
        }
        val enabled by remember(toolId, uiState.disabledAppFunctions) {
            derivedStateOf { toolId !in uiState.disabledAppFunctions }
        }
        ToolDetailViewState(
            visualState = tool?.parameters?.visualStateForSchema() ?: ToolDetailVisualState.Default,
            toolName = tool?.name ?: toolId,
            description = tool?.description.orEmpty(),
            serverDisplayName = stringResource(R.string.tool_detail_local_tool),
            schemaJson = remember(tool) { tool?.parameters?.takeIf { it.isNotBlank() }?.let(::laidOutSchema) ?: "{}" },
            lastUsed = null,
            enabled = enabled,
            // A built-in's risk is resolved from code before any override is
            // read (`ToolRepositoryImpl.getRisk` returns on `builtinRisk`), so
            // only a discovered AppFunction gets a control here. Offering one
            // for a built-in would be a control that cannot act.
            risk = if (ToolRiskResolution.isOverridable(tool)) {
                ToolRiskUi.Editable(ToolRiskResolution.forOverridable(toolId, uiState.toolRiskOverrides))
            } else {
                ToolRiskUi.Fixed(ToolRiskResolution.forLocalTool(tool, uiState.toolRiskOverrides))
            },
        )
    }

    val callbacks = ToolDetailCallbacks(
        onBack = onBack,
        onToggle = { isEnabled ->
            if (isMcp) {
                viewModel.toggleMcpTool(toolId = toolId, isEnabled = isEnabled)
            } else {
                viewModel.toggleLocalTool(toolName = toolId, isEnabled = isEnabled)
            }
        },
        onRiskChange = { chosen ->
            viewModel.setToolRisk(toolKey = toolId, risk = with(ToolRiskResolution) { chosen.toDomain() })
        },
    )

    ToolDetailContent(
        state = viewState,
        callbacks = callbacks,
        modifier = modifier.testTag(tag = TOOL_DETAIL_ROOT_TEST_TAG),
    )
}

/**
 * Finds the connection status of the server that owns [toolId] (if any).
 *
 * The id format is `mcp:<sha8(serverUrl)>:<toolName>`, so we re-hash every
 * known server URL and compare prefixes. Returns `null` when no known
 * server matches — e.g. the user navigated via a stale deep link after
 * removing the originating server.
 */
private fun findOwningServerStatus(state: ToolsUiState, toolId: String): McpConnectionStatus? {
    val prefix = McpServerRepositoryImpl.MCP_ID_PREFIX
    if (!toolId.startsWith(prefix)) return null
    return state.mcpServers.firstOrNull { snapshot ->
        toolId.startsWith(McpServerRepositoryImpl.mcpToolId(serverUrl = snapshot.url, toolName = "").trimEnd(':'))
    }?.status
}

/**
 * Decides which [ToolDetailVisualState] applies given a schema string.
 * Blank → `Default` (renders the empty `{}` placeholder); text that is not a
 * JSON object by the grammar the schema is laid out with ([JsonLayout]) →
 * `SchemaError`; otherwise `Default`. The `Loading` state is reserved for the
 * MCP path while the repository fetch is still in flight.
 */
internal fun String.visualStateForSchema(): ToolDetailVisualState {
    val trimmed = trim()
    return when {
        trimmed.isEmpty() -> ToolDetailVisualState.Default
        trimmed.startsWith('{') && JsonLayout.isValid(trimmed) -> ToolDetailVisualState.Default
        else -> ToolDetailVisualState.SchemaError
    }
}

/**
 * A tool's parameter schema as the detail screen shows it: laid out one key per
 * line by [JsonLayout.indented], the layout every JSON surface shares. A server's
 * schema arrives minified on one line; text that is not JSON is returned as it
 * came, and [visualStateForSchema] shows the error state for it instead.
 *
 * @param schema the schema as the tool declares it.
 * @return the text the schema preview draws.
 */
internal fun laidOutSchema(schema: String): String = JsonLayout.indented(schema.trim())

/** TestTag applied to the tool-detail screen root. */
internal const val TOOL_DETAIL_ROOT_TEST_TAG = "tool_detail_root"
