package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A path prefix test lives in exactly one production file: `PathContainment`.
 *
 * **Why.** `file.absolutePath.startsWith(root)` reads as a containment check and
 * is not one: the string is compared as written, so `…/audio/../files/x`
 * starts with `…/audio/` and resolves outside it. The audio store's delete guard
 * had exactly that shape, next to a workspace gate and a download gate that
 * canonicalised correctly — three hand-written checks, one of them wrong.
 * `PathContainment` canonicalises both sides and compares with a trailing
 * separator; every other file asks it rather than writing a fourth.
 *
 * **What it reads.** The production sources, comments removed
 * ([ProductionSources]), for `startsWith` called on a path string
 * (`absolutePath`, `canonicalPath`, `path`). A prefix test spelled some other way
 * — through a local variable, `File.startsWith(File)`, `Path.startsWith` — is not
 * seen; the rule is aimed at the shape that shipped.
 */
class PathContainmentGuardTest {

    @Test
    fun `no production file outside PathContainment prefix-tests a path string`() {
        val offenders = ProductionSources.code
            .filterKeys { !it.endsWith(HELPER) }
            .mapValues { (_, code) -> offendersIn(code) }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "a path is prefix-tested as a string. Ask PathContainment.childOrNull (or isSelfOrInside for an " +
                "already canonical path) instead: it canonicalises first, so `..` and symlinks cannot pass.",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `the census recognises the shape it claims to`() {
        // Keeps the rule above from passing vacuously: the helper itself holds the
        // one sanctioned prefix test.
        val helper = ProductionSources.code.entries.single { it.key.endsWith(HELPER) }.value
        assertTrue(offendersIn(helper).isNotEmpty())
        assertEquals(1, offendersIn("return target.takeIf { it.absolutePath.startsWith(rootPath) }").size)
        assertEquals(1, offendersIn("file.canonicalPath.startsWith(dirPrefix)").size)
        assertEquals(1, offendersIn("canonical.path.startsWith(root.path + File.separator)").size)
        assertEquals(0, offendersIn("relativePath.startsWith(\"/\")").size)
    }

    /** The lines of [code] that call `startsWith` on a path string. */
    private fun offendersIn(code: String): List<String> =
        code.lines().filter { PATH_PREFIX_TEST.containsMatchIn(it) }.map { it.trim() }

    private companion object {
        const val HELPER = "/data/local/PathContainment.kt"

        /** `startsWith` on a `File`'s path string. */
        val PATH_PREFIX_TEST = Regex("""\b(absolutePath|canonicalPath|path)\s*\??\.\s*startsWith\s*\(""")
    }
}
