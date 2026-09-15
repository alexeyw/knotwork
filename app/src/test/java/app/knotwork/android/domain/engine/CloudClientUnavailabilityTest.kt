package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.CloudProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CloudClientUnavailability.message] — each cause must name the setting that
 * resolves it and must not borrow another cause's remedy.
 */
class CloudClientUnavailabilityTest {

    @Test
    fun `given missing credentials for a hosted provider when worded then it asks for an API key`() {
        val message = CloudClientUnavailability.MissingCredentials.message(CloudProvider.ANTHROPIC)

        assertTrue(message.contains("'anthropic'"))
        assertTrue(message.contains("no API key"))
    }

    @Test
    fun `given missing credentials for Ollama when worded then it asks for a server address not a key`() {
        val message = CloudClientUnavailability.MissingCredentials.message(CloudProvider.OLLAMA)

        assertTrue(message.contains("server address"))
        assertFalse(message.contains("API key"))
    }

    @Test
    fun `given the restriction when worded then it names the setting to turn off`() {
        val message = CloudClientUnavailability.BlockedByLocalOnlyMode.message(CloudProvider.OPENAI)

        assertTrue(message.contains("'openai'"))
        assertTrue(message.contains("\"Block network from local model\""))
        assertFalse(message.contains("API key"))
    }

    @Test
    fun `given a non-local endpoint when worded then it names the host, the restriction and what counts as local`() {
        val message = CloudClientUnavailability.EndpointNotLocal("ollama.example.com").message(CloudProvider.OLLAMA)

        assertTrue(message.contains("'ollama.example.com'"))
        assertTrue(message.contains("\"Block network from local model\""))
        assertTrue(message.contains("192.168.x.x"))
    }

    @Test
    fun `given a non-local endpoint without a readable host when worded then it still reads as a sentence`() {
        val message = CloudClientUnavailability.EndpointNotLocal(host = null).message(CloudProvider.OLLAMA)

        assertTrue(message.contains("at the configured address"))
        assertFalse(message.contains("null"))
    }

    @Test
    fun `given a cleartext refusal when worded then the rule's own remedy is kept`() {
        val reason = "Refusing an unencrypted connection to a public address. Use https:// instead."

        val message = CloudClientUnavailability.CleartextRefused(reason).message(CloudProvider.OLLAMA)

        assertTrue(message.contains("'ollama'"))
        assertTrue(message.endsWith(reason))
        assertFalse(message.contains("Block network"))
    }
}
