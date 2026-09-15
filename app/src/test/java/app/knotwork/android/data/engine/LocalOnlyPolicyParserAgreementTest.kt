package app.knotwork.android.data.engine

import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.android.domain.services.LocalOnlyPolicy
import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks [LocalOnlyPolicy] against the parser that actually opens the connection.
 *
 * The policy reads the host with its own small parser ([CleartextPolicy.hostOf]),
 * while the Koog Ollama client hands the same string to Ktor. If the two disagree
 * about which host a URL names, the policy approves one host and the request goes
 * to another — so every URL the policy calls local must name, for Ktor, exactly
 * the host the policy judged.
 */
class LocalOnlyPolicyParserAgreementTest {

    @Test
    fun `given adversarial urls when judged local then Ktor resolves the same host`() {
        val disagreements = ADVERSARIAL_URLS.mapNotNull { url ->
            if (!LocalOnlyPolicy.isLocalEndpoint(url)) return@mapNotNull null
            val policyHost = CleartextPolicy.hostOf(url)
            val ktorHost = try {
                Url(url).host
            } catch (e: IllegalArgumentException) {
                // Ktor refuses to build the URL, so no connection is opened at all.
                return@mapNotNull null
            }
            if (ktorHost.equals(policyHost, ignoreCase = true)) null else "$url → policy=$policyHost ktor=$ktorHost"
        }
        assertEquals(emptyList<String>(), disagreements)
    }

    private companion object {
        val ADVERSARIAL_URLS = listOf(
            "https://192.168.1.42:11434",
            "http://localhost:11434/api",
            "https://evil.example\\@192.168.1.42:11434",
            "https://evil.example\\.192.168.1.42",
            "https://192.168.1.42:11434@evil.example",
            "https://user:pass@192.168.1.42:11434",
            "https://evil.example#@192.168.1.42",
            "https://evil.example?@192.168.1.42",
            "https://evil.example;@192.168.1.42",
            "https://evil.example%40192.168.1.42",
            "https://evil.example%2F@192.168.1.42",
            "https://evil.example @192.168.1.42",
            "https://evil.example\t@192.168.1.42",
            "https:/192.168.1.42:11434",
            "https:\\\\evil.example\\@192.168.1.42",
            "HTTPS://192.168.1.42",
            "https://010.0.0.1",
            "https://192.168.1.42.",
            "https://127.1",
            "https://0x7f.0.0.1",
            "https://[::1]:11434",
            "https://[::ffff:192.168.1.42]",
            "//192.168.1.42:11434",
            "192.168.1.42:11434",
        )
    }
}
