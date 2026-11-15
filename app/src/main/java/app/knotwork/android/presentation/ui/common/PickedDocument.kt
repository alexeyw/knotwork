package app.knotwork.android.presentation.ui.common

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * A document the user picked in the system's create-document picker to save something
 * into. The picker has already created it — empty — by the time the app hears back, so
 * every answer to a pick ends one of two ways: the content written into it, or the
 * document removed again ([discardUnwritten]).
 *
 * The second way matters because what the app meant to write can be gone when the picker
 * returns: Android may end the app's process while the picker is open, and content kept
 * only in a screen's composition does not survive a rotation. An empty file left where the
 * user chose to save looks like a saved export and holds nothing. So each save-to-file
 * point holds its content in a ViewModel across the picker and answers the pick with one
 * of these; `PickedDocumentCensusTest` keeps every `CreateDocument` launcher on this path
 * or names why it is not.
 *
 * Every call blocks on the document's provider: make it off the main thread.
 */
interface PickedDocument {

    /**
     * Opens the document for writing.
     *
     * @return The stream, owned by the caller; `null` when the provider refuses the
     *   document or has lost it.
     */
    fun openOutput(): OutputStream?

    /**
     * Deletes the document.
     *
     * @return `true` when it is gone (a document already gone counts), `false` when the
     *   provider refused or cannot delete documents.
     */
    fun delete(): Boolean

    /**
     * The document's name as the user's file manager shows it.
     *
     * @return The provider's display name, else the last segment of the document's
     *   address — never empty for a document the picker returned.
     */
    fun displayName(): String
}

/**
 * What became of a picked document nothing was written into.
 */
sealed interface UnwrittenDocument {

    /** The document was deleted: nothing is left where the user chose to save. */
    data object Removed : UnwrittenDocument

    /**
     * The provider would not delete the document: an empty file is left, and the user
     * has to be told its name to remove it.
     *
     * @property fileName The left document's name ([PickedDocument.displayName]).
     */
    data class Left(val fileName: String) : UnwrittenDocument
}

/**
 * Removes a picked document that will not be written — the content was gone when the
 * picker returned, could not be rendered, or the document would not open.
 *
 * @param ioDispatcher Dispatcher carrying the provider calls; injected so a test can
 *   drive it on the test scheduler.
 * @return [UnwrittenDocument.Removed], or [UnwrittenDocument.Left] with the name the
 *   user needs to find the empty file.
 */
suspend fun PickedDocument.discardUnwritten(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): UnwrittenDocument =
    withContext(ioDispatcher) {
        if (delete()) UnwrittenDocument.Removed else UnwrittenDocument.Left(displayName())
    }

/**
 * A [PickedDocument] behind the `content:` address the create-document picker returned.
 *
 * @property resolver Resolver of the picked document's provider.
 * @property uri The address the picker returned.
 */
class ContentResolverPickedDocument(private val resolver: ContentResolver, private val uri: Uri) : PickedDocument {

    @SuppressLint("Recycle") // Returned to the caller, who closes it.
    override fun openOutput(): OutputStream? = try {
        resolver.openOutputStream(uri)
    } catch (e: IOException) {
        // `openOutputStream` declares FileNotFoundException: a provider that created
        // the document and then lost it.
        Timber.w(e, "A picked document could not be opened")
        null
    } catch (e: SecurityException) {
        // A provider that offered the document and then refused to open it.
        Timber.w(e, "A picked document's provider refused to open it")
        null
    }

    override fun delete(): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: FileNotFoundException) {
        Timber.i(e, "A picked document to delete was already gone")
        true
    } catch (e: SecurityException) {
        Timber.i(e, "A picked document's provider refused to delete it")
        false
    } catch (e: UnsupportedOperationException) {
        Timber.i(e, "A picked document's provider cannot delete documents")
        false
    } catch (e: IllegalArgumentException) {
        Timber.i(e, "A picked document's provider refused to delete it")
        false
    } catch (e: RuntimeException) {
        // A provider that died mid-call surfaces as a bare runtime exception.
        Timber.w(e, "A picked document's provider failed while deleting it")
        false
    }

    override fun displayName(): String = queryDisplayName() ?: uri.lastPathSegment.orEmpty()

    /** The provider's display name for the document, or `null` when it does not say. */
    private fun queryDisplayName(): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: RuntimeException) {
        Timber.i(e, "A picked document's name could not be read")
        null
    }
}
