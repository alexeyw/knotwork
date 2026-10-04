package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.data.local.crypto.FakeAeadCipher
import app.knotwork.android.data.local.crypto.InMemorySharedPreferences
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.CloudProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies [ApiKeyManager]'s storage round-trip and its re-enterable-secret
 * recovery policy: values that can no longer be decrypted are reported as
 * absent and dropped, never propagated as errors.
 */
class ApiKeyManagerTest {

    private companion object {
        const val PREFS_NAME = "secure_api_keys_v2"
        const val KEY_ALIAS = "knotwork.api_keys"
        const val OPENAI_KEY = "openai_api_key"
        const val OLLAMA_CONTEXT = "ollama_context"

        /**
         * Every entry name an installed release may already hold, with the write that must
         * land on it. The names are user data: a rename reads every saved key as "not
         * configured", silently. Written out as literals on purpose — deriving them here
         * the way production does would let both drift together.
         */
        val SHIPPED_ENTRIES: Map<String, suspend (ApiKeyManager) -> Unit> = mapOf(
            "openai_api_key" to { m -> m.setApiKey(CloudProvider.OPENAI, "v") },
            "openai_model" to { m -> m.setModel(CloudProvider.OPENAI, "v") },
            "anthropic_api_key" to { m -> m.setApiKey(CloudProvider.ANTHROPIC, "v") },
            "anthropic_model" to { m -> m.setModel(CloudProvider.ANTHROPIC, "v") },
            "google_api_key" to { m -> m.setApiKey(CloudProvider.GOOGLE, "v") },
            "google_model" to { m -> m.setModel(CloudProvider.GOOGLE, "v") },
            "deepseek_api_key" to { m -> m.setApiKey(CloudProvider.DEEPSEEK, "v") },
            "deepseek_model" to { m -> m.setModel(CloudProvider.DEEPSEEK, "v") },
            "ollama_base_url" to { m -> m.setBaseUrl(CloudProvider.OLLAMA, "v") },
            "ollama_model" to { m -> m.setModel(CloudProvider.OLLAMA, "v") },
            "ollama_context" to { m -> m.setOllamaContextWindowSize(1) },
        )
    }

    private lateinit var context: Context
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var cipher: FakeAeadCipher

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = InMemorySharedPreferences()
        cipher = FakeAeadCipher()
        every { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
    }

    private fun manager() = ApiKeyManager(context, cipher)

    @Test
    fun `given key saved when read through a fresh instance then round-trips`() = runTest {
        manager().setApiKey(CloudProvider.OPENAI, "sk-test-123")

        assertEquals("sk-test-123", manager().getApiKey(CloudProvider.OPENAI).first())
    }

    @Test
    fun `given key saved when raw prefs inspected then value is not plaintext`() = runTest {
        manager().setApiKey(CloudProvider.OPENAI, "sk-test-123")

        val raw = prefs.values[OPENAI_KEY] as String
        assertFalse(raw.contains("sk-test-123"))
    }

    @Test
    fun `given key set to null when read then returns null and entry is removed`() = runTest {
        val subject = manager()
        subject.setApiKey(CloudProvider.OPENAI, "sk-test-123")

        subject.setApiKey(CloudProvider.OPENAI, null)

        assertNull(subject.getApiKey(CloudProvider.OPENAI).first())
        assertFalse(prefs.values.containsKey(OPENAI_KEY))
    }

    @Test
    fun `given undecryptable stored key when read then treated as unset and dropped`() = runTest {
        manager().setApiKey(CloudProvider.OPENAI, "sk-test-123")
        cipher.failDecrypt = true

        // A lost Keystore key must surface as "no key configured", not crash:
        // API keys are user re-enterable, unlike the database passphrase.
        assertNull(manager().getApiKey(CloudProvider.OPENAI).first())
        assertFalse(prefs.values.containsKey(OPENAI_KEY))
    }

    @Test
    fun `given no stored ollama context when read then returns default`() = runTest {
        assertEquals(
            SettingsDefaults.OLLAMA_CONTEXT_WINDOW_DEFAULT,
            manager().getOllamaContextWindowSize().first(),
        )
    }

    @Test
    fun `given ollama context saved when read through a fresh instance then round-trips`() = runTest {
        manager().setOllamaContextWindowSize(8192)

        assertEquals(8192, manager().getOllamaContextWindowSize().first())
        assertTrue(prefs.values.containsKey(OLLAMA_CONTEXT))
    }

    @Test
    fun `given updated key when flow observed then emits the new value`() = runTest {
        val subject = manager()
        subject.setApiKey(CloudProvider.ANTHROPIC, "first")

        subject.setApiKey(CloudProvider.ANTHROPIC, "second")

        assertEquals("second", subject.getApiKey(CloudProvider.ANTHROPIC).first())
    }

    @Test
    fun `given each slot an installed release may hold when written then it lands under its shipped name`() = runTest {
        SHIPPED_ENTRIES.forEach { (name, write) ->
            prefs.values.clear()

            write(manager())

            assertEquals("write for '$name' landed elsewhere", setOf(name), prefs.values.keys)
        }
    }

    @Test
    fun `given values an earlier release wrote under the shipped names when read then each is returned`() = runTest {
        // Written the way the per-provider store did: straight through the encrypted
        // store, under the literal name, the file and the key alias.
        val earlier = KeystoreBackedPrefsStore(context, PREFS_NAME, KEY_ALIAS, cipher)
        earlier.putString("deepseek_api_key", "sk-ds", synchronous = true)
        earlier.putString("google_model", "gemini-x", synchronous = true)
        earlier.putString("ollama_base_url", "http://10.0.0.2:11434", synchronous = true)

        val subject = manager()

        assertEquals("sk-ds", subject.getApiKey(CloudProvider.DEEPSEEK).first())
        assertEquals("gemini-x", subject.getModel(CloudProvider.GOOGLE).first())
        assertEquals("http://10.0.0.2:11434", subject.getBaseUrl(CloudProvider.OLLAMA).first())
    }

    @Test
    fun `given a key saved for one provider when another is read then it stays unset`() = runTest {
        val subject = manager()
        subject.setApiKey(CloudProvider.OPENAI, "sk-openai")

        assertNull(subject.getApiKey(CloudProvider.ANTHROPIC).first())
        assertNull(subject.getModel(CloudProvider.OPENAI).first())
    }

    @Test
    fun `given a slot the provider does not use when read then it is null`() = runTest {
        assertNull(manager().getApiKey(CloudProvider.OLLAMA).first())
        assertNull(manager().getBaseUrl(CloudProvider.OPENAI).first())
    }
}
