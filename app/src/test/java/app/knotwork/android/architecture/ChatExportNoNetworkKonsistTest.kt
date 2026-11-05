package app.knotwork.android.architecture

import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.presentation.ui.chat.ChatExportDelegate
import app.knotwork.android.presentation.ui.chat.ChatExportEvent
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konsist guard on the chat export: **a chat leaves the device only in the user's
 * own hands, and only as the one file.**
 *
 * The export holds the whole conversation — every message, and the error text and
 * prompt of every run that did not finish. It exists so its owner can keep it or
 * pass it on, through the system share sheet or a file they picked, on an explicit
 * tap. What must never appear on that path is a second route (an upload, a
 * crash-reporter attachment, a sync), so, as on the journal and run-trace exports,
 * no file on the path may import a network client ([NetworkClientImports]);
 * `android.net` stays allowed (a picked document arrives as an `android.net.Uri`).
 *
 * The path is selected by the `ChatExport` token in a file's name, its imports or
 * the classes it declares (`ExportChatUseCase.kt` declares the document type), plus
 * the shared hand-off file both exports stage and write through. Two tests keep
 * that selection honest — a production file whose text names the export but falls
 * outside it fails, and so does a rename that leaves the token or the hand-off
 * behind.
 *
 * The last test holds the other half of the promise: the document's text is read
 * in exactly two places, each passing it straight to the shared hand-off, so the
 * file shared and the file saved cannot be encoded two ways (`ChatExportHandOffTest`
 * proves the encoding).
 */
class ChatExportNoNetworkKonsistTest {

    @Test
    fun `chat-export files import no network client`() {
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
    fun `every production file naming the chat export is inside the guarded scope`() {
        val guarded = guardedFiles().map { it.projectPath }.toSet()
        val unguarded = ArchitectureScope.production
            .files
            .filter { file -> file.text.contains(EXPORT_TOKEN) || file.text.contains(EXPORT_USE_CASE) }
            .map { it.projectPath }
            .filterNot { it in guarded }
            .sorted()

        assertTrue(
            "these files name the chat export but fall outside the no-network guard, so they are " +
                "unprotected: $unguarded. Name the file into the guard (a `ChatExport…` name, or an import " +
                "of one), rather than widening the filter until it matches everything.",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `given the export types when renamed then the guard's token and hand-off still describe them`() {
        assertTrue(
            "the chat-export guard keys off the \"$EXPORT_TOKEN\" token, which no longer appears in the " +
                "names of the export types it is derived from — update the token together with the rename.",
            listOf(ChatExportDocument::class, ChatExportDelegate::class, ChatExportEvent::class)
                .all { it.simpleName?.contains(EXPORT_TOKEN) == true },
        )
        val handOff = ArchitectureScope.production.files.filter { it.name == HAND_OFF_FILE }
        assertEquals("exactly one $HAND_OFF_FILE.kt is expected", 1, handOff.size)
        assertEquals(
            "$HAND_OFF_FILE.kt no longer declares the hand-off this guard follows",
            HAND_OFF_FUNCTIONS,
            handOff.single()
                .functions(includeNested = true)
                .map { it.name }
                .filter { it in HAND_OFF_FUNCTIONS }
                .toSet(),
        )
    }

    @Test
    fun `the chat document's text is read only to hand it to the shared hand-off`() {
        val readers = guardedFiles()
            .filter { file -> file.name != DOCUMENT_FILE && file.name != HAND_OFF_FILE }
            .associate { file -> file.name to file.text.lines().filter { DOCUMENT_JSON_READ.containsMatchIn(it) } }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "a chat document's json is read outside the two hand-off call sites: $readers. Pass it to " +
                "ExportDocumentHandOff.share / .write where it is read — a second encoder is how a shared " +
                "file and a saved file stop being the same bytes.",
            setOf("ChatExportDelegate", "ChatExportFile"),
            readers.keys,
        )
        readers.forEach { (file, lines) ->
            lines.forEach { line ->
                assertTrue(
                    "$file reads the document's json other than as a hand-off argument: `${line.trim()}`",
                    HAND_OFF_ARGUMENT.any { it.containsMatchIn(line) },
                )
            }
        }
    }

    /**
     * The chat-export path: the document type and its renderer, the delegate and
     * its effects, both screens and their ViewModels — every file named for, or
     * reaching for, a `ChatExport…` type or the renderer (the chat ViewModel only
     * injects it) — and the hand-off file both exports use.
     *
     * @return Every production file on the path.
     */
    private fun guardedFiles() = ArchitectureScope.production
        .files
        .filter { file ->
            file.name == HAND_OFF_FILE ||
                file.name.contains(EXPORT_TOKEN) ||
                file.imports.any { it.name.contains(EXPORT_TOKEN) || it.name.endsWith(".$EXPORT_USE_CASE") } ||
                file.classes(includeNested = true).any { it.name.contains(EXPORT_TOKEN) }
        }

    private companion object {
        /** The token shared by the export types' names, pinned to them by the third test. */
        const val EXPORT_TOKEN = "ChatExport"

        /** The renderer's name, which carries the words in the other order; importing it puts a file on the path. */
        const val EXPORT_USE_CASE = "ExportChatUseCase"

        /** The file declaring the document type and its renderer. */
        const val DOCUMENT_FILE = "ExportChatUseCase"

        /** The shared hand-off: one encoder behind the staged share and the picked file. */
        const val HAND_OFF_FILE = "ExportDocumentHandOff"

        /** What the hand-off file must keep declaring for this guard to mean anything. */
        val HAND_OFF_FUNCTIONS = setOf("share", "write", "bytes")

        /** A read of a document's text: `document.json`, `event.document.json`. */
        val DOCUMENT_JSON_READ = Regex("""\bdocument\.json\b""")

        /** The two shapes a read may take: an argument of the share or of the write. */
        val HAND_OFF_ARGUMENT = listOf(
            Regex("""^\s*json = document\.json,\s*$"""),
            Regex("""ExportDocumentHandOff\.write\(outputStream, document\.json, ioDispatcher\)"""),
        )

        /** Floor for the guarded set: document, delegate, effects, file name, hand-off, two screens, two VMs. */
        const val MINIMUM_GUARDED_FILES = 8

        const val EMPTY_SCOPE_FAILURE =
            "the chat-export no-network guard matched almost nothing — the name / import filter has " +
                "stopped describing the path; fix the filter rather than letting the guard pass by covering nothing."

        const val NETWORK_IMPORT_FAILURE =
            "a chat leaves the device only in the user's own hands: no file on the chat-export path may " +
                "import a network client (NetworkClientImports). The export goes to the system share sheet or " +
                "to a file the user picked, on an explicit action — never to a server."
    }
}
