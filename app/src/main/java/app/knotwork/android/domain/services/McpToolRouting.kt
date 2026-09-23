package app.knotwork.android.domain.services

/**
 * The one rule for which MCP server answers a tool name. The agent's tool
 * catalogue, the HITL risk lookup, the dispatch of the call and the Tools
 * screen all read it, so the entry the model chose from, the decision that
 * gated the call and the server that ran it are always the same server.
 *
 * ### The rule
 *
 * 1. **A tool on the device wins.** A built-in tool or a discovered AppFunction
 *    with the same name is always the one that runs: the repository dispatches
 *    local names before it looks at MCP at all. An MCP tool of that name is
 *    therefore never offered — listing it would put a server's description in
 *    the prompt for a call that runs something else.
 * 2. **Otherwise the first server in the user's order serves the name** — the
 *    first one that publishes it and does not have it switched off. A later
 *    server publishing the same name is not offered either, for the same reason.
 *
 * ### Why there is no failover to the next server
 *
 * The risk of a call is decided before it runs, from the user's per-server
 * decision for the serving server. Retrying a failed call on another server
 * would run it under a decision made for a different tool. It would also repeat
 * a side effect: a call that timed out may still be running on the first server.
 */
object McpToolRouting {

    /**
     * One MCP server's catalogue as the rule sees it.
     *
     * @property serverUrl the server's configured URL — its identity in settings.
     * @property toolNames names the server publishes to this app.
     * @property disabledToolNames names the user switched off on this server; a
     *   switched-off name is not served from here, but a later server may serve it.
     */
    data class ServerCatalogue(val serverUrl: String, val toolNames: Set<String>, val disabledToolNames: Set<String>)

    /** Why a tool a server publishes is not offered to the agent from that server. */
    sealed interface Shadow {
        /** A built-in tool or a discovered AppFunction has the same name, and it always wins. */
        data object LocalTool : Shadow

        /**
         * An earlier server in the user's order serves the same name.
         *
         * @property serverUrl URL of the server that serves it.
         */
        data class EarlierServer(val serverUrl: String) : Shadow
    }

    /**
     * The server that serves [toolName] among [servers].
     *
     * Local tools are not considered here: every caller has already routed local
     * names before it asks (see [shadowOf] for the full rule).
     *
     * @param toolName the name the model called, or a catalogue entry's name.
     * @param servers MCP servers in the user's order.
     * @return the URL of the first server that publishes [toolName] and has not
     *   switched it off, or `null` when no server can serve it.
     */
    fun servingServer(toolName: String, servers: List<ServerCatalogue>): String? = servers.firstOrNull { server ->
        toolName in server.toolNames && toolName !in server.disabledToolNames
    }?.serverUrl

    /**
     * Why [toolName], as published by [serverUrl], is not offered to the agent —
     * independently of whether the user switched it off on that server, so the
     * Tools screen can tell the user that switching it on would not help.
     *
     * @param toolName the published name.
     * @param serverUrl the server publishing it.
     * @param localToolNames names of every tool on the device, disabled ones included:
     *   a disabled local tool still takes its name, because the call would be
     *   refused as disabled rather than routed to a server.
     * @param servers MCP servers in the user's order.
     * @return [Shadow.LocalTool] or [Shadow.EarlierServer] when something else takes
     *   the name, `null` when nothing does.
     */
    fun shadowOf(
        toolName: String,
        serverUrl: String,
        localToolNames: Set<String>,
        servers: List<ServerCatalogue>,
    ): Shadow? {
        if (toolName in localToolNames) return Shadow.LocalTool
        val earlier = servers.takeWhile { it.serverUrl != serverUrl }
        return servingServer(toolName, earlier)?.let { Shadow.EarlierServer(serverUrl = it) }
    }

    /**
     * Whether the agent is offered [toolName] from [serverUrl]: nothing on the
     * device takes the name, and [serverUrl] is the server that serves it — which
     * also means the user has not switched it off there.
     *
     * @param toolName the published name.
     * @param serverUrl the server publishing it.
     * @param localToolNames names of every tool on the device, disabled ones included.
     * @param servers MCP servers in the user's order.
     * @return `true` when this catalogue entry is the one a call by that name reaches.
     */
    fun isOffered(
        toolName: String,
        serverUrl: String,
        localToolNames: Set<String>,
        servers: List<ServerCatalogue>,
    ): Boolean = toolName !in localToolNames && servingServer(toolName, servers) == serverUrl
}
