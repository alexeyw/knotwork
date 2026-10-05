package app.knotwork.android.domain.connection

import app.knotwork.android.domain.models.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ConnectionPreconditions] — why a Test button is disabled, and the first thing a
 * check asks.
 */
class ConnectionPreconditionsTest {

    private val lan = "http://192.168.1.20:8000"

    private fun provider(
        provider: CloudProvider,
        key: String? = null,
        address: String? = null,
        approved: Set<String> = emptySet(),
        localOnly: Boolean = false,
    ) = ConnectionPreconditions.provider(provider, ProviderConnectionDraft(key, address), approved, localOnly)

    @Test
    fun `given a server the user runs when its address is missing or unparsable then that is the reason`() {
        val own = CloudProvider.OPENAI_COMPATIBLE

        assertEquals(ConnectionRefusal.MissingAddress, provider(own, key = "sk", address = null))
        assertEquals(ConnectionRefusal.MissingAddress, provider(own, address = "  "))
        assertEquals(ConnectionRefusal.NotAnAddress, provider(own, address = "192.168.1.20:8000/v1"))
    }

    @Test
    fun `given a server the user runs when its address is refused then the address rule says why`() {
        val own = CloudProvider.OPENAI_COMPATIBLE

        assertEquals(AddressRefusal.PublicCleartext("203.0.113.7"), provider(own, address = "http://203.0.113.7/v1"))
        assertEquals(AddressRefusal.CleartextNeedsApproval(lan), provider(own, address = "$lan/v1"))
        assertEquals(
            AddressRefusal.HostNotLocal("llm.example.com"),
            provider(own, address = "https://llm.example.com/v1", localOnly = true),
        )
    }

    @Test
    fun `given a server the user runs at an allowed address when judged then it may run without a key`() {
        assertNull(provider(CloudProvider.OPENAI_COMPATIBLE, address = " $lan/v1 ", approved = setOf(lan)))
        assertNull(
            provider(
                CloudProvider.OLLAMA,
                address = "http://127.0.0.1:11434",
                approved = setOf("http://127.0.0.1:11434"),
            ),
        )
    }

    @Test
    fun `given a hosted provider when no key is entered then the key is the reason`() {
        assertEquals(ConnectionRefusal.MissingKey, provider(CloudProvider.GROQ, key = null))
        assertEquals(ConnectionRefusal.MissingKey, provider(CloudProvider.OPENROUTER, key = " "))
        assertNull(provider(CloudProvider.OPENAI, key = "sk"))
    }

    @Test
    fun `given a hosted provider while the restriction is on when judged then the form does not refuse it`() {
        // A refusal that depends on a setting alone is said by the check, not by a disabled button.
        assertNull(provider(CloudProvider.GOOGLE, key = "AIza", localOnly = true))
    }

    @Test
    fun `given an MCP server when its URL is missing, unparsable or refused then that is the reason`() {
        assertEquals(ConnectionRefusal.MissingAddress, ConnectionPreconditions.mcp("", emptySet()))
        assertEquals(ConnectionRefusal.NotAnAddress, ConnectionPreconditions.mcp("example.com/mcp", emptySet()))
        assertEquals(
            AddressRefusal.PublicCleartext("mcp.example.com"),
            ConnectionPreconditions.mcp("http://mcp.example.com/mcp", emptySet()),
        )
        assertEquals(AddressRefusal.CleartextNeedsApproval(lan), ConnectionPreconditions.mcp("$lan/mcp", emptySet()))
    }

    @Test
    fun `given an MCP server at an allowed address when judged then it may run`() {
        // "Block network from local model" does not cover MCP servers; nothing here reads it.
        assertNull(ConnectionPreconditions.mcp("https://mcp.example.com/mcp", emptySet()))
        assertNull(ConnectionPreconditions.mcp("$lan/mcp", setOf(lan)))
    }
}
