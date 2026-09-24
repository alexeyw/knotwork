package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of every place production code constructs a Koog model client, requiring each
 * to pass the shared deadlines, `CloudClientTimeouts.CONFIG`.
 *
 * **Why a census and not only a test per factory.** Koog's default is 900 s for the
 * request and the socket, and passing no config is silent — it compiles, it works on a
 * healthy provider, and it waits fifteen minutes on a quiet one. The chat factory got
 * explicit deadlines and a test reading them back; the embedding factory was a second
 * construction site built the same way, and nothing looked at it, so memory writes and
 * searches kept the default. `OllamaClient` also keeps no readable copy of its config,
 * so no test can read the value back from it. What a test *can* see is the construction
 * site, and a site added next time is the one this catches.
 *
 * **What it matches.** A call to any class imported from a Koog provider-client package
 * (`ai.koog.prompt.executor.clients.<provider>` or `…ollama.client`) whose name ends in
 * `Client`; the call's argument list — balanced parentheses, comments removed — must
 * name `CloudClientTimeouts.CONFIG`. A client constructed through a helper that is not
 * itself such a class, or reached by a fully qualified name without an import, is not
 * seen: it is a census of one spelling, which is how both existing sites are written.
 */
class KoogClientTimeoutKonsistTest {

    @Test
    fun `every Koog model client is built with the shared deadlines`() {
        val offenders = constructions().filterNot { it.arguments.contains(SHARED_CONFIG) }
            .map { "${it.file}: ${it.clientName}(…)" }

        assertEquals(
            "these Koog clients are built without $SHARED_CONFIG, so Koog's 900 s request and " +
                "socket defaults apply to them — a provider that goes quiet holds the call for fifteen " +
                "minutes. Pass the shared config (settings = …ClientSettings(timeoutConfig = …) for the " +
                "hosted providers, timeoutConfig = … for OllamaClient).",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the census finds the construction sites it claims to`() {
        // Keeps the rule above from passing vacuously: both factories must be seen, the
        // chat one with its five providers.
        val byFile = constructions().groupBy { it.file.substringAfterLast('/') }

        assertEquals("chat clients seen: ${byFile["KoogClientFactory.kt"]}", 5, byFile["KoogClientFactory.kt"]?.size)
        assertTrue("embedding clients seen: ${byFile.keys}", (byFile["KoogEmbedderFactory.kt"]?.size ?: 0) == 2)
    }

    /** One `SomeClient(…)` call in production code. */
    private data class Construction(val file: String, val clientName: String, val arguments: String)

    /** Every construction of an imported Koog provider client across the production sources. */
    private fun constructions(): List<Construction> = ProductionSources.code.flatMap { (file, code) ->
        PROVIDER_CLIENT_IMPORT.findAll(code).map { it.groupValues[1] }.distinct().flatMap { name ->
            Regex("""\b${Regex.escape(name)}\s*\(""").findAll(code).map { call ->
                Construction(file, name, argumentsFrom(code, call.range.last))
            }
        }
    }

    /**
     * The text between the parenthesis at [open] and the one that closes it.
     *
     * @param code Comment-free source text.
     * @param open Index of an opening parenthesis in [code].
     */
    private fun argumentsFrom(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, index)
            }
        }
        error("unbalanced parentheses after index $open")
    }

    private companion object {
        /** The expression every construction site has to pass. */
        const val SHARED_CONFIG = "CloudClientTimeouts.CONFIG"

        /**
         * An import of a Koog provider client — `clients.<provider>.XxxClient` (not the
         * `retry` decorators) or `ollama.client.OllamaClient`; group 1 is the simple name.
         */
        val PROVIDER_CLIENT_IMPORT = Regex(
            """(?m)^import ai\.koog\.prompt\.executor\.(?:clients\.(?!retry\.)[a-z0-9]+|ollama\.client)\.""" +
                """([A-Z]\w*Client)\s*$""",
        )
    }
}
