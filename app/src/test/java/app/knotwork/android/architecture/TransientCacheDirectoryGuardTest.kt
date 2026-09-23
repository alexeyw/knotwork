package app.knotwork.android.architecture

import app.knotwork.android.domain.constants.TransientCacheDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every directory the app creates under its cache is an entry of
 * [TransientCacheDirectory] — and therefore swept by the daily maintenance pass.
 *
 * **Why.** A handoff file that nobody deletes stays until the device runs low on
 * storage, which on most devices is never. The camera wrote the full-resolution
 * original of every photo taken from the composer (the only copy with its GPS
 * EXIF) into such a directory, and nothing deleted it; the Files screen's share
 * copies outlived both the share and the file. Each owner now cleans up, but an
 * owner can miss (a process death, a result that never arrives), and the sweep
 * only covers what the registry lists. This guard makes the registry the list.
 *
 * **What it reads.** The production sources, comments removed
 * ([ProductionSources]): a use of `.cacheDir` must name a registry directory on
 * the same line (through `.dirName`, a property only the registry declares), or be
 * in [NOT_A_HANDOFF] with its reason. And `res/xml/file_paths.xml`: every
 * `<cache-path>` the `FileProvider` serves must be a registry directory. A cache
 * directory reached some other way (`cacheDir.resolve`, a path string) is not seen
 * — which is why the first rule forbids a bare `.cacheDir` rather than looking for
 * a particular constructor.
 */
class TransientCacheDirectoryGuardTest {

    @Test
    fun `every production use of the cache directory names a registry entry`() {
        val offenders = ProductionSources.code
            .filterKeys { path -> NOT_A_HANDOFF.keys.none { path.endsWith(it) } }
            .mapValues { (_, code) -> offendersIn(code) }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "code reaches the cache directory without naming a TransientCacheDirectory entry. Add the " +
                "directory to the registry (so the daily sweep removes what its owner misses) and build it " +
                "as File(cacheDir, TransientCacheDirectory.X.dirName).",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `only the registry declares dirName`() {
        val declaring = ProductionSources.code.filterValues { DIR_NAME_DECLARATION.containsMatchIn(it) }.keys

        assertEquals(
            "`.dirName` is how the rule above recognises a registry directory, so no other type may declare it",
            setOf("main/java/app/knotwork/android/domain/constants/TransientCacheDirectory.kt"),
            declaring,
        )
    }

    @Test
    fun `every cache path the FileProvider serves is a registry directory`() {
        val xml = File(ProductionSources.moduleDirectory(), "src/main/res/xml/file_paths.xml").readText()
        val served = CACHE_PATH.findAll(xml).map { it.groupValues[1].trimEnd('/') }.toSet()

        assertTrue("file_paths.xml serves no cache path — the parse is broken", served.isNotEmpty())
        assertEquals(
            "a <cache-path> is not a TransientCacheDirectory entry, so nothing sweeps it",
            emptySet<String>(),
            served - TransientCacheDirectory.entries.map { it.dirName }.toSet(),
        )
    }

    @Test
    fun `the census recognises the handoff directories it claims to`() {
        // Keeps the first rule from passing vacuously: these sites build their
        // directories through the registry today.
        val sites = ProductionSources.code.values.sumOf { code -> CACHE_DIR_USE.findAll(code).count() }
        assertTrue("only $sites cache-directory uses recognised", sites >= MIN_KNOWN_SITES)
        assertEquals(1, offendersIn("val dir = File(context.cacheDir, \"images\")").size)
        assertEquals(1, offendersIn("val dir = context.cacheDir.resolve(\"shared\")").size)
        assertEquals(0, offendersIn("File(context.cacheDir, TransientCacheDirectory.CAMERA_CAPTURE.dirName)").size)
    }

    /** The lines of [code] that use `.cacheDir` without naming a registry directory. */
    private fun offendersIn(code: String): List<String> =
        code.lines().filter { CACHE_DIR_USE.containsMatchIn(it) && !it.contains(".dirName") }.map { it.trim() }

    private companion object {
        /** A use of the app's cache directory; `externalCacheDir` / `codeCacheDir` are other words. */
        val CACHE_DIR_USE = Regex("""\.cacheDir\b""")

        /** A declaration of a `dirName` property. */
        val DIR_NAME_DECLARATION = Regex("""\bval\s+dirName\b""")

        /** The `path` attribute of a `<cache-path>` element. */
        val CACHE_PATH = Regex("""<cache-path[^>]*?path="([^"]*)"""")

        /** Registry sites today: audio, camera, workspace share, journal, and the sweep. */
        const val MIN_KNOWN_SITES = 5

        /**
         * Cache uses that are not handoff directories, by path suffix, with why the
         * sweep must not touch them.
         */
        val NOT_A_HANDOFF = mapOf(
            "/data/engine/LiteRTLlmEngine.kt" to
                "LiteRT-LM's own compilation cache: the runtime owns it, and deleting it only costs a " +
                "recompile — it holds no user content",
        )
    }
}
