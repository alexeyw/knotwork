package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Census of the presentation code that reads or writes JSON with `org.json`: every JSON a
 * person reads goes through the shared layout (`presentation/common/JsonLayout.kt`), and the
 * files that use `org.json` for anything else are named here with the reason.
 *
 * **Why.** `org.json` rewrites what it reads: on Android it writes `/` as `\/`, and it reads
 * numbers into `Long` or `Double`, so `1.50` comes back as `1.5` and a big integer loses its
 * digits. The approval card once rebuilt a tool call's arguments that way and showed a
 * different text from the one an MCP server received — while the user approves what runs
 * (`§76`). [JsonLayout] copies every token as written; this guard keeps a new screen from
 * showing JSON through `org.json` again. `JsonLayoutLosslessTest` holds the layout itself.
 *
 * **What it reads.** The production sources of `:app`'s presentation layer and of
 * `:catalog`, comments removed, for an `org.json` import or a fully qualified use. The
 * catalog never parses a value — the host lays it out — so no catalog file may appear.
 */
class JsonDisplayGuardTest {

    @Test
    fun `every presentation file using org json is in the census`() {
        val users = sources.filterValues { ORG_JSON.containsMatchIn(it) }.keys

        assertEquals(
            "the set of presentation files using org.json changed. org.json rewrites what it reads (`/` " +
                "as `\\/`, 1.50 as 1.5): JSON a person reads goes through presentation/common/JsonLayout. " +
                "If the file uses org.json for something no one reads as JSON, name it in " +
                "JsonDisplayGuardTest.ORG_JSON_USERS with the reason — or remove the one that is gone.",
            ORG_JSON_USERS.keys,
            users,
        )
    }

    @Test
    fun `the census sees an import, a wildcard and a fully qualified use, and reads both modules`() {
        assertTrue(ORG_JSON.containsMatchIn("import org.json.JSONObject"))
        assertTrue(ORG_JSON.containsMatchIn("import org.json.*"))
        assertTrue(ORG_JSON.containsMatchIn("val o = org.json.JSONArray(text)"))
        assertTrue(sources.keys.any { it.startsWith(PRESENTATION) })
        assertTrue(sources.keys.count { it.startsWith("catalog/") } >= MIN_CATALOG_FILES)
    }

    private companion object {
        const val PRESENTATION = "main/java/app/knotwork/android/presentation/"
        const val MIN_CATALOG_FILES = 100

        val ORG_JSON = Regex("""(?<![A-Za-z0-9_])org\.json\.""")

        /** Presentation files that use `org.json` for a purpose no one reads as JSON. */
        val ORG_JSON_USERS = mapOf(
            "${PRESENTATION}ui/pipeline/editor/config/NodeConfigCodec.kt" to
                "encodes and decodes a node's configuration for the pipeline; never shown",
            "${PRESENTATION}ui/chat/home/ConsoleCopyPayloads.kt" to
                "quotes a variable's value for the clipboard (Copy all), as a JSON string literal",
        )

        val sources: Map<String, String> by lazy {
            val catalog = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main")
            ProductionSources.code.filterKeys { it.startsWith(PRESENTATION) } + catalog.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate {
                    "catalog/" + it.relativeTo(catalog).invariantSeparatorsPath to
                        ProductionSources.stripComments(it.readText())
                }
        }
    }
}
