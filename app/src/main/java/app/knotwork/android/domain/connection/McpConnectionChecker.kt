package app.knotwork.android.domain.connection

import app.knotwork.android.domain.models.McpServerConfig

/**
 * Checks that an MCP server can be reached with the values in its form, by connecting and listing
 * its tools — one attempt, nothing saved.
 *
 * The check uses a connection of its own, opened and closed for it: the shared connections the
 * agent and the Tools screen use are keyed by saved servers, and a form being edited is not one.
 */
interface McpConnectionChecker {

    /**
     * Connects to the server [config] describes, lists its tools and disconnects.
     *
     * Total: every outcome, a failed connection included, comes back as a result.
     *
     * @param config The server as entered in the form, credentials included.
     * @return What the check found.
     */
    suspend fun check(config: McpServerConfig): ConnectionCheckResult
}
