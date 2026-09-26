package app.knotwork.android.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every component exported **without a permission** reads its caller's extras
 * only inside a function that catches `RuntimeException`.
 *
 * Such a component is reachable by every installed app, which chooses the
 * extras. Unmarshalling a malformed or hostile parcel throws, and an exception
 * escaping an entry point's callback crashes the process — every run in flight
 * with it — before any of the component's own gates has run. The
 * external-automation receiver was written with that guard; the share activity,
 * exported beside it, was not. This test makes the guard a property of the
 * exported set rather than of one file.
 *
 * **What counts as a read:** a call to an intent's `get…Extra` family (a method
 * reference included) or its `extras` bundle, in the component's own source
 * file. A read moved into a helper is checked by the helper's own test —
 * `SharedIntentFieldsTest` for the share activity.
 */
class ExportedEntryExtrasGuardTest {

    @Test
    fun `given a component exported without a permission when it reads extras then the read is guarded`() {
        val entries = unguardedExports()
        assertTrue("no permission-less export found — the census reads nothing", entries.isNotEmpty())
        val failures = mutableListOf<String>()
        var reads = 0
        for (path in entries) {
            val code = ProductionSources.code[path]
            if (code == null) {
                failures += "$path: exported by the manifest but no such source file"
                continue
            }
            val lines = code.lines()
            lines.forEachIndexed { index, line ->
                if (!EXTRAS_READ.containsMatchIn(line)) return@forEachIndexed
                reads++
                val function = enclosingFunction(lines, index)
                if (function == null || !function.contains(GUARD)) {
                    failures += "$path:${index + 1}: extras read outside a function that catches RuntimeException"
                }
            }
        }
        assertTrue("the census found no extras read at all — the pattern has gone stale", reads > 0)
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    /** Source paths (keyed as in [ProductionSources.code]) of the `main` components exported without a permission. */
    private fun unguardedExports(): List<String> {
        val manifest = File(ProductionSources.moduleDirectory(), "src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        return COMPONENT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
            .filter { it.getAttribute("android:exported") == "true" && it.getAttribute("android:permission").isEmpty() }
            .map { element ->
                val name = element.getAttribute("android:name")
                val qualified = if (name.startsWith(".")) "$PACKAGE$name" else name
                "main/java/${qualified.replace('.', '/')}.kt"
            }
    }

    /**
     * The text of the function declared nearest above [index], up to the next
     * function declaration — enough to see whether its body catches.
     */
    private fun enclosingFunction(lines: List<String>, index: Int): String? {
        val start = (index downTo 0).firstOrNull { FUNCTION.containsMatchIn(lines[it]) } ?: return null
        val end = ((index + 1) until lines.size).firstOrNull { FUNCTION.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        const val PACKAGE = "app.knotwork.android"
        const val GUARD = "catch (e: RuntimeException)"
        val COMPONENT_TAGS = listOf("activity", "activity-alias", "receiver", "service", "provider")
        val EXTRAS_READ = Regex("""\bget\w*Extra\b|\.extras\b|::get\w*Extra\b""")
        val FUNCTION = Regex("""^\s*(?:(?:private|internal|public|protected|override|suspend|inline)\s+)*fun\s""")
    }
}
