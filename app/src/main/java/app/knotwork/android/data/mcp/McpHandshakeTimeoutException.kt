package app.knotwork.android.data.mcp

import java.io.IOException
import kotlin.time.Duration

/**
 * An MCP server accepted the connection and did not complete the handshake within [after].
 *
 * Its own type so a connection check can tell this case — a URL that answers and is not the MCP
 * endpoint, or a server that hangs — from a socket that never opened. An [IOException], as the
 * failure it replaces was: every caller that handled that one still handles this.
 *
 * @param url The server URL the handshake was attempted with.
 * @property after The handshake deadline that ran out.
 */
class McpHandshakeTimeoutException(url: String, val after: Duration) :
    IOException("MCP server $url did not complete the handshake within ${after.inWholeSeconds}s")
