package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.ToolApprovalPolicy
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.UpdateMcpServerResult
import kotlinx.coroutines.flow.Flow

/**
 * What the agent's tools may reach and how a call is gated: the MCP servers, per-tool enable /
 * disable and risk overrides, the approval policy and its window, the destructive-tool block, the
 * agent workspace's limits, and the `http_request` allowlist and response cap.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface ToolSettings {

    /**
     * Configured MCP servers, ordered by user insertion order.
     *
     * Each entry carries the URL plus optional display name, transport
     * choice, and arbitrary request headers (typically `Authorization`).
     * Implementations migrate legacy URL-only persistence one-shot to
     * default [McpServerConfig]s on first read.
     *
     * Emits again after every change made through [addMcpServer],
     * [updateMcpServer] or [removeMcpServer] — a change of a server's auth or
     * headers alone included — and the list a change produces already carries
     * the servers' new auth and headers. The Tools screen connects with what
     * it last received, so a list without them would stand until something
     * else made the list emit.
     */
    val mcpServers: Flow<List<McpServerConfig>>

    /**
     * Persists [config]. If a server with the same URL already exists,
     * it is replaced in place (preserving order).
     */
    suspend fun addMcpServer(config: McpServerConfig)

    /**
     * Replaces the server identified by [originalUrl] with [updated].
     * If [originalUrl] is not present, behaves the same as [addMcpServer].
     * The URL inside [updated] may differ from [originalUrl] (edit
     * scenario); persistence keeps the row at its old position.
     *
     * Refuses to persist with [UpdateMcpServerResult.UrlCollision] when
     * `updated.url` matches the URL of a different existing row. Without
     * this guard, replacing by index would silently produce a `[B, B]`
     * list and lose the original server's auth / headers / display name.
     */
    suspend fun updateMcpServer(originalUrl: String, updated: McpServerConfig): UpdateMcpServerResult

    /** Removes the server identified by [url]. No-op when missing. */
    suspend fun removeMcpServer(url: String)

    /**
     * A [Flow] representing the set of disabled local app function names.
     */
    val disabledAppFunctions: Flow<Set<String>>

    /**
     * Switches the local tools named in [functions] on or off, leaving every
     * other entry of [disabledAppFunctions] as stored.
     *
     * The change is applied to the stored set in one transaction, never to a
     * copy the caller read earlier: two switches flipped before the first write
     * came back would otherwise each write their own copy, and the second would
     * erase the first.
     *
     * @param functions Tool names to change; empty changes nothing.
     * @param enabled `true` removes them from the disabled set, `false` adds them.
     */
    suspend fun setAppFunctionsEnabled(functions: Set<String>, enabled: Boolean)

    /**
     * A [Flow] of disabled MCP tool ids. Entries match the
     * `mcp:<sha8(serverUrl)>:<toolName>` ids produced by
     * `McpServerRepositoryImpl.mcpToolId`. Kept separate from
     * [disabledAppFunctions] because the two namespaces share no collision
     * guarantees — disabling `search_tool` (local) and an MCP tool named
     * `search_tool` are independent decisions.
     */
    val disabledMcpTools: Flow<Set<String>>

    /**
     * Switches the MCP tools whose ids are in [toolIds] on or off, leaving
     * every other entry of [disabledMcpTools] as stored.
     *
     * One call is one write, however many ids it carries — switching all of a
     * server's tools at once is one call, not one per tool. The change is
     * applied to the stored set in one transaction, never to a copy the caller
     * read earlier, so a bulk change and a single switch flipped right after it
     * both survive.
     *
     * @param toolIds `mcp:<sha8(serverUrl)>:<toolName>` ids to change; empty
     * changes nothing.
     * @param enabled `true` removes them from the disabled set, `false` adds them.
     */
    suspend fun setMcpToolsEnabled(toolIds: Set<String>, enabled: Boolean)

    /**
     * A [Flow] of per-tool risk overrides. The map is the user's authoritative
     * voice on what risk class a discovered tool should be treated as; when an
     * entry is present it takes precedence over the conservative `SENSITIVE`
     * default that both discovery sources fall back to.
     *
     * Two key namespaces share this map, and they cannot collide because the
     * MCP form is prefixed:
     *  - **Discovered AppFunctions** — keyed by the AppFunction's tool name
     *    (the qualified `"${packageName}/${id}"` form).
     *  - **MCP tools** — keyed by the `mcp:<sha8(serverUrl)>:<toolName>` id
     *    produced by `McpServerRepositoryImpl.mcpToolId`, i.e. per server and
     *    not per bare name. Two servers advertising the same `create_issue`
     *    are independent decisions, exactly as they already are for
     *    [disabledMcpTools].
     *
     * Built-in tools carry hard-coded risk constants (see
     * `ToolRepositoryImpl.getBuiltinTools`) and are deliberately NOT affected
     * by entries in this map — their risk is part of the app's own contract.
     *
     * Missing entries (or an empty map) mean "no override, use the source
     * default" — the canonical resolution lives in `ToolRepository.getRisk`.
     */
    val toolRiskOverrides: Flow<Map<String, ToolRisk>>

    /**
     * Sets (or replaces) the risk override for a single discovered tool. To
     * remove an override, callers should pass the source-default value or wait
     * for the removal API to land in a follow-up task; this cut intentionally
     * keeps the surface minimal because there is no UI surface yet.
     *
     * @param toolKey Key of the tool to override — an AppFunction tool name or
     *   an `mcp:<sha8(serverUrl)>:<toolName>` id. See [toolRiskOverrides] for
     *   the namespace rules.
     * @param risk Effective risk class to associate with [toolKey].
     */
    suspend fun setToolRiskOverride(toolKey: String, risk: ToolRisk)

    /**
     * A [Flow] representing the timeout in milliseconds for tool approval requests.
     * After this duration without a user response, the approval is considered timed out.
     */
    val toolCallTimeoutMs: Flow<Long>

    /**
     * Updates the tool call approval timeout.
     *
     * @param timeoutMs The new timeout in milliseconds.
     */
    suspend fun setToolCallTimeoutMs(timeoutMs: Long)

    /**
     * A [Flow] of the maximum size, in bytes, of any single file the agent
     * workspace will accept. Writes whose content exceeds this are refused
     * ([app.knotwork.android.domain.models.WorkspaceError.TooLarge]); it also
     * caps how large a file the workspace will pull into memory for a text read.
     */
    val workspaceMaxFileSizeBytes: Flow<Long>

    /**
     * Updates the per-file size limit of the agent workspace.
     *
     * @param bytes The new per-file ceiling, in bytes.
     */
    suspend fun setWorkspaceMaxFileSizeBytes(bytes: Long)

    /**
     * A [Flow] of the maximum total size, in bytes, the agent workspace may
     * occupy across all files. A write that would push the workspace past this
     * is refused
     * ([app.knotwork.android.domain.models.WorkspaceError.QuotaExceeded]) so a
     * looping pipeline cannot exhaust device storage.
     */
    val workspaceMaxTotalBytes: Flow<Long>

    /**
     * Updates the workspace-wide total-size limit.
     *
     * @param bytes The new workspace-wide ceiling, in bytes.
     */
    suspend fun setWorkspaceMaxTotalBytes(bytes: Long)

    /**
     * A [Flow] of the budget, in **tokens**, of file content the `read_file`
     * tool returns in a single call. The tool converts this to an approximate
     * byte ceiling and truncates the served window to it (appending a
     * `[... truncated, N bytes remain — use offset to continue]` marker), so a
     * single read of a large file can never overflow the local model's context
     * window.
     */
    val workspaceReadTokenBudget: Flow<Int>

    /**
     * Updates the per-read token budget of the `read_file` tool.
     *
     * @param tokens The new budget, in tokens.
     */
    suspend fun setWorkspaceReadTokenBudget(tokens: Int)

    /**
     * A [Flow] of the domain allowlist for the `http_request` tool, in the order
     * the user added them. Each entry is a normalised host (e.g. `api.example.com`)
     * and is matched **exactly** — sub-domains are not implied, so the user must
     * add each host they intend to reach (see
     * [app.knotwork.android.domain.services.HttpRequestPolicy.isHostAllowed]).
     *
     * The allowlist is the tool's master switch: while it is **empty** the tool
     * is not published into [getAvailableTools] at all (the agent never sees it),
     * and any direct call is refused. A request whose target host matches no
     * entry is refused before it leaves the device — the conservative default
     * that keeps the read_file → http_request exfiltration channel closed until
     * the user explicitly opens a destination.
     */
    val allowedHttpDomains: Flow<List<String>>

    /**
     * Persists the full `http_request` domain allowlist, replacing any previous
     * value. Callers are expected to pass already-normalised, de-duplicated hosts
     * (see the Settings → Tools editor); persistence preserves their order.
     *
     * @param domains The new allowlist of normalised hosts.
     */
    suspend fun setAllowedHttpDomains(domains: List<String>)

    /**
     * A [Flow] of the maximum size, in bytes, of the response body the
     * `http_request` tool pulls into memory and returns to the agent. A larger
     * response is read up to this cap and a truncation marker is appended.
     * Bounds how much untrusted remote content one call can inject into context.
     */
    val httpToolMaxResponseBytes: Flow<Long>

    /**
     * Updates the `http_request` response-body byte ceiling.
     *
     * @param bytes The new ceiling, in bytes.
     */
    suspend fun setHttpToolMaxResponseBytes(bytes: Long)

    /**
     * Policy that drives the Human-in-the-Loop approval gate
     * (`ToolInvocationGate`); which risks it stops for is
     * [ToolApprovalPolicy.requiresApproval]. Supersedes the legacy boolean
     * `requires_user_confirmation` key, which nothing else reads.
     *
     * While the policy key is absent, every read maps that legacy key onto a
     * policy (nothing is written): `true` → [ToolApprovalPolicy.SensitiveOrDestructive],
     * `false` → [ToolApprovalPolicy.NeverPrompt], absent →
     * [ToolApprovalPolicy.DEFAULT].
     */
    val toolApprovalPolicy: Flow<ToolApprovalPolicy>

    /**
     * Persists the user's tool-approval policy choice.
     *
     * @param policy The new policy.
     */
    suspend fun setToolApprovalPolicy(policy: ToolApprovalPolicy)

    /**
     * `true` when the user has opted to hard-block every `DESTRUCTIVE`
     * tool — `ToolNodeExecutor` refuses to call the tool and returns a
     * structured "blocked by policy" observation. Defaults to `false`.
     */
    val blockDestructiveTools: Flow<Boolean>

    /**
     * Updates the hard-deny destructive-tool flag.
     *
     * @param blocked `true` to refuse destructive tools outright.
     */
    suspend fun setBlockDestructiveTools(blocked: Boolean)
}
