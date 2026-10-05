package app.knotwork.android.architecture

import app.knotwork.android.domain.models.RunTraceExportDocument
import app.knotwork.android.domain.verification.BuildRunTraceExportUseCase
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konsist guard on the run trace export: **a run's trace leaves the device only in
 * the user's own hands.**
 *
 * The export holds everything the on-device model read during a run — every full
 * prompt, so the memory excerpts and the chat history inside them, with every
 * answer, seed and hash. It exists so its owner can repeat or audit the run
 * elsewhere, through the system share sheet or a file they picked, on an explicit
 * tap. What must never appear on that path is a second route: an upload, a
 * crash-reporter attachment, a sync. Nothing in the UI would tell the two apart.
 *
 * So, as on the journal exports, no file on the export path may import a network
 * client ([NetworkClientImports]); `android.net` stays allowed (a picked document
 * arrives as an `android.net.Uri`). The path is selected by the `RunTraceExport`
 * token in a file's name or imports, and two more tests keep that filter honest: a
 * production file whose text names the export but falls outside the guarded set
 * fails, and so does a rename of the export types that leaves the token behind.
 */
class RunTraceExportNoNetworkKonsistTest {

    @Test
    fun `run-trace-export files import no network client`() {
        val files = guardedFiles()

        // A guard that matched nothing would pass everything.
        assertTrue(EMPTY_SCOPE_FAILURE, files.size >= MINIMUM_GUARDED_FILES)

        files.assertFalse(additionalMessage = NETWORK_IMPORT_FAILURE) { file ->
            file.imports.any { import ->
                NetworkClientImports.PREFIXES.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `every production file naming the run trace export is inside the guarded scope`() {
        val guarded = guardedFiles().map { it.projectPath }.toSet()
        val unguarded = ArchitectureScope.production
            .files
            .filter { file -> file.text.contains(EXPORT_TOKEN) }
            .map { it.projectPath }
            .filterNot { it in guarded }
            .sorted()

        assertTrue(
            "these files name the run trace export but fall outside the no-network guard, so they are " +
                "unprotected: $unguarded. Name the file into the guard (a `RunTraceExport…` name, or an import " +
                "of one), rather than widening the filter until it matches everything.",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `given the export types when renamed then the guard's token no longer describes them`() {
        assertTrue(
            "the run-trace-export guard keys off the \"$EXPORT_TOKEN\" token, which no longer appears in the " +
                "names of the export types it is derived from — update the token together with the rename.",
            listOf(RunTraceExportDocument::class, BuildRunTraceExportUseCase::class)
                .all { it.simpleName?.contains(EXPORT_TOKEN) == true },
        )
    }

    /**
     * The run-trace-export path: the document type, the renderer, and every file
     * that reaches for one of them — the use case that reads and renders a run, and
     * whatever shows the export.
     *
     * @return Every production file on the path.
     */
    private fun guardedFiles() = ArchitectureScope.production
        .files
        .filter { file ->
            file.name.contains(EXPORT_TOKEN) || file.imports.any { it.name.contains(EXPORT_TOKEN) }
        }

    private companion object {
        /** The token shared by the export types' names, pinned to them by the third test. */
        const val EXPORT_TOKEN = "RunTraceExport"

        /** Floor for the guarded set: the document, the renderer and the use case that reads a run. */
        const val MINIMUM_GUARDED_FILES = 3

        const val EMPTY_SCOPE_FAILURE =
            "the run-trace-export no-network guard matched almost nothing — the name / import filter has " +
                "stopped describing the path; fix the filter rather than letting the guard pass by covering nothing."

        const val NETWORK_IMPORT_FAILURE =
            "a run's trace leaves the device only in the user's own hands: no file on the run-trace-export " +
                "path may import a network client (NetworkClientImports). The export goes to the system share " +
                "sheet or to a file the user picked, on an explicit action — never to a server."
    }
}
