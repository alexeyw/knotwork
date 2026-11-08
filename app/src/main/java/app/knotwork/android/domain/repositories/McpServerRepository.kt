package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.McpConnectionStatus
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTool
import kotlinx.coroutines.flow.Flow

/**
 * Per-server gateway over the raw `McpClient` transport.
 *
 * Adds three concerns on top of the protocol-level client:
 *
 *  1. **Tool list caching.** `fetchToolList` returns cached `McpTool` entries
 *     for up to [TOOL_LIST_TTL_MS] (5 minutes), and only to a fetch with the
 *     config they were fetched with, while the connection they came from is
 *     still the live one — an edited server, or one whose connection was
 *     replaced or dropped, is asked again; `forceRefresh = true` bypasses the
 *     cache (wired to the trailing refresh icon in `McpServerRow`). Asking
 *     again is a real `tools/list` request over the live connection; a session
 *     the server has closed is replaced by a new one on the way.
 *  2. **Per-server connection status.** Each URL has a hot `Flow<McpConnectionStatus>`
 *     that drives the status pill in the Tools screen. Transitions are emitted
 *     from inside `fetchToolList`: `Connecting → Connected` on success,
 *     `Connecting → Error` on handshake or JSON-RPC failure.
 *  3. **Lifecycle.** Connections are opened lazily and kept alive across
 *     `fetchToolList` calls; callers drop one through [disconnect] when a URL
 *     is removed from Settings. The implementation does not own them: they
 *     live in the pool the agent's tool calls use too, so the status shown
 *     here describes the connection the agent's next call would use.
 *
 * Implementations live in `data/repositories/McpServerRepositoryImpl`.
 */
interface McpServerRepository {

    /**
     * Connects (lazily) to the server described by [config], performs an MCP
     * `tools/list` round-trip, and returns the resulting [McpTool] entries.
     *
     * The status flow returned by [observeConnectionStatus] is updated as a
     * side-effect of this call (`Connecting → Connected` / `Connecting → Error`),
     * so callers do not need to wrap the result in their own error UI — they
     * can simply observe the flow and surface its terminal state.
     *
     * @param config full server configuration (URL + optional headers, name,
     * transport). Headers reach the underlying `McpClient.connect`.
     * @param forceRefresh when `true`, ignores the cached tool list (if any)
     * and re-issues the round-trip even if the previous response is within
     * the 5-minute TTL.
     * @return [Result.success] with the tool list on a clean fetch; [Result.failure]
     * wrapping the underlying exception when the handshake or the `tools/list`
     * call throws. The status flow carries the same outcome.
     */
    suspend fun fetchToolList(config: McpServerConfig, forceRefresh: Boolean = false): Result<List<McpTool>>

    /**
     * Hot stream of the current connection lifecycle for [serverUrl]. The first
     * collector receives the latest cached state (or `Connecting` if no fetch
     * has been attempted yet).
     */
    fun observeConnectionStatus(serverUrl: String): Flow<McpConnectionStatus>

    /**
     * Closes the underlying [app.knotwork.android.data.mcp.McpClient] for [serverUrl]
     * (if any) and drops the cached tool list. No-op when the server was never
     * fetched. Called from `ToolsViewModel` when the user removes a server URL.
     */
    suspend fun disconnect(serverUrl: String)

    /** Tunables shared across every implementation of this interface. */
    companion object {
        /** Cache TTL for [fetchToolList] in milliseconds (5 minutes). */
        const val TOOL_LIST_TTL_MS: Long = 5L * 60L * 1000L
    }
}
