package app.knotwork.android.domain.services

import app.knotwork.android.domain.services.McpToolRouting.ServerCatalogue
import app.knotwork.android.domain.services.McpToolRouting.Shadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one rule for which MCP server answers a tool name: a device tool wins,
 * then the first server in the user's order with the name switched on.
 */
class McpToolRoutingTest {

    private fun server(url: String, vararg names: String, disabled: Set<String> = emptySet()) =
        ServerCatalogue(serverUrl = url, toolNames = names.toSet(), disabledToolNames = disabled)

    @Test
    fun `given two servers publishing a name when servingServer then the first in the user's order serves it`() {
        val servers = listOf(server("a", "create_issue"), server("b", "create_issue"))

        assertEquals("a", McpToolRouting.servingServer("create_issue", servers))
    }

    @Test
    fun `given the first server has the name switched off when servingServer then the next one serves it`() {
        val servers = listOf(server("a", "create_issue", disabled = setOf("create_issue")), server("b", "create_issue"))

        assertEquals("b", McpToolRouting.servingServer("create_issue", servers))
    }

    @Test
    fun `given every server has the name switched off when servingServer then nobody serves it`() {
        val servers = listOf(
            server("a", "create_issue", disabled = setOf("create_issue")),
            server("b", "create_issue", disabled = setOf("create_issue")),
        )

        assertNull(McpToolRouting.servingServer("create_issue", servers))
    }

    @Test
    fun `given no server publishes the name when servingServer then nobody serves it`() {
        assertNull(McpToolRouting.servingServer("phantom", listOf(server("a", "create_issue"))))
    }

    @Test
    fun `given a local tool has the name when shadowOf then the local tool shadows every server`() {
        val servers = listOf(server("a", "read_file"), server("b", "read_file"))

        assertEquals(Shadow.LocalTool, McpToolRouting.shadowOf("read_file", "a", setOf("read_file"), servers))
        assertEquals(Shadow.LocalTool, McpToolRouting.shadowOf("read_file", "b", setOf("read_file"), servers))
    }

    @Test
    fun `given an earlier server serves the name when shadowOf then the later one is shadowed by it`() {
        val servers = listOf(server("a", "create_issue"), server("b", "create_issue"))

        assertNull(McpToolRouting.shadowOf("create_issue", "a", emptySet(), servers))
        assertEquals(Shadow.EarlierServer("a"), McpToolRouting.shadowOf("create_issue", "b", emptySet(), servers))
    }

    @Test
    fun `given the earlier server has the name switched off when shadowOf then the later one is not shadowed`() {
        val servers = listOf(server("a", "create_issue", disabled = setOf("create_issue")), server("b", "create_issue"))

        assertNull(McpToolRouting.shadowOf("create_issue", "b", emptySet(), servers))
    }

    @Test
    fun `given a switched-off tool behind a serving server when shadowOf then it still reports the shadow`() {
        // The Tools screen shows the line under a switched-off row too: switching it
        // on would not make it reachable.
        val servers = listOf(server("a", "create_issue"), server("b", "create_issue", disabled = setOf("create_issue")))

        assertEquals(Shadow.EarlierServer("a"), McpToolRouting.shadowOf("create_issue", "b", emptySet(), servers))
    }

    @Test
    fun `isOffered holds for exactly the entry a call by that name reaches`() {
        val servers = listOf(
            server("a", "create_issue", "read_file", disabled = setOf("create_issue")),
            server("b", "create_issue"),
        )
        val local = setOf("read_file")

        assertFalse(McpToolRouting.isOffered("create_issue", "a", local, servers))
        assertTrue(McpToolRouting.isOffered("create_issue", "b", local, servers))
        assertFalse(McpToolRouting.isOffered("read_file", "a", local, servers))
    }
}
