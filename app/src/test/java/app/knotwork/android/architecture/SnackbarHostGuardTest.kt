package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every snackbar in the app renders through `KnotworkSnackbarHost`, and every host has
 * a place.
 *
 * **What went wrong, all at once.** Twelve screens each wrote their own host. They
 * rendered three different snackbars — Material3's own on eight, the Knotwork one on
 * three, flush with the screen edges — and put them four different ways: in a Scaffold
 * slot, aligned to a Box's bottom, at the bottom with a hand-measured offset (96 dp on
 * one screen, 88 dp on another), and unaligned in a Box, which is the top-left corner,
 * over the top bar (every settings screen, the pipeline library). The Models screen
 * showed its download failures through a host state and rendered no host at all, so no
 * failure ever appeared.
 *
 * **What it reads.** The production sources of `:app` and `:catalog`, comments
 * removed:
 *  1. no raw `SnackbarHost(` outside `KnotworkSnackbarHost.kt`;
 *  2. every `SnackbarHostState()` a file creates is rendered by a `KnotworkSnackbarHost`
 *     in that file;
 *  3. every `KnotworkSnackbarHost(` call sits in a `snackbarHost` lambda (a Scaffold
 *     slot, or a slot the screen forwards to one) or aligns itself;
 *  4. every `snackbarHost` slot a composable declares reaches a Scaffold or an aligned
 *     container.
 * It is a census of spellings: a host state passed to another file and rendered there
 * reads as unrendered, which is the safe direction.
 */
class SnackbarHostGuardTest {

    @Test
    fun `no screen renders the raw Material3 host`() {
        val offenders = sources
            .filterKeys { !it.endsWith("/KnotworkSnackbarHost.kt") }
            .filterValues { RAW_HOST.containsMatchIn(it) }
            .keys

        assertEquals(
            "render snackbars with KnotworkSnackbarHost — one look, one margin",
            emptySet<String>(),
            offenders,
        )
    }

    @Test
    fun `every host state a screen creates is rendered by a host`() {
        val offenders = sources.flatMap { (path, code) ->
            STATE_DECLARATION.findAll(code).map { it.groupValues[1] }
                .filterNot { name ->
                    Regex("""KnotworkSnackbarHost\(\s*(hostState\s*=\s*)?$name\b""").containsMatchIn(code)
                }
                .map { name -> "$path: $name" }
                .toList()
        }

        assertEquals(
            "a SnackbarHostState is shown into but never rendered — its messages never appear",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every host sits in a slot or aligns itself`() {
        val offenders = sources.flatMap { (path, code) ->
            HOST_CALL.findAll(code)
                .filterNot { call ->
                    insideSlotLambda(code, call.range.first) ||
                        argumentsOf(code, call.range.last + 1).contains("align(")
                }
                .map { call -> "$path:${code.substring(0, call.range.first).count { it == '\n' } + 1}" }
                .toList()
        }

        assertEquals(
            "a KnotworkSnackbarHost is placed in neither a snackbarHost slot nor an aligned position",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every snackbar slot reaches a scaffold or an aligned container`() {
        val offenders = sources.filterValues { code -> SLOT_PARAMETER.containsMatchIn(code) }
            .filterValues { code ->
                !SLOT_FORWARD.containsMatchIn(code) &&
                    code.lines().none { it.contains("snackbarHost()") && it.contains("align(") }
            }
            .keys

        assertEquals(
            "a composable declares a snackbarHost slot and places it nowhere",
            emptySet<String>(),
            offenders,
        )
    }

    @Test
    fun `the census sees the hosts it claims to`() {
        // Keeps the rules above from passing vacuously if the patterns stop matching.
        val hosts = sources.values.sumOf { HOST_CALL.findAll(it).count() }
        val slots = sources.values.count { SLOT_PARAMETER.containsMatchIn(it) }
        assertTrue("only $hosts hosts recognised", hosts >= MIN_HOSTS)
        assertTrue("only $slots slots recognised", slots >= MIN_SLOTS)
        assertTrue(RAW_HOST.containsMatchIn("SnackbarHost(hostState = state)"))
        assertTrue(!RAW_HOST.containsMatchIn("KnotworkSnackbarHost(hostState = state)"))
    }

    /** Whether the host call at [offset] lies inside a `snackbarHost = {` / `snackbarHost: … = {` lambda. */
    private fun insideSlotLambda(code: String, offset: Int): Boolean {
        val opener = SLOT_LAMBDA.findAll(code.substring(0, offset)).lastOrNull() ?: return false
        val between = code.substring(opener.range.last + 1, offset)
        return between.count { it == '{' } >= between.count { it == '}' }
    }

    /** Text of the argument list opening just before [start], up to its closing parenthesis. */
    private fun argumentsOf(code: String, start: Int): String {
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return code.substring(start, i)
    }

    private companion object {
        val RAW_HOST = Regex("""(?<![A-Za-z])SnackbarHost\(""")
        val HOST_CALL = Regex("""(?<!fun )\bKnotworkSnackbarHost\(""")
        val STATE_DECLARATION = Regex("""val\s+(\w+)\s*=\s*remember\s*\{\s*SnackbarHostState\(\)""")
        val SLOT_LAMBDA = Regex("""snackbarHost\s*(=|:\s*@Composable\s*\(\)\s*->\s*Unit\s*=)\s*\{""")
        val SLOT_PARAMETER = Regex("""snackbarHost\s*:\s*@Composable\s*\(\)\s*->\s*Unit\s*=\s*\{\s*\}""")
        val SLOT_FORWARD = Regex("""snackbarHost\s*=\s*snackbarHost\b""")

        /** Hosts at the time of writing: twelve screens. */
        const val MIN_HOSTS = 12

        /** Slots at the time of writing: eleven catalog screens and the editor. */
        const val MIN_SLOTS = 12

        /** Production Kotlin of `:app` (every source set) and `:catalog`, comments removed. */
        val sources: Map<String, String> by lazy {
            val module = ProductionSources.moduleDirectory()
            val catalog = File(module.parentFile, "catalog/src/main")
            ProductionSources.code + catalog.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate {
                    "catalog/" + it.relativeTo(catalog).invariantSeparatorsPath to
                        ProductionSources.stripComments(it.readText())
                }
        }
    }
}
