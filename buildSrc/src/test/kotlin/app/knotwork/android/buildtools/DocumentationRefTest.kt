package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DocumentationRef].
 *
 * The rule exists here rather than in the app precisely so both branches can be
 * tested: an app-side unit test only ever observes the debug `BuildConfig`, so
 * the release branch — the one that matters to a store user — would be asserted
 * by nothing.
 */
class DocumentationRefTest {

    @Test
    fun `given a release build when the ref is resolved then it is the version tag`() {
        assertEquals("v0.9.0", DocumentationRef.of(release = true, versionName = "0.9.0"))
    }

    @Test
    fun `given a debug build when the ref is resolved then it is the default branch`() {
        assertEquals("main", DocumentationRef.of(release = false, versionName = "0.9.0"))
    }

    @Test
    fun `given a debug build when the ref is resolved then the version name is not consulted`() {
        // The rule is a function of the build type alone. Reading the version
        // name here is what would make `versionNameSuffix` — set today for an
        // unrelated reason — silently decide where the links point.
        assertEquals(
            DocumentationRef.of(release = false, versionName = "0.9.0"),
            DocumentationRef.of(release = false, versionName = "0.9.0-debug"),
        )
    }
}
