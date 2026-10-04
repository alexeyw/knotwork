package app.knotwork.android.data.engine

import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelNetworkGate] — the single decision every model client asks before
 * it is built.
 */
class ModelNetworkGateTest {

    private val localOnlyMode = MutableStateFlow(false)
    private val approvedOrigins = MutableStateFlow(setOf(APPROVED_LAN_ORIGIN))
    private val settingsRepository = mockk<SettingsRepository> {
        every { blockNetworkFromLocalModel } returns localOnlyMode
        every { approvedCleartextOrigins } returns approvedOrigins
    }
    private val gate = ModelNetworkGate(settingsRepository)

    @Test
    fun `given local-only mode off when cloudRefusal then cloud providers are admitted`() = runTest {
        assertNull(gate.cloudRefusal())
    }

    @Test
    fun `given local-only mode on when cloudRefusal then cloud providers are blocked`() = runTest {
        localOnlyMode.value = true

        assertEquals(CloudClientUnavailability.BlockedByLocalOnlyMode, gate.cloudRefusal())
    }

    @Test
    fun `given the settings now when a hop rule is taken then it applies them`() = runTest {
        // The rule the transport applies to every redirect is the same pair of checks, with
        // the restriction and the approved origins as they stand when the client is built.
        val open = gate.hopRule()
        localOnlyMode.value = true
        val restricted = gate.hopRule()

        assertNull(open.refusal("$APPROVED_LAN_ORIGIN/v1/chat/completions"))
        assertNull(open.refusal("https://api.groq.com/openai/v1/chat/completions"))
        assertNotNull(open.refusal("http://10.0.0.9:11434/api/chat"))
        assertNotNull(restricted.refusal("https://api.groq.com/openai/v1/chat/completions"))
        assertNull(restricted.refusal("$APPROVED_LAN_ORIGIN/v1/chat/completions"))
    }

    @Test
    fun `given local-only mode off when networkToolRefusal then the tool may run`() = runTest {
        assertNull(gate.networkToolRefusal("search_tool"))
    }

    @Test
    fun `given local-only mode on when networkToolRefusal then the tool is refused`() = runTest {
        localOnlyMode.value = true

        val refusal = gate.networkToolRefusal("search_tool")

        assertNotNull(refusal)
        // The model reads this text, so it has to name the tool and the setting that
        // withheld it — a bare "disabled" sends the model looking at the Tools screen,
        // where the tool's own switch is still on.
        assertTrue(refusal!!.contains("search_tool"))
        assertTrue(refusal.contains("Block network from local model"))
    }

    @Test
    fun `given the restriction flag has not emitted when networkToolRefusal then the tool may run`() = runTest {
        every { settingsRepository.blockNetworkFromLocalModel } returns emptyFlow()

        assertNull(ModelNetworkGate(settingsRepository).networkToolRefusal("search_tool"))
    }

    @Test
    fun `given the restriction flag has not emitted when cloudRefusal then cloud providers are admitted`() = runTest {
        // Mirrors the stored default (`false`); an empty flow must not read as "on" or throw.
        every { settingsRepository.blockNetworkFromLocalModel } returns emptyFlow()

        assertNull(gate.cloudRefusal())
    }

    @Test
    fun `given local-only mode on and a public https Ollama when endpointRefusal then the host is named`() = runTest {
        localOnlyMode.value = true

        assertEquals(
            CloudClientUnavailability.EndpointNotLocal("ollama.example.com"),
            gate.endpointRefusal("https://ollama.example.com/api"),
        )
    }

    @Test
    fun `given local-only mode on and public cleartext when endpointRefusal then the restriction is the reason`() =
        runTest {
            // Both rules refuse; the restriction is the one the user switched on deliberately,
            // so it is reported first.
            localOnlyMode.value = true

            assertEquals(
                CloudClientUnavailability.EndpointNotLocal("203.0.113.7"),
                gate.endpointRefusal("http://203.0.113.7:11434"),
            )
        }

    @Test
    fun `given local-only mode on and an unapproved LAN cleartext address when checked then cleartext refuses`() =
        runTest {
            localOnlyMode.value = true

            val refusal = gate.endpointRefusal("http://10.0.0.9:11434")

            assertTrue(
                "expected a cleartext refusal, got $refusal",
                refusal is CloudClientUnavailability.CleartextRefused,
            )
        }

    @Test
    fun `given local-only mode on and admissible local addresses when endpointRefusal then they are admitted`() =
        runTest {
            localOnlyMode.value = true

            assertNull(gate.endpointRefusal("$APPROVED_LAN_ORIGIN/api"))
            assertNull(gate.endpointRefusal("https://192.168.1.42:11434"))
        }

    @Test
    fun `given local-only mode off when endpointRefusal then only the cleartext rule applies`() = runTest {
        assertNull(gate.endpointRefusal("https://ollama.example.com"))
        assertTrue(gate.endpointRefusal("http://203.0.113.7:11434") is CloudClientUnavailability.CleartextRefused)
    }

    @Test
    fun `given the restriction switched on between calls when asked again then the new value applies`() = runTest {
        assertNull(gate.endpointRefusal("https://ollama.example.com"))

        localOnlyMode.value = true

        assertTrue(gate.endpointRefusal("https://ollama.example.com") is CloudClientUnavailability.EndpointNotLocal)
    }

    private companion object {
        const val APPROVED_LAN_ORIGIN = "http://192.168.1.42:11434"
    }
}
