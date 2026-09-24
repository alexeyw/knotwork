package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inventory of every production file that opens a URI through `ContentResolver`,
 * with where that URI comes from.
 *
 * **Why.** The resolver opens a URI with this app's identity. When the URI is
 * chosen by another app, that app borrows the identity: a `file://` URI names the
 * app's private files, and a URI of the app's own unexported `FileProvider`
 * opens freely for the app's own uid. The share target took `EXTRA_STREAM` from
 * any sender straight to such a read. `ForeignContentUri` is the rule that
 * closes it — another app's `content://` only — and this inventory makes every
 * read either apply the rule or say why its URI cannot come from another app.
 *
 * **What it checks.** Each production file calling `openInputStream`,
 * `openFileDescriptor` or an asset-descriptor variant is in [INVENTORY]. A
 * [Source.AnyApp] file must call `ForeignContentUri.isAcceptable`. A
 * [Source.SystemPicker] file is one whose URI is the result of a system document
 * or media picker the user drove — no other app can hand it over. Whether the
 * rule is applied *before* the read is not checked here; `AttachmentStoreImplTest`
 * and `AudioCaptureStoreImplTest` pin that on the two sinks.
 */
class ContentUriReadInventoryTest {

    /** Where the URIs a file opens come from. */
    private sealed interface Source {
        /** Another app can supply the URI; the file must apply `ForeignContentUri`. */
        data object AnyApp : Source

        /**
         * Only a system picker result reaches the read.
         *
         * @property picker Which picker, in words a reviewer can check against the file.
         */
        data class SystemPicker(val picker: String) : Source
    }

    @Test
    fun `every production file that opens a content uri is in the inventory`() {
        val unlisted = filesOpeningAUri() - INVENTORY.keys

        assertTrue(
            "these files open a URI through ContentResolver but are not in the inventory: ${unlisted.sorted()}. " +
                "Add each to ContentUriReadInventoryTest.INVENTORY: AnyApp (and apply ForeignContentUri before " +
                "the read) if another app can choose the URI, SystemPicker if only a picker result reaches it.",
            unlisted.isEmpty(),
        )
    }

    @Test
    fun `every read another app can reach applies the foreign-content rule`() {
        val unguarded = INVENTORY.filterValues { it == Source.AnyApp }.keys
            .filterNot { path -> codeOf(path).contains(RULE) }

        assertEquals(
            "these files open a URI another app can supply without asking ForeignContentUri",
            emptyList<String>(),
            unguarded,
        )
    }

    @Test
    fun `the inventory lists no file that no longer opens a uri`() {
        assertEquals(emptySet<String>(), INVENTORY.keys - filesOpeningAUri())
    }

    /** Production files (paths under `app/src`) that call a resolver open method. */
    private fun filesOpeningAUri(): Set<String> =
        ProductionSources.code.filterValues { OPEN_CALL.containsMatchIn(it) }.keys

    private fun codeOf(path: String): String = ProductionSources.code.getValue(path)

    private companion object {
        const val RULE = "ForeignContentUri.isAcceptable("
        const val BASE = "main/java/app/knotwork/android/"

        /** The `ContentResolver` methods that open what a URI names. */
        val OPEN_CALL =
            Regex(
                """\.(openInputStream|openFileDescriptor|openAssetFileDescriptor|openTypedAssetFileDescriptor)\s*\(""",
            )

        val INVENTORY: Map<String, Source> = mapOf(
            // Photo picker results and images shared into the app — EXTRA_STREAM from any sender.
            "${BASE}data/local/AttachmentStoreImpl.kt" to Source.AnyApp,
            // An audio document picker result today; guarded anyway, as a sink.
            "${BASE}data/local/AudioCaptureStoreImpl.kt" to Source.AnyApp,
            "${BASE}presentation/ui/chat/home/ChatHomeScreen.kt" to
                Source.SystemPicker("OpenDocument — the chat-history import file"),
            "${BASE}presentation/ui/files/FilesScreen.kt" to
                Source.SystemPicker("OpenDocument — a document imported into the workspace"),
            "${BASE}presentation/ui/orchestrator/PipelineLibraryScreen.kt" to
                Source.SystemPicker("OpenDocument — a pipeline file to import"),
            "${BASE}presentation/ui/prompts/PromptLibraryScreen.kt" to
                Source.SystemPicker("OpenDocument — a prompt pack to import"),
            "${BASE}presentation/ui/settings/SettingsScreens.kt" to
                Source.SystemPicker("OpenDocument — a memory export to import"),
        )
    }
}
