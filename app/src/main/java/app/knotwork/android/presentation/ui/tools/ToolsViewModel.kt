package app.knotwork.android.presentation.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.repositories.ToolSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * ViewModel for the Tools screen.
 *
 * Surfaces three streams:
 *
 *  - **Local tools** — loaded once from `ToolRepository.getAllLocalTools`.
 *  - **MCP servers** — combined from `ToolSettings.mcpServers`
 *    (full [McpServerConfig] per row) and the per-URL streams of
 *    `McpServerRepository`. Adding or editing a server immediately
 *    triggers a `tools/list` fetch with the persisted headers;
 *    removing one disconnects the underlying client.
 *  - **Disabled MCP tool ids** — separate set in
 *    `ToolSettings.disabledMcpTools`.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val toolSettings: ToolSettings,
    private val toolRepository: ToolRepository,
    private val mcpServerRepository: McpServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    private val serverObservers = ConcurrentHashMap<String, Job>()

    init {
        viewModelScope.launch {
            // Discovered on-device AppFunctions are shown alongside built-in tools
            // so the user can disable them. Visibility and agent-availability are
            // kept consistent: a tool hidden/disabled here is removed from the
            // agent catalogue and refused by the executor through the same
            // `disabledAppFunctions` set that the toggle writes to — a tool the
            // user cannot see and control is never silently callable in pipelines.
            val tools = toolRepository.getAllLocalTools()
            _uiState.update { it.copy(localTools = tools) }
        }

        toolSettings.mcpServers
            .onEach { configs -> reconcileServerSet(configs) }
            .launchIn(viewModelScope)

        toolSettings.disabledAppFunctions
            .onEach { disabled ->
                _uiState.update { it.copy(disabledAppFunctions = disabled) }
            }
            .launchIn(viewModelScope)

        toolSettings.disabledMcpTools
            .onEach { disabled ->
                _uiState.update { it.copy(disabledMcpTools = disabled) }
            }
            .launchIn(viewModelScope)

        // Drives the risk segmented control on the tool-detail screen. Read even
        // when no override exists: an absent entry is not "no risk", it is the
        // conservative SENSITIVE default the approval gate itself falls back to.
        toolSettings.toolRiskOverrides
            .onEach { overrides ->
                _uiState.update { it.copy(toolRiskOverrides = overrides) }
            }
            .launchIn(viewModelScope)

        // Drives the "Allowed domains · N hosts" sub-row count under http_request.
        toolSettings.allowedHttpDomains
            .onEach { domains ->
                _uiState.update { it.copy(allowedHttpDomainCount = domains.size) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Reconciles [configs] against the in-memory snapshot map. Snapshots
     * are inserted FIRST (with `Connecting` placeholder status) so the
     * async observers / fetches launched immediately after find their
     * row when they fold updates back into the state. URLs no longer
     * present in [configs] are cancelled and disconnected.
     */
    private fun reconcileServerSet(configs: List<McpServerConfig>) {
        val urls = configs.mapTo(mutableSetOf()) { it.url }
        val existing = serverObservers.keys.toSet()
        val toRemove = existing - urls
        val toAdd = configs.filter { it.url !in existing }

        toRemove.forEach { url ->
            serverObservers.remove(url)?.cancel()
            viewModelScope.launch { mcpServerRepository.disconnect(serverUrl = url) }
        }

        // Capture the prior snapshot map BEFORE mutating state so we can detect
        // config drift (auth header swapped, transport flipped, …) and trigger
        // a fresh fetch for previously-known URLs whose config just changed.
        val previousByUrl = _uiState.value.mcpServers.associateBy { it.url }

        _uiState.update { state ->
            val existingByUrl = state.mcpServers.associateBy { it.url }
            val snapshots = configs.map { config ->
                val previous = existingByUrl[config.url]
                if (previous != null) {
                    previous.copy(config = config)
                } else {
                    McpServerSnapshot(
                        config = config,
                        status = McpConnectionStatus.Connecting,
                        tools = emptyList(),
                    )
                }
            }
            state.copy(
                mcpServers = snapshots,
                expandedServerUrls = state.expandedServerUrls.intersect(urls),
            )
        }

        toAdd.forEach { config ->
            serverObservers[config.url] = observeServer(config.url)
            fetchToolsAndUpdate(config = config, forceRefresh = false)
        }
        // Existing URLs whose persisted config differs from what the VM last
        // observed get a forced refetch. This is the on-save-edit path: the
        // user updated auth/transport/headers via `McpServerConfigScreen`, and
        // the fetch reconnects with the new config (the connection pool replaces
        // a connection opened with a different one) without waiting for a manual
        // Refresh. It is the only reconnect a save gets: the form does not
        // disconnect, so a save that changed nothing leaves the row as it was.
        configs.forEach { config ->
            val previous = previousByUrl[config.url] ?: return@forEach
            if (previous.config != config) {
                fetchToolsAndUpdate(config = config, forceRefresh = true)
            }
        }
    }

    private fun observeServer(url: String): Job = mcpServerRepository.observeConnectionStatus(serverUrl = url)
        .onEach { status -> updateServerStatus(url = url, status = status) }
        .launchIn(viewModelScope)

    /**
     * Launches a `fetchToolList` for [config] and merges the result
     * into the snapshot list. Success updates the `tools` field; failure
     * is observed indirectly via the status flow (the repository emits
     * [McpConnectionStatus.Error] on the same code path).
     */
    private fun fetchToolsAndUpdate(config: McpServerConfig, forceRefresh: Boolean) {
        viewModelScope.launch {
            val tools = mcpServerRepository.fetchToolList(config = config, forceRefresh = forceRefresh)
                .getOrElse { return@launch }
            _uiState.update { state ->
                state.copy(
                    mcpServers = state.mcpServers.map { snapshot ->
                        if (snapshot.url == config.url) snapshot.copy(tools = tools) else snapshot
                    },
                )
            }
        }
    }

    private fun updateServerStatus(url: String, status: McpConnectionStatus) {
        _uiState.update { state ->
            state.copy(
                mcpServers = state.mcpServers.map { snapshot ->
                    if (snapshot.url == url) snapshot.copy(status = status) else snapshot
                },
            )
        }
    }

    /** Removes an MCP server URL and disconnects the underlying client. */
    fun removeMcpServer(url: String) {
        viewModelScope.launch {
            toolSettings.removeMcpServer(url = url)
        }
    }

    /** Toggles whether [serverUrl]'s tool list is expanded in the UI. */
    fun toggleServerExpanded(serverUrl: String) {
        _uiState.update { state ->
            val current = state.expandedServerUrls
            val next = if (serverUrl in current) current - serverUrl else current + serverUrl
            state.copy(expandedServerUrls = next)
        }
    }

    /**
     * Re-fetches the tool list for [serverUrl] bypassing the repository's
     * 5-minute cache. Wired to the trailing refresh icon in
     * `McpServerRow`.
     */
    fun refreshServer(serverUrl: String) {
        val config = _uiState.value.mcpServers.firstOrNull { it.url == serverUrl }?.config ?: return
        fetchToolsAndUpdate(config = config, forceRefresh = true)
    }

    /**
     * Switches one local tool (built-in or discovered AppFunction) on or off.
     *
     * Only this tool's entry changes, and the store applies the change to the
     * set it holds: the copy in [uiState] can be a write behind, and writing
     * that copy back would undo a switch flipped just before.
     *
     * @param toolName The tool's name, as `disabledAppFunctions` keys it.
     * @param isEnabled The switch's new position.
     */
    fun toggleLocalTool(toolName: String, isEnabled: Boolean) {
        viewModelScope.launch {
            toolSettings.setAppFunctionsEnabled(functions = setOf(toolName), enabled = isEnabled)
        }
    }

    /**
     * Switches one MCP tool on or off. Only this tool's entry changes; see
     * [toggleLocalTool] for why the change is not computed from [uiState].
     *
     * @param toolId The tool's `McpTool.id`.
     * @param isEnabled The switch's new position.
     */
    fun toggleMcpTool(toolId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            toolSettings.setMcpToolsEnabled(toolIds = setOf(toolId), enabled = isEnabled)
        }
    }

    /**
     * Switches every tool [serverUrl]'s row lists on or off, in one write.
     *
     * "Every tool" is the list the row shows — the server's last `tools/list`
     * answer. Ids remembered for tools the server no longer lists keep their
     * state, as they do when tools are switched one by one. The result is the
     * same set those single switches would leave, so the agent's catalogue and
     * the name routing treat it the same way: a tool switched off here is out
     * of the catalogue, and a name it shared falls to the next server that has
     * it on.
     *
     * The row is expanded as well, so the switches show the result and the one
     * tool wanted from a server is a tap away. A server that lists no tools
     * changes nothing.
     *
     * @param serverUrl The server's URL, the row's identity.
     * @param enabled `true` to switch its tools on, `false` to switch them off.
     */
    fun setServerToolsEnabled(serverUrl: String, enabled: Boolean) {
        val toolIds = _uiState.value.mcpServers.firstOrNull { it.url == serverUrl }
            ?.tools
            ?.mapTo(mutableSetOf()) { it.id }
            .orEmpty()
        if (toolIds.isEmpty()) return
        _uiState.update { it.copy(expandedServerUrls = it.expandedServerUrls + serverUrl) }
        viewModelScope.launch {
            toolSettings.setMcpToolsEnabled(toolIds = toolIds, enabled = enabled)
        }
    }

    /**
     * Persists the user's risk decision for one tool.
     *
     * [toolKey] is whatever `ToolRepository.getRisk` will look up later: the
     * bare tool name for a discovered AppFunction, the `mcp:<sha8(url)>:<name>`
     * id for an MCP tool. Passing a built-in tool's name would persist an entry
     * the gate never reads, which is why the screen only offers the control
     * where the override applies.
     *
     * @param toolKey Override key, matching the gate's lookup.
     * @param risk The level the user chose.
     */
    fun setToolRisk(toolKey: String, risk: ToolRisk) {
        viewModelScope.launch { toolSettings.setToolRiskOverride(toolKey = toolKey, risk = risk) }
    }

    /**
     * Resolves an MCP tool by its stable id (matches
     * `McpServerRepositoryImpl.mcpToolId`). Returns `null` if no server
     * in [uiState] currently advertises a tool with this id.
     */
    fun findMcpTool(toolId: String): McpTool? {
        val servers = _uiState.value.mcpServers
        for (server in servers) {
            server.tools.firstOrNull { it.id == toolId }?.let { return it }
        }
        return null
    }
}
