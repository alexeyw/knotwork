package app.knotwork.android.presentation.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the hand-maintained [AboutAcknowledgments] list surfaced on the
 * About screen.
 *
 * The list is the user-facing companion of the repository `NOTICE` file. These
 * assertions keep it from silently rotting as dependencies change: they fail the
 * build if a credit is blank, duplicated, references a component that is no
 * longer a dependency, or drops a component whose license requires an explicit
 * attribution notice (SQLCipher BSD-3-Clause, the SIL OFL 1.1 brand fonts).
 */
class AboutAcknowledgmentsTest {

    @Test
    fun `given acknowledgments when read then list is not empty`() {
        assertTrue("Acknowledgments list must not be empty", AboutAcknowledgments.ENTRIES.isNotEmpty())
    }

    @Test
    fun `given acknowledgments when read then every entry has a non-blank name and license`() {
        AboutAcknowledgments.ENTRIES.forEach { entry ->
            assertTrue("Entry name must not be blank", entry.name.isNotBlank())
            assertTrue("License must not be blank for '${entry.name}'", entry.license.isNotBlank())
        }
    }

    @Test
    fun `given acknowledgments when read then there are no duplicate names`() {
        val names = AboutAcknowledgments.ENTRIES.map { it.name }
        assertEquals("Acknowledgments must not contain duplicate names", names.size, names.distinct().size)
    }

    @Test
    fun `given acknowledgments when read then notice-required components are present`() {
        // SQLCipher (BSD-3-Clause) and the bundled SIL OFL 1.1 fonts each carry a
        // license that mandates reproducing an attribution notice. They must never
        // be dropped from the user-facing list while they ship in the APK.
        val names = AboutAcknowledgments.ENTRIES.map { it.name }.toSet()
        listOf("SQLCipher", "Inter", "JetBrains Mono").forEach { required ->
            assertTrue("Notice-required component '$required' is missing", required in names)
        }
    }

    @Test
    fun `given acknowledgments when read then the bundled embedding model is credited`() {
        // The Universal Sentence Encoder TFLite model ships in the released
        // APK/AAB (assets/universal_sentence_encoder.tflite) and must remain in
        // the attribution audit even though it is an asset rather than a Gradle
        // dependency.
        val names = AboutAcknowledgments.ENTRIES.map { it.name }.toSet()
        assertTrue("Bundled embedding model must be credited", "Universal Sentence Encoder" in names)
    }

    @Test
    fun `given acknowledgments when read then the bundled fonts are credited under OFL`() {
        val fonts = AboutAcknowledgments.ENTRIES.filter { it.name == "Inter" || it.name == "JetBrains Mono" }
        assertEquals("Both brand fonts must be listed", 2, fonts.size)
        fonts.forEach { entry ->
            assertEquals("Font '${entry.name}' must be credited under SIL OFL 1.1", "SIL OFL 1.1", entry.license)
        }
    }

    @Test
    fun `given acknowledgments when read then stale non-dependencies are absent`() {
        // Retrofit and Coil were listed historically but are not actual runtime
        // dependencies (the network stack is OkHttp + Ktor). Guard against their
        // reintroduction as phantom credits.
        val names = AboutAcknowledgments.ENTRIES.map { it.name }.toSet()
        listOf("Retrofit", "Coil").forEach { stale ->
            assertFalse("Stale non-dependency '$stale' must not be listed", stale in names)
        }
    }
}
