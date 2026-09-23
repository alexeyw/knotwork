package app.knotwork.android.presentation.ui.tools

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.services.McpToolRouting

/**
 * UI state for the Tools screen.
 *
 * @property mcpServers per-server snapshot for the MCP section: full
 * [McpServerConfig], connection status, and the tool list (when the
 * connection succeeded).
 * @property disabledAppFunctions ids of locally registered AppFunctions
 * the user has paused (stored in `SettingsRepository.disabledAppFunctions`).
 * @property disabledMcpTools ids (`McpTool.id`) of MCP tools the user has
 * paused (stored in `SettingsRepository.disabledMcpTools`).
 * @property localTools all AppFunctions discovered on-device by
 * `ToolRepository.getAllLocalTools`. Used both to render the
 * "Built-in" section and to source `AgentTool.parameters` for the
 * detail screen's schema preview.
 * @property expandedServerUrls server URLs currently expanded in the
 * UI; used to drive the chevron and the nested tool-row list. Defaults
 * to empty (every server starts collapsed).
 * @property allowedHttpDomainCount hosts currently on the `http_request`
 * allowlist; drives the count on that tool's sub-row.
 * @property toolRiskOverrides the user's per-tool risk decisions, keyed the way
 * `ToolRepository.getRisk` looks them up. An absent key means the gate's own
 * conservative default, not "no risk".
 */
data class ToolsUiState(
    val mcpServers: List<McpServerSnapshot> = emptyList(),
    val disabledAppFunctions: Set<String> = emptySet(),
    val disabledMcpTools: Set<String> = emptySet(),
    val localTools: List<AgentTool> = emptyList(),
    val expandedServerUrls: Set<String> = emptySet(),
    val allowedHttpDomainCount: Int = 0,
    val toolRiskOverrides: Map<String, ToolRisk> = emptyMap(),
) {
    /**
     * Why each MCP tool — keyed by [McpTool.id] — is not offered to the agent
     * although its server publishes it. A tool that is offered has no entry.
     *
     * Computed by [McpToolRouting], the rule `ToolRepository` routes calls and
     * builds the agent's catalogue with, so the screen cannot tell a different
     * story from the one the agent lives in. Servers are taken in settings order
     * and deduplicated by URL, as the repository does.
     */
    val mcpShadowing: Map<String, McpToolRouting.Shadow>
        get() {
            val servers = mcpServers.distinctBy { it.url }
            val catalogues = servers.map { server ->
                McpToolRouting.ServerCatalogue(
                    serverUrl = server.url,
                    toolNames = server.tools.mapTo(LinkedHashSet()) { it.name },
                    disabledToolNames = server.tools.filter {
                        it.id in disabledMcpTools
                    }.mapTo(mutableSetOf()) { it.name },
                )
            }
            val localNames = localTools.mapTo(mutableSetOf()) { it.name }
            return buildMap {
                servers.forEach { server ->
                    server.tools.forEach { tool ->
                        McpToolRouting.shadowOf(
                            toolName = tool.name,
                            serverUrl = server.url,
                            localToolNames = localNames,
                            servers = catalogues,
                        )?.let { put(tool.id, it) }
                    }
                }
            }
        }
}

/**
 * Per-server slice surfaced to the UI.
 *
 * @property config the persisted server configuration. The URL inside
 * [config] is the stable identity used by every callback and by the
 * repository's status flow.
 * @property status current connection status from
 * `McpServerRepository.observeConnectionStatus`.
 * @property tools tools advertised by the server on its last successful
 * `tools/list` fetch. Empty while [status] is
 * [McpConnectionStatus.Connecting] or [McpConnectionStatus.Error].
 */
data class McpServerSnapshot(val config: McpServerConfig, val status: McpConnectionStatus, val tools: List<McpTool>) {
    val url: String get() = config.url
}
