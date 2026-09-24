package app.knotwork.android.architecture

import app.knotwork.android.domain.models.JournalExportDocument
import app.knotwork.android.presentation.ui.common.JournalExportDelegate
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konsist guard on the journal exports: **the journal leaves the device only in
 * the user's own hands.**
 *
 * The trigger-evaluation journal and the external-request journal are the app's
 * two records of what it did while nobody was watching, and both live in the
 * SQLCipher-encrypted database. The export exists so their owner can hand one to
 * a bug report or to an offline analysis — through the system share sheet or a
 * file they picked, on an explicit tap. What must never appear on that path is a
 * second route: an upload, a crash-reporter attachment, a "help us improve"
 * sync. The difference between the two is not visible in the UI, and by the time
 * it would be noticed the journals would already have travelled.
 *
 * So the rule is structural rather than prose: no file on the export path may
 * import a network client (OkHttp, Retrofit, Koog, Ktor or raw `java.net`). As on
 * the other two no-network surfaces, `android.net` is deliberately allowed — the
 * document picker hands this path an `android.net.Uri`, and a rule that has to be
 * suppressed on its first day is not a rule.
 *
 * Two bounds worth stating rather than discovering later:
 *
 *  - The scope is `app/src/main` (see [ArchitectureScope]). `TriggerJournalDumpReceiver`
 *    lives in `src/debug` and is therefore outside it — acceptably, because that
 *    file exists only in debuggable builds and shares this path's one renderer,
 *    which *is* guarded.
 *  - A filter over names and paths goes stale in silence. The second test closes
 *    that with a different instrument — the file's own text — so a new file that
 *    talks about a journal export but escapes the filter fails the build instead
 *    of quietly leaving the guarded set.
 */
class JournalExportNoNetworkKonsistTest {

    @Test
    fun `journal-export files import no network client`() {
        val files = guardedFiles()

        // A guard that matched nothing would pass everything — the exact failure
        // mode a privacy rule must not have.
        assertTrue(EMPTY_SCOPE_FAILURE, files.size >= MINIMUM_GUARDED_FILES)

        files.assertFalse(additionalMessage = NETWORK_IMPORT_FAILURE) { file ->
            file.imports.any { import ->
                NetworkClientImports.PREFIXES.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `every production file naming a journal export is inside the guarded scope`() {
        val guarded = guardedFiles().map { it.projectPath }.toSet()
        val unguarded = ArchitectureScope.production
            .files
            .filter { file -> JOURNAL_EXPORT_MENTIONS.any { file.text.contains(it) } }
            // `projectPath`, not `path`: an absolute path would name this machine's
            // checkout in a failure every other reader has to translate.
            .map { it.projectPath }
            .filterNot { it in guarded }
            .sorted()

        assertTrue(
            "these files name a journal export but fall outside the no-network guard, so they are " +
                "unprotected: $unguarded. Name the file into the guard (a `JournalExport…` / " +
                "`…JournalExportUseCase` name), rather than widening the filter until it matches everything.",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `given the export types when renamed then the guard's token no longer describes them`() {
        // The filter below is a substring of the export types' own names. Deriving
        // it that way is only safe if something notices when the names move, so
        // this is that something: a rename that leaves the token behind fails here
        // rather than silently emptying the guarded set.
        assertTrue(
            "the journal-export guard keys off the \"$EXPORT_TOKEN\" token, which no longer appears in the " +
                "names of the export types it is derived from — update the token together with the rename.",
            listOf(JournalExportDelegate::class, JournalExportDocument::class)
                .all { it.simpleName?.contains(EXPORT_TOKEN) == true },
        )
    }

    /**
     * The journal-export surface: the shared presentation helpers, the domain
     * renderers, and every file that reaches for one of them.
     *
     * The import arm is what actually holds the line. The two
     * `Export…JournalUseCase`s and both journal screens carry no export token in
     * their own names, and a name-only filter left all four unguarded — which is
     * how the coverage test below earned its place on the first run.
     *
     * @return Every production file on the journal-export path.
     */
    private fun guardedFiles() = ArchitectureScope.production
        .files
        .filter { file ->
            file.name.contains(EXPORT_TOKEN) || file.imports.any { it.name.contains(EXPORT_TOKEN) }
        }

    private companion object {
        /**
         * The token shared by every export type's name — the one the filter and
         * the coverage test both key off. Pinned to the real declarations by the
         * third test, so a rename fails loudly instead of emptying the guard.
         */
        const val EXPORT_TOKEN = "JournalExport"

        /** Text that marks a file as talking about a journal export, for the coverage test. */
        val JOURNAL_EXPORT_MENTIONS = listOf(EXPORT_TOKEN)

        /**
         * Floor for the guarded set. The surface is small by design — two
         * renderers, one document type, two read-and-render use cases, the delegate
         * with its UI wiring, and the two screens with their ViewModels — and a
         * filter that suddenly matches a couple of files has stopped describing it.
         */
        const val MINIMUM_GUARDED_FILES = 8

        const val EMPTY_SCOPE_FAILURE =
            "the journal-export no-network guard matched almost nothing. The surface has not shrunk to a " +
                "couple of files, so the name / import filter has stopped describing it — fix the filter " +
                "rather than letting the guard pass by covering nothing."

        const val NETWORK_IMPORT_FAILURE =
            "the journals leave the device only in the user's own hands: no file on the journal-export path " +
                "may import a network client (NetworkClientImports). The export goes to " +
                "the system share sheet or to a file the user picked, on an explicit action — never to a " +
                "server, and never as a side effect of something else."
    }
}
