package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps this module's Compose tests — instrumented, and the JVM screenshots of
 * `presentation/screenshots/` — on the v2 Compose test APIs.
 *
 * **Why.** The v1 factories (`junit4.createComposeRule`, `createAndroidComposeRule`, `runComposeUiTest`
 * and the rest) are deprecated. They run the composition's coroutines (effects,
 * `rememberCoroutineScope`) on an `UnconfinedTestDispatcher`, so one launched there runs at once and
 * can hide a timing bug the app has. The v2 factories in the `v2`
 * packages use a `StandardTestDispatcher`, as `runTest` does. Every test moved to v2 at once; this
 * fails the next one written against v1. The design system's screenshot tests have the same guard
 * in `:catalog`.
 *
 * **Why a source scan.** The `androidTest` source set is not on the JVM unit-test classpath, so the
 * guard reads its files. `src/androidTest` is a declared input of this module's test tasks, so an
 * edit there re-runs it; `src/test` compiles with them.
 */
class ComposeTestRuleV2GuardTest {

    @Test
    fun `given a Compose test then it builds its Compose rule with the v2 factories`() {
        val files = SOURCE_SETS.flatMap { sourceSet ->
            val sources = File(ProductionSources.moduleDirectory(), sourceSet).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
            assertTrue(
                "found no v2 Compose rule in $sourceSet — the scan no longer matches the code",
                sources.any { V2_FACTORY.containsMatchIn(it.readText()) },
            )
            sources
        }

        val v1 = files.flatMap { file ->
            V1_API.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList()
        }

        assertEquals("use the v2 factory (the `v2` package) instead", emptyList<String>(), v1)
    }

    private companion object {
        /** Every source set of this module that composes in a test. */
        val SOURCE_SETS = listOf("src/androidTest", "src/test")

        /**
         * A deprecated v1 factory, imported or written out. The rule and environment *types*
         * (`junit4.AndroidComposeTestRule`, `AndroidComposeUiTestEnvironment`) are left out: the v2
         * factories return them, so naming one is valid v2 code; only their v1 construction is
         * deprecated, and a test reaches it through these factories.
         */
        val V1_API = Regex(
            """androidx\.compose\.ui\.test\.(junit4\.(createComposeRule|createAndroidComposeRule|""" +
                """createEmptyComposeRule)|runComposeUiTest|runAndroidComposeUiTest|runEmptyComposeUiTest)\b""",
        )

        /** A v2 factory, for the check that the scan reads what it claims to. */
        val V2_FACTORY = Regex("""androidx\.compose\.ui\.test\.junit4\.v2\.create\w*ComposeRule\b""")
    }
}
