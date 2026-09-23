package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk

/**
 * Repository interface for managing and accessing tools available to the AI agent.
 */
interface ToolRepository {
    /**
     * Retrieves a list of all currently available tools.
     *
     * @return A list of [AgentTool] instances.
     */
    suspend fun getAvailableTools(): List<AgentTool>

    /**
     * Retrieves a list of all available local tools (AppFunctions), ignoring their disabled state.
     *
     * @return A list of [AgentTool] instances.
     */
    suspend fun getAllLocalTools(): List<AgentTool>

    /**
     * Executes a specific tool by its name with the given arguments.
     *
     * @param name The name of the tool to execute.
     * @param arguments The arguments to pass to the tool, formatted as a JSON string.
     * An MCP call goes to exactly one server — the one [getRisk] resolved for the
     * same name — with no failover to another server when it fails.
     *
     * @param context Engine-supplied [ToolExecutionContext] with trusted environment
     *   values. The session id is forwarded to built-in [LocalToolExecutor]
     *   strategies; the gated risk is re-checked against the MCP server about to run
     *   the call, which is refused when they differ.
     * @return The result of the tool execution as a string.
     */
    suspend fun executeTool(
        name: String,
        arguments: String,
        context: ToolExecutionContext = ToolExecutionContext.EMPTY,
    ): String

    /**
     * Resolves the effective [ToolRisk] for a tool by its name. This is the single
     * seam consumed by the Human-in-the-Loop gate: the gate must never read
     * `AgentTool.risk` directly so that user-supplied overrides take precedence
     * over whatever the discovery source declared.
     *
     * Resolution order:
     * 1. Built-in tools (`schedule_task`, `search_tool`, `delegate_task`) return
     *    their hard-coded risk constants. They are part of the app's own
     *    contract and are deliberately not overridable.
     * 2. Discovered AppFunctions return the user override from
     *    `SettingsRepository.toolRiskOverrides` (keyed by the AppFunction's tool
     *    name) if set, otherwise [ToolRisk.SENSITIVE] (we cannot trust the
     *    AppFunctionManager metadata for side-effect signal).
     * 3. MCP tools return the user override from the same map, keyed by the
     *    `mcp:<sha8(serverUrl)>:<toolName>` id of the server that **serves** the
     *    name — the first in the user's order that publishes it and has not
     *    switched it off, the same server [executeTool] sends the call to —
     *    otherwise [ToolRisk.SENSITIVE]. An MCP tool never takes the name of a
     *    built-in or an AppFunction: those resolve in steps 1–2, and the MCP
     *    namesake is not offered to the agent at all.
     *
     * The override is the **user's** voice, never the server's. MCP's
     * `readOnlyHint` / `destructiveHint` tool annotations are deliberately not
     * consulted: they are self-declared by the remote server, and letting a
     * server lower its own tools to `READ_ONLY` would let it walk straight past
     * the HITL gate. A hint may be surfaced as advice one day; it must never
     * be an input to this function.
     *
     * Most tools have a single static risk and ignore [arguments]. The exception
     * is `http_request`, whose risk depends on the HTTP method carried in the
     * arguments (a read `GET` is [ToolRisk.SENSITIVE]; a state-changing
     * `POST` / `PUT` / `DELETE` is [ToolRisk.DESTRUCTIVE]). The HITL gate passes
     * the resolved argument string so the confirmation strength matches the
     * concrete call; callers that do not have the arguments (or for whom risk is
     * argument-independent) may rely on the empty default.
     *
     * @param toolName The name of the tool to look up.
     * @param arguments The resolved JSON argument string of the pending call,
     *   used only by tools whose risk is argument-dependent. Defaults to empty.
     * @return The effective [ToolRisk].
     * @throws IllegalArgumentException if no tool with the given name is known to
     * any of the active sources.
     */
    suspend fun getRisk(toolName: String, arguments: String = ""): ToolRisk
}
