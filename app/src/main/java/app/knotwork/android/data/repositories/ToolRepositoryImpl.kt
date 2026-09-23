package app.knotwork.android.data.repositories

import app.knotwork.android.data.mcp.McpClient
import app.knotwork.android.data.mcp.McpConnectionPool
import app.knotwork.android.data.tools.local.LocalAppFunctionManager
import app.knotwork.android.data.tools.local.SearchTool
import app.knotwork.android.data.tools.local.executors.AppendFileExecutor
import app.knotwork.android.data.tools.local.executors.DeleteFileExecutor
import app.knotwork.android.data.tools.local.executors.EditFileExecutor
import app.knotwork.android.data.tools.local.executors.FindFilesExecutor
import app.knotwork.android.data.tools.local.executors.HttpRequestExecutor
import app.knotwork.android.data.tools.local.executors.ListFilesExecutor
import app.knotwork.android.data.tools.local.executors.ReadFileExecutor
import app.knotwork.android.data.tools.local.executors.WriteFileExecutor
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.ToolSource
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.HttpRequestPolicy
import app.knotwork.android.domain.services.McpToolRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/**
 * Implementation of [ToolRepository] that merges the agent's tool sources —
 * built-ins, AppFunctions discovered on the device, and the tools advertised by
 * the MCP servers configured in settings — and dispatches calls to them.
 *
 * It does **not** own the MCP connections: live [McpClient] instances belong to
 * the shared [McpConnectionPool], which `McpServerRepositoryImpl` uses too. That
 * is deliberate — while the two kept separate pools, the Tools screen's health
 * badge could describe a session the agent's next call would not use.
 *
 * @property localToolExecutors Hilt multibinding map keyed by tool name. Each entry
 * implements one of the built-in agent tools (e.g. `schedule_task`, `delegate_task`,
 * `search_tool`). New built-ins are added by registering another implementation in DI;
 * an unknown name now fails fast instead of returning a fake success string.
 */
class ToolRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mcpConnectionPool: McpConnectionPool,
    private val localAppFunctionManager: LocalAppFunctionManager,
    private val apiKeyRepository: ApiKeyRepository,
    private val searchTool: SearchTool,
    private val localToolExecutors: Map<String, @JvmSuppressWildcards LocalToolExecutor>,
) : ToolRepository {

    private suspend fun getBuiltinTools(): List<AgentTool> {
        val availableModels = mutableListOf<CloudProvider>()
        if (!apiKeyRepository.getOpenAIKey().firstOrNull().isNullOrBlank()) availableModels.add(CloudProvider.OPENAI)
        if (!apiKeyRepository.getAnthropicKey().firstOrNull().isNullOrBlank()) {
            availableModels.add(CloudProvider.ANTHROPIC)
        }
        if (!apiKeyRepository.getGoogleKey().firstOrNull().isNullOrBlank()) availableModels.add(CloudProvider.GOOGLE)
        if (!apiKeyRepository.getDeepSeekKey().firstOrNull().isNullOrBlank()) {
            availableModels.add(CloudProvider.DEEPSEEK)
        }
        if (!apiKeyRepository.getOllamaBaseUrl().firstOrNull().isNullOrBlank()) {
            availableModels.add(CloudProvider.OLLAMA)
        }

        val scheduleTool = AgentTool(
            name = "schedule_task",
            description = "Schedules a task to be executed by the agent in the background. " +
                "intervalHours: >0 for periodic, 0 for one-time. " +
                "delayMinutes: >0 for delayed execution.",
            parameters = """
                {
                  "type": "object",
                  "properties": {
                    "prompt": { "type": "string", "description": "The prompt or task description" },
                    "intervalHours": { "type": "integer", "description": "Interval in hours for periodic tasks. Default 0." },
                    "delayMinutes": { "type": "integer", "description": "Delay in minutes for one-time tasks. Default 0." }
                  },
                  "required": ["prompt"]
                }
            """.trimIndent(),
            risk = ToolRisk.SENSITIVE,
        )

        val baseTools = mutableListOf(
            scheduleTool,
            searchTool.asAgentTool().copy(risk = ToolRisk.READ_ONLY),
            workspaceReadTool(
                name = ReadFileExecutor.TOOL_NAME,
                description = ReadFileExecutor.DESCRIPTION,
                parameters = ReadFileExecutor.PARAMETERS,
            ),
            workspaceReadTool(
                name = ListFilesExecutor.TOOL_NAME,
                description = ListFilesExecutor.DESCRIPTION,
                parameters = ListFilesExecutor.PARAMETERS,
            ),
            workspaceReadTool(
                name = FindFilesExecutor.TOOL_NAME,
                description = FindFilesExecutor.DESCRIPTION,
                parameters = FindFilesExecutor.PARAMETERS,
            ),
            // Mutating workspace tools. write_file / edit_file are SENSITIVE (a
            // scoped, reversible change inside the sandbox); delete_file is
            // DESTRUCTIVE (irreversible) and always routes through the typed
            // confirmation path. The risk here is the single source the HITL gate
            // reads via `getRisk`.
            workspaceWriteTool(
                name = WriteFileExecutor.TOOL_NAME,
                description = WriteFileExecutor.DESCRIPTION,
                parameters = WriteFileExecutor.PARAMETERS,
                risk = ToolRisk.SENSITIVE,
            ),
            workspaceWriteTool(
                name = EditFileExecutor.TOOL_NAME,
                description = EditFileExecutor.DESCRIPTION,
                parameters = EditFileExecutor.PARAMETERS,
                risk = ToolRisk.SENSITIVE,
            ),
            workspaceWriteTool(
                name = AppendFileExecutor.TOOL_NAME,
                description = AppendFileExecutor.DESCRIPTION,
                parameters = AppendFileExecutor.PARAMETERS,
                risk = ToolRisk.SENSITIVE,
            ),
            workspaceWriteTool(
                name = DeleteFileExecutor.TOOL_NAME,
                description = DeleteFileExecutor.DESCRIPTION,
                parameters = DeleteFileExecutor.PARAMETERS,
                risk = ToolRisk.DESTRUCTIVE,
            ),
            // Outbound HTTP. Always present in the built-in set so executeTool and
            // getRisk can route to it, but published to the agent (getAvailableTools)
            // only when the domain allowlist is non-empty. The declared risk here is
            // the conservative maximum for display; the HITL gate resolves the real
            // per-method risk via getRisk (GET → SENSITIVE, writes → DESTRUCTIVE).
            AgentTool(
                name = HttpRequestExecutor.TOOL_NAME,
                description = HttpRequestExecutor.DESCRIPTION,
                parameters = HttpRequestExecutor.PARAMETERS,
                risk = ToolRisk.DESTRUCTIVE,
            ),
        )

        if (availableModels.isEmpty()) {
            return baseTools
        }

        val modelsString = availableModels.joinToString(", ") { it.id }
        val defaultModel = availableModels.first().id
        val delegateTool = AgentTool(
            name = "delegate_task",
            description = "Delegates a complex or specialized task to an external LLM and saves the " +
                "result to memory. ONLY use this tool if you need cloud reasoning.",
            parameters = """
                {
                  "type": "object",
                  "properties": {
                    "taskDescription": { "type": "string", "description": "A detailed explanation of the task to be delegated" },
                    "targetModel": { "type": "string", "description": "The external model to use. MUST be one of: $modelsString. Default is $defaultModel." }
                  },
                  "required": ["taskDescription"]
                }
            """.trimIndent(),
            risk = ToolRisk.SENSITIVE,
        )

        baseTools.add(delegateTool)
        return baseTools
    }

    /**
     * Builds an [AgentTool] for a read-only workspace file tool (`read_file`,
     * `list_files`, `find_files`). All three are [ToolRisk.READ_ONLY]: reading or
     * listing the agent's own jailed sandbox neither mutates state nor reaches
     * outside the device, so they pass the HITL gate without a confirmation under
     * the default policy.
     */
    private fun workspaceReadTool(name: String, description: String, parameters: String): AgentTool = AgentTool(
        name = name,
        description = description,
        parameters = parameters,
        risk = ToolRisk.READ_ONLY,
    )

    /**
     * Builds an [AgentTool] for a mutating workspace file tool (`write_file`,
     * `edit_file`, `delete_file`). Unlike the read tools these carry an explicit
     * [risk] — [ToolRisk.SENSITIVE] for the reversible writes, [ToolRisk.DESTRUCTIVE]
     * for the irreversible delete — which the HITL gate reads back through
     * [getRisk] to decide whether (and how strictly) to confirm with the user.
     */
    private fun workspaceWriteTool(name: String, description: String, parameters: String, risk: ToolRisk): AgentTool =
        AgentTool(
            name = name,
            description = description,
            parameters = parameters,
            risk = risk,
        )

    /**
     * Reads the persisted MCP server list and **deduplicates by URL**, keeping
     * the first occurrence. Defensive measure against a known issue in
     * [app.knotwork.android.data.local.SettingsManager.updateMcpServer], which
     * replaces by index without checking for collisions: editing server A's
     * URL to match an existing server B can persist `[B, B]`. The list
     * iteration is the source of truth for ordering, so the dedup must
     * happen here — otherwise a duplicate URL would trigger a duplicate
     * `executeTool` call against the same connected client (catastrophic for
     * non-idempotent tools) and emit duplicate tools from `getAvailableTools`.
     */
    private suspend fun distinctMcpConfigs(): List<McpServerConfig> =
        settingsRepository.mcpServers.first().distinctBy { it.url }

    /**
     * Brings the shared [McpConnectionPool] in line with
     * [SettingsRepository.mcpServers] before this repository routes anything
     * through it: unknown servers are connected, servers whose config changed
     * (auth, transport, headers) are reconnected so the new settings take
     * effect, and servers no longer persisted are disconnected and dropped.
     *
     * The pool is shared with `McpServerRepositoryImpl`, which drives the Tools
     * screen's health indicator — so the session reported as healthy there is
     * the same session a tool call lands on here. Connection failures are
     * swallowed by the pool: the URL is left out so the next sync retries.
     */
    private suspend fun syncMcpClients() = mcpConnectionPool.reconcile(distinctMcpConfigs())

    /**
     * Retrieves all locally available tools — built-in tools first (stable ordering for
     * prompt engineering), then AppFunctions discovered via
     * [LocalAppFunctionManager.getAvailableFunctions].
     *
     * Built-ins always win on name collisions: a discovered AppFunction whose id matches
     * a built-in (`schedule_task`, `search_tool`, `delegate_task`) is dropped with a
     * `Timber.w` to preserve the executor mapping in [executeTool] and the risk classification
     * in `getRisk`. This keeps the deterministic built-in path intact even when the host
     * device exposes AppFunctions advertising the same identifier.
     *
     * @return A list of [AgentTool] representing the local tools available.
     */
    override suspend fun getAllLocalTools(): List<AgentTool> {
        val builtins = getBuiltinTools()
        val builtinNames = builtins.mapTo(mutableSetOf()) { it.name }
        val appFunctions = localAppFunctionManager.getAvailableFunctions()
            .filter { tool ->
                if (tool.name in builtinNames) {
                    Timber.w("AppFunction %s collides with built-in tool; built-in wins", tool.name)
                    false
                } else {
                    true
                }
            }
            // Tag provenance so UI surfaces can distinguish discovered AppFunctions
            // from hand-written built-ins (the Tools screen hides the former).
            .map { it.copy(source = ToolSource.APP_FUNCTION) }
        return builtins + appFunctions
    }

    /**
     * Retrieves all available tools, including both local tools (not disabled) and tools
     * fetched from connected MCP servers.
     *
     * The `disabledAppFunctions` setting filters the merged local catalogue — it gates
     * both built-ins and discovered AppFunctions by name. The `disabledMcpTools` setting
     * filters MCP-advertised tools by their stable
     * `mcp:<sha8(serverUrl)>:<toolName>` id (see [McpServerRepositoryImpl.mcpToolId]).
     *
     * Every name appears **once**, and it is the entry a call by that name reaches
     * ([McpToolRouting]): an MCP tool whose name a local tool takes — disabled or
     * withheld ones included — is left out, and so is one a server earlier in the
     * user's order serves. Listing either would put one tool's description in the
     * prompt for a call that runs another.
     *
     * Two built-ins are additionally withheld by a restriction of their own: `http_request`
     * while no domain is allowlisted, and `search_tool` while "Block network from local
     * model" is on.
     *
     * @return A list of [AgentTool] representing all tools currently available to the agent.
     */
    override suspend fun getAvailableTools(): List<AgentTool> {
        val servers = connectedMcpServers()
        val disabledLocal = settingsRepository.disabledAppFunctions.first()
        val disabledMcp = settingsRepository.disabledMcpTools.first()
        // http_request is its own master switch: while no domain is allowlisted the
        // tool is hidden from the agent entirely (a direct call is still refused by
        // the executor). This keeps the read_file → http_request exfiltration channel
        // closed by default until the user opts a destination in.
        val httpDisabled = settingsRepository.allowedHttpDomains.first().isEmpty()
        // "Block network from local model" covers the built-in search tool: while the
        // restriction is on the tool is withheld from the catalogue, so the model is not
        // offered a path off the device it has no confirmation gate for. Hiding it is only
        // half the answer — a pipeline node bound to `search_tool` by name never reads this
        // catalogue — so the tool refuses the call itself as well (see `SearchTool`).
        val localOnlyMode = settingsRepository.blockNetworkFromLocalModel.first()
        val allLocal = getAllLocalTools()
        val availableLocal = allLocal.filter { tool ->
            tool.name !in disabledLocal &&
                !(httpDisabled && tool.name == HttpRequestExecutor.TOOL_NAME) &&
                !(localOnlyMode && tool.name == SearchTool.TOOL_NAME)
        }

        // The local names are taken from the unfiltered list on purpose: a disabled or
        // withheld local tool still owns its name — `executeTool` dispatches it (and
        // refuses it) before it ever looks at MCP.
        val localNames = allLocal.mapTo(mutableSetOf()) { it.name }
        val catalogues = servers.map { it.catalogue(disabledMcp) }
        val mcpTools = servers.flatMap { server ->
            server.tools.filter { tool ->
                val offered = McpToolRouting.isOffered(
                    toolName = tool.name,
                    serverUrl = server.config.url,
                    localToolNames = localNames,
                    servers = catalogues,
                )
                if (!offered && tool.name in localNames) {
                    Timber.w("MCP tool %s collides with a tool on the device; the device tool wins", tool.name)
                }
                offered
            }
        }

        return availableLocal + mcpTools
    }

    /**
     * Executes a tool by its name with the given arguments.
     *
     * Dispatch order:
     *  1. Built-in tools — handled by a [LocalToolExecutor] registered in DI.
     *  2. AppFunctions discovered via [LocalAppFunctionManager.invokeByName] — the manager
     *     owns the end-to-end pipeline (codec encode → `ExecuteAppFunctionRequest` →
     *     system call → codec decode). All Android AppFunctions types stay encapsulated
     *     behind that surface; this method only sees plain `String` in and out.
     *  3. MCP — forwarded to the one server that serves the name ([McpToolRouting]).
     *
     * @param name The name of the tool to execute.
     * @param arguments A JSON string containing the arguments required by the tool.
     * @param context Engine-supplied [ToolExecutionContext] carrying trusted environment
     *   values. The session id is forwarded to built-in executors only; the AppFunction
     *   and MCP protocols have no session notion. The gated risk is checked on the MCP
     *   branch, the one whose serving tool can change between the gate and the call.
     * @return A string representing the result of the tool execution.
     * @throws IllegalArgumentException If the tool is disabled, has no executor registered,
     *   or is not found across active providers.
     * @throws IllegalStateException If a system-level AppFunction call reports a failure
     *   (re-thrown verbatim by [LocalAppFunctionManager.invokeByName]), or if the MCP
     *   server now serving [name] carries a different risk than the gate decided on.
     */
    override suspend fun executeTool(name: String, arguments: String, context: ToolExecutionContext): String {
        val builtinTools = getBuiltinTools()
        val disabled = settingsRepository.disabledAppFunctions.first()
        if (builtinTools.any { it.name == name }) {
            if (name in disabled) {
                throw IllegalArgumentException("Tool $name is disabled")
            }

            val executor = localToolExecutors[name]
                ?: throw IllegalArgumentException("Local tool $name has no executor registered")
            return executor.execute(arguments, context)
        }

        val builtinNames = builtinTools.mapTo(mutableSetOf()) { it.name }
        if (name !in builtinNames && localAppFunctionManager.isDiscovered(name)) {
            if (name in disabled) {
                throw IllegalArgumentException("Tool $name is disabled")
            }
            return localAppFunctionManager.invokeByName(name, arguments)
        }

        return executeMcpTool(name = name, arguments = arguments, gatedRisk = context.gatedRisk)
    }

    /**
     * MCP-side dispatch for [executeTool]. The call goes to exactly **one** server:
     * the one [routeMcpTool] resolves, which is the same resolution [getRisk] gave
     * the gate. There is no failover to another server when that one throws — its
     * error is the answer. Retrying elsewhere would run the call under the user's
     * decision for a different server, and could repeat a side effect: a call that
     * timed out may still be running where it was sent.
     *
     * The resolution is still made twice — once for [getRisk], once here — and
     * the pool can reconnect a server in between, handing the name to a server
     * that was down a moment ago. [gatedRisk] closes that: when the server serving
     * the name now carries a different risk than the one the gate decided on, the
     * call is refused rather than run under the old decision.
     *
     * @param name The tool name.
     * @param arguments The JSON argument string.
     * @param gatedRisk The risk the gate decided on, or `null` outside the gate.
     * @return The serving server's result.
     * @throws IllegalArgumentException when every server publishing [name] has it
     *   switched off (`is disabled`), or no server publishes it (`not found`).
     * @throws IllegalStateException when the serving server's risk differs from [gatedRisk].
     */
    private suspend fun executeMcpTool(name: String, arguments: String, gatedRisk: ToolRisk?): String {
        val server = when (val route = routeMcpTool(name)) {
            is McpRoute.Served -> route.server
            McpRoute.Disabled -> throw IllegalArgumentException("Tool $name is disabled")
            McpRoute.Unknown -> throw IllegalArgumentException("Tool $name not found across active providers")
        }
        if (gatedRisk != null) {
            val currentRisk = mcpRisk(server = server, toolName = name)
            if (currentRisk != gatedRisk) {
                Timber.w("MCP tool %s: serving risk %s differs from gated %s; refused", name, currentRisk, gatedRisk)
                // Two causes lead here and the message names both: a reconnect handed
                // the name to another server, or the user changed this server's risk
                // level while the approval card was up.
                throw IllegalStateException(
                    "MCP tool $name now resolves to risk level $currentRisk, not the $gatedRisk its approval " +
                        "check used — the serving server or its risk setting changed. The call was not made; " +
                        "run it again to re-check.",
                )
            }
        }
        return server.client.executeTool(name, arguments)
    }

    /**
     * Resolves the effective [ToolRisk] for a tool by name. See [ToolRepository.getRisk]
     * KDoc for the resolution contract.
     *
     * The lookup intentionally does **not** cache discovery / settings results: AppFunction
     * availability and risk overrides are user-controlled and must reflect the latest
     * device state on every call. The HITL gate executes this exactly once per tool
     * invocation, so the extra Flow read is not on a hot path.
     */
    override suspend fun getRisk(toolName: String, arguments: String): ToolRisk {
        // http_request is the one tool whose risk depends on the call, not just
        // the name: a read GET is SENSITIVE while a state-changing write is
        // DESTRUCTIVE. Resolve it from the same source the executor enforces with
        // (HttpRequestPolicy), falling back to the conservative DESTRUCTIVE when
        // the method cannot be parsed so an ambiguous call never under-prompts.
        if (toolName == HttpRequestExecutor.TOOL_NAME) {
            return httpRequestRisk(arguments)
        }

        val builtinRisk = getBuiltinTools().firstOrNull { it.name == toolName }?.risk
        if (builtinRisk != null) {
            return builtinRisk
        }

        if (localAppFunctionManager.isDiscovered(toolName)) {
            val overrides = settingsRepository.toolRiskOverrides.first()
            return overrides[toolName] ?: ToolRisk.SENSITIVE
        }

        // The same resolution `executeTool` dispatches with: the risk is the user's
        // decision for the server that will run the call, never for another server
        // publishing the same name.
        return when (val route = routeMcpTool(toolName)) {
            is McpRoute.Served -> mcpRisk(server = route.server, toolName = toolName)
            // No approval card for a call that cannot run: every server publishing
            // the name has it switched off.
            McpRoute.Disabled -> throw IllegalArgumentException("Tool $toolName is disabled")
            McpRoute.Unknown -> throw IllegalArgumentException("Unknown tool: $toolName")
        }
    }

    /**
     * The user's risk decision for [toolName] on [server], or the conservative
     * [ToolRisk.SENSITIVE] default. Keyed per server, not per bare name: a long
     * shared prefix is normal in MCP catalogues, and two servers publishing the same
     * `create_issue` stay independent decisions — the rule `disabledMcpTools` follows.
     */
    private suspend fun mcpRisk(server: ConnectedMcpServer, toolName: String): ToolRisk {
        val key = McpServerRepositoryImpl.mcpToolId(serverUrl = server.config.url, toolName = toolName)
        return settingsRepository.toolRiskOverrides.first()[key] ?: ToolRisk.SENSITIVE
    }

    /**
     * Resolves which connected server serves [toolName], by [McpToolRouting] — the
     * single resolution behind [getRisk] and [executeTool]. Local names are routed
     * by both callers before they get here.
     */
    private suspend fun routeMcpTool(toolName: String): McpRoute {
        val servers = connectedMcpServers()
        val disabledMcp = settingsRepository.disabledMcpTools.first()
        val servingUrl = McpToolRouting.servingServer(toolName, servers.map { it.catalogue(disabledMcp) })
        if (servingUrl != null) {
            return McpRoute.Served(servers.first { it.config.url == servingUrl })
        }
        return if (servers.any { server -> server.tools.any { it.name == toolName } }) {
            McpRoute.Disabled
        } else {
            McpRoute.Unknown
        }
    }

    /**
     * Syncs the pool and snapshots every connected server, in the user's order,
     * with the tools it publishes. A server whose `tools/list` fails counts as
     * publishing nothing, so routing moves on; cancellation is re-thrown.
     */
    private suspend fun connectedMcpServers(): List<ConnectedMcpServer> {
        syncMcpClients()
        // Walk the persisted config order rather than the pool's own map: its
        // iteration order is non-deterministic, and the user's ordering in
        // Settings → External providers decides which server serves a shared name.
        return distinctMcpConfigs().mapNotNull { config ->
            val client = mcpConnectionPool.peek(config.url) ?: return@mapNotNull null
            val tools = try {
                client.getTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "MCP tools/list failed for a configured server; routing skips it")
                emptyList()
            }
            ConnectedMcpServer(config = config, client = client, tools = tools)
        }
    }

    /**
     * One connected MCP server as the router sees it: the config it is keyed by,
     * the live client, and the tools it published when asked.
     */
    private class ConnectedMcpServer(val config: McpServerConfig, val client: McpClient, val tools: List<AgentTool>) {
        /** This server's catalogue for [McpToolRouting], with [disabledMcp] resolved to names. */
        fun catalogue(disabledMcp: Set<String>): McpToolRouting.ServerCatalogue {
            val names = tools.mapTo(LinkedHashSet()) { it.name }
            return McpToolRouting.ServerCatalogue(
                serverUrl = config.url,
                toolNames = names,
                disabledToolNames = names.filterTo(mutableSetOf()) { name ->
                    McpServerRepositoryImpl.mcpToolId(serverUrl = config.url, toolName = name) in disabledMcp
                },
            )
        }
    }

    /** Outcome of routing an MCP tool name. */
    private sealed interface McpRoute {
        /** [server] serves the name. */
        class Served(val server: ConnectedMcpServer) : McpRoute

        /** Servers publish the name, but every one of them has it switched off. */
        data object Disabled : McpRoute

        /** No connected server publishes the name. */
        data object Unknown : McpRoute
    }

    /**
     * Resolves the per-method risk of an `http_request` call from its [arguments].
     * Parses the `method` field and maps it via [HttpRequestPolicy.methodRisk];
     * a missing, unknown, or malformed method falls back to
     * [ToolRisk.DESTRUCTIVE] so an unparsable call is gated at the strictest
     * level rather than slipping through with a weaker prompt.
     */
    private fun httpRequestRisk(arguments: String): ToolRisk {
        val method = try {
            JSONObject(arguments).optString("method", "GET")
        } catch (e: JSONException) {
            Timber.w(e, "http_request risk lookup: malformed arguments; defaulting to DESTRUCTIVE")
            return ToolRisk.DESTRUCTIVE
        }
        return HttpRequestPolicy.methodRisk(method) ?: ToolRisk.DESTRUCTIVE
    }
}
