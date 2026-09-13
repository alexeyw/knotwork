package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [uncoveredPrefixes], the guard every glob-scoped scan uses to
 * fail when a declared root stops contributing files instead of passing over
 * nothing.
 */
class UncoveredPrefixesTest {

    @Test
    fun `given every prefix covered when checked then nothing uncovered`() {
        val paths = listOf("README.md", "app/build.gradle.kts", "docs/faq.md")

        assertTrue(uncoveredPrefixes(paths, listOf("", "app/", "docs/")).isEmpty())
    }

    @Test
    fun `given a root with no file when checked then that prefix reported`() {
        val paths = listOf("app/build.gradle.kts", "docs/faq.md")

        assertEquals(listOf("", "fastlane/"), uncoveredPrefixes(paths, listOf("", "app/", "fastlane/")))
    }
}
