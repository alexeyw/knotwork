package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of every production file that launches the system's create-document picker:
 * each answers the pick through a `PickedDocument`, or is named here with why it does not.
 *
 * **Why.** The picker creates the document — empty — before the app hears back. A
 * save-to-file point that held its content only in composition lost it on a rotation
 * while the picker was open, and one that held it in a view model lost it on a process
 * death; either way it returned without writing, and the user was left an empty file
 * where they chose to save, usually with nothing said. `ContentResolverPickedDocument`
 * is the path that cannot do that: the view model holding the content writes into it,
 * or removes it (`discardUnwritten`) and says so.
 *
 * **What it checks.** Every production file calling `CreateDocument(` builds a
 * `ContentResolverPickedDocument(` or is in [EXEMPT] with its reason; an exemption whose
 * file no longer launches the picker fails, so the list cannot rot.
 */
class PickedDocumentCensusTest {

    @Test
    fun `every create-document launcher answers its pick through a picked document or says why not`() {
        val unguarded = launchers()
            .filterNot { path -> path in EXEMPT }
            .filterNot { path -> ProductionSources.code.getValue(path).contains(RULE) }
            .sorted()

        assertTrue(
            "these files launch the create-document picker but answer the pick without a PickedDocument: " +
                "$unguarded. Hold the content in the view model across the picker and hand it " +
                "ContentResolverPickedDocument(resolver, uri), so a save that cannot write removes the empty " +
                "file the picker created — or add the file to PickedDocumentCensusTest.EXEMPT with the reason.",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `the exemptions name only files that still launch the create-document picker`() {
        assertEquals(emptySet<String>(), EXEMPT.keys - launchers())
    }

    @Test
    fun `an exempt file does not also answer through a picked document`() {
        val both = EXEMPT.keys.filter { path -> ProductionSources.code[path]?.contains(RULE) == true }

        assertEquals("these files use the rule and need no exemption", emptyList<String>(), both)
    }

    /** Production files (paths under `app/src`) that launch the create-document picker. */
    private fun launchers(): Set<String> =
        ProductionSources.code.filterValues { CREATE_DOCUMENT.containsMatchIn(it) }.keys

    private companion object {
        const val RULE = "ContentResolverPickedDocument("
        const val BASE = "main/java/app/knotwork/android/presentation/ui/"

        /** The contract that launches the picker: `ActivityResultContracts.CreateDocument(mime)`. */
        val CREATE_DOCUMENT = Regex("""\bCreateDocument\s*\(""")

        /**
         * Files that launch the picker and answer it another way, with why. The first
         * three render what they write from the database when the stream arrives, so a
         * process death or a rotation loses nothing; a provider that refuses to open the
         * document still leaves it empty there, and nothing is said.
         */
        val EXEMPT: Map<String, String> = mapOf(
            "${BASE}memory/MemoryScreen.kt" to
                "renders the memory export from the database when the stream arrives; holds nothing across the picker",
            "${BASE}settings/SettingsScreens.kt" to
                "renders the memory-base export when the stream arrives; holds nothing across the picker",
            "${BASE}settings/usage/UsageTelemetryScreen.kt" to
                "renders the usage record when the stream arrives; holds nothing across the picker",
            "${BASE}settings/backup/BackupScreen.kt" to
                "writes through SafBackupDocuments, which deletes a document it does not finish and names one " +
                "its provider will not delete",
        )
    }
}
