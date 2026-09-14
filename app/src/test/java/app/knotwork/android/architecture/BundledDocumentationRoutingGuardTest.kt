package app.knotwork.android.architecture

import app.knotwork.android.domain.constants.DocumentationLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps a document that ships in the app from opening in the browser.
 *
 * `openDocumentation` routes by the registry's `delivery`, but only when the caller
 * hands it a reader: without one, a bundled document goes to the web like any other.
 * That fallback is what let the onboarding Ready step send its FAQ link — a bundled
 * document, on the step where the network may not be set up yet — to the browser,
 * while every screen around it looked correct.
 *
 * The guard reads every call in `src/main/java` and fails when a call that can reach a
 * bundled document passes no reader. A call naming a registry constant is resolved
 * against the registry itself, so moving a document into the APK makes its readerless
 * callers fail here rather than on a device. A call whose id is a variable has to name,
 * in [DYNAMIC_ID_SOURCES], the file its ids come from.
 *
 * Sources are read from disk; an edit to them recompiles `:app`, which re-runs this
 * test (measured for the tests that read `src/main` the same way).
 */
class BundledDocumentationRoutingGuardTest {

    @Test
    fun `given a call that can reach a bundled document then it passes the in-app reader`() {
        val calls = callSites()
        assertTrue("found no openDocumentation call — the scan no longer matches the code", calls.isNotEmpty())

        val offenders = calls.filter { it.arguments.size < READER_ARGUMENT_COUNT }.mapNotNull { call ->
            val ids = idsReaching(call)
                ?: return@mapNotNull "${call.location}: id `${call.arguments[1]}` is neither a registry " +
                    "constant nor listed in DYNAMIC_ID_SOURCES"
            val bundled = ids.filter { DocumentationLinks.byId(it)?.delivery == DocumentationLinks.Delivery.BUNDLED }
            if (bundled.isEmpty()) null else "${call.location}: opens bundled $bundled without a reader"
        }

        assertEquals("pass the reader navigation as the third argument", emptyList<String>(), offenders)
    }

    @Test
    fun `given the dynamic callers listed then each still calls with a variable id`() {
        val dynamic = callSites().filter { !it.arguments[1].startsWith(CONSTANT_PREFIX) }.map { it.file.name }.toSet()

        assertEquals("keep DYNAMIC_ID_SOURCES in step with the code", DYNAMIC_ID_SOURCES.keys, dynamic)
    }

    /** One `openDocumentation(...)` call, with its top-level arguments as written. */
    private data class CallSite(val file: File, val line: Int, val arguments: List<String>) {
        val location: String get() = "${file.name}:$line"
    }

    private fun idsReaching(call: CallSite): List<String>? {
        val idArgument = call.arguments[1]
        if (idArgument.startsWith(CONSTANT_PREFIX)) {
            return listOf(constantValue(idArgument.removePrefix(CONSTANT_PREFIX)))
        }
        val source = DYNAMIC_ID_SOURCES[call.file.name] ?: return null
        return CONSTANT_REFERENCE.findAll(File(MAIN_SOURCES, source).readText())
            .map { constantValue(it.groupValues[1]) }
            .toList()
            .also { assertTrue("$source names no registry constant", it.isNotEmpty()) }
    }

    private fun constantValue(name: String): String = DocumentationLinks::class.java.getField(name).get(null) as String

    private fun callSites(): List<CallSite> = File(MAIN_SOURCES).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file -> callsIn(file) }
        .toList()

    private fun callsIn(file: File): List<CallSite> {
        val text = file.readText()
        return CALL.findAll(text)
            .filterNot { text.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { match ->
                val open = match.range.last
                CallSite(
                    file = file,
                    line = text.substring(0, open).count { it == '\n' } + 1,
                    arguments = topLevelArguments(text, open),
                )
            }
            .toList()
    }

    /**
     * Splits the argument list that opens at [open] on its top-level commas, so a lambda
     * or a nested call inside an argument stays in one piece.
     */
    private fun topLevelArguments(text: String, open: Int): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (index in open + 1 until text.length) {
            val char = text[index]
            when {
                char in "({[" -> depth++
                char in ")}]" && depth == 0 -> break
                char in ")}]" -> depth--
                char == ',' && depth == 0 -> {
                    arguments += current.toString().trim()
                    current.clear()
                    continue
                }
            }
            current.append(char)
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { arguments += it }
        return arguments
    }

    private companion object {
        const val MAIN_SOURCES = "src/main/java/app/knotwork/android"
        const val CONSTANT_PREFIX = "DocumentationLinks."
        const val READER_ARGUMENT_COUNT = 3
        val CALL = Regex("""\bopenDocumentation\(""")
        val CONSTANT_REFERENCE = Regex("""DocumentationLinks\.(ID_[A-Z_]+)""")

        /**
         * Callers whose id is a variable, keyed by file name, with the file (relative to
         * [MAIN_SOURCES]) that supplies every id they can receive.
         */
        val DYNAMIC_ID_SOURCES = mapOf(
            "SettingsScreens.kt" to "presentation/ui/settings/SettingsHelpCatalog.kt",
        )
    }
}
