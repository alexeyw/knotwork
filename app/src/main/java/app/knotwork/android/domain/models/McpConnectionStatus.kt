package app.knotwork.android.domain.models

import app.knotwork.android.domain.connection.ConnectionFailure

/**
 * Connection lifecycle of an MCP server as observed by `McpServerRepository`.
 *
 * The status flow is hot: it transitions `Connecting → Connected` on a successful
 * `tools/list` fetch and `Connecting → Error` when the handshake or the JSON-RPC
 * call throws. UI surfaces (`ToolsScreen`) render the current value as a status
 * pill in the trailing slot of the server row, including the transition animation.
 */
sealed interface McpConnectionStatus {
    /** Initial state — emitted before the first fetch attempt and on force-refresh. */
    data object Connecting : McpConnectionStatus

    /** Steady state after a successful `tools/list` response. */
    data object Connected : McpConnectionStatus

    /**
     * Terminal state for the current attempt: the connection or the `tools/list` call failed.
     * The failure is typed — the same classification the form's connection check uses — so the
     * screen words it in the interface language.
     *
     * @property host The server's host, named in the sentence.
     * @property failure What went wrong.
     */
    data class Error(val host: String, val failure: ConnectionFailure) : McpConnectionStatus

    /**
     * The attempt was abandoned before it resolved, and no earlier answer stands: the server is
     * not connected, and trying again is the way on.
     */
    data object Interrupted : McpConnectionStatus
}
