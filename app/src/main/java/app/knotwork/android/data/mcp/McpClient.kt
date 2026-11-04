package app.knotwork.android.data.mcp

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.McpServerConfig

/**
 * Interface representing a client for the Model Context Protocol (MCP).
 * This client is responsible for establishing a connection with an MCP server,
 * discovering available tools, and executing them.
 */
interface McpClient {
    /**
     * Connects to the MCP server described by [config]. Implementations honour
     * [McpServerConfig.headers] (typically `Authorization: Bearer …`) and the
     * declared transport.
     *
     * Implementations may downgrade an unsupported transport to a viable default,
     * but must never silently drop auth headers. The one implementation connects
     * with the transport the config names, both SSE and Streamable HTTP.
     */
    suspend fun connect(config: McpServerConfig)

    /**
     * Disconnects from the currently connected MCP server and releases any held resources.
     */
    suspend fun disconnect()

    /**
     * Retrieves the list of available tools from the connected MCP server — the list the
     * server gave when it was last asked: at [connect], or at the latest [refreshTools].
     * Sends nothing over the network.
     *
     * @return A list of [AgentTool] objects representing the tools exposed by the server.
     */
    suspend fun getTools(): List<AgentTool>

    /**
     * Asks the connected server for its tool list again, over the live session, and makes
     * that list the one [getTools] and [executeTool] use from then on.
     *
     * The one request that tells a live session from one the server has closed without
     * opening a new one, and the way a tool the server added or removed since [connect]
     * reaches the app.
     *
     * @return The tools the server publishes now.
     * @throws McpSessionExpiredException when the server answers that the session is closed.
     * @throws IllegalStateException when the client is not connected.
     */
    suspend fun refreshTools(): List<AgentTool>

    /**
     * Executes a specific tool on the MCP server with the provided arguments.
     *
     * @param name The name of the tool to execute.
     * @param arguments A JSON string representing the arguments required by the tool.
     * @return A string containing the result of the tool's execution.
     * @throws McpSessionExpiredException when the server answers that the session is closed;
     *   the call was refused and did not run.
     */
    suspend fun executeTool(name: String, arguments: String): String
}

/**
 * Factory interface for creating instances of [McpClient].
 * Useful for dependency injection where multiple clients might need to be created.
 */
interface McpClientFactory {
    /**
     * Creates and returns a new instance of [McpClient].
     *
     * @return A new [McpClient] instance.
     */
    fun create(): McpClient
}
