package app.knotwork.android.data.mcp

import java.io.IOException

/**
 * The MCP server answered a request with `404 Not Found` for the session the request was sent
 * in: the server has closed that session, and the request was refused without being run.
 *
 * The MCP specification (2025-11-25, *Session Management*) lets a Streamable-HTTP server close a
 * session at any time — MCP Kotlin SDK 0.16.0 servers do it after 30 minutes without requests —
 * and requires it to answer the closed session's id with 404 from then on. On that answer the
 * client must start a new session. Neither the SDK's client nor Koog does that, so this type is
 * how [KoogMcpClient] tells [McpConnectionPool] to: the pool replaces the connection once and
 * repeats the request on the new session. Repeating it is safe because the server never ran it.
 *
 * Its own type, not a message to match, so nothing else can be mistaken for it: a 404 from a
 * server that issued no session id means a wrong address, and stays an ordinary failure. An
 * [IOException], like the transport failure it replaces, so a caller that does not recover —
 * the second time in a row, say — reports it as one failed request.
 */
class McpSessionExpiredException :
    IOException(
        "The MCP server closed the session (HTTP 404 for its session id). The request was not run.",
    )
