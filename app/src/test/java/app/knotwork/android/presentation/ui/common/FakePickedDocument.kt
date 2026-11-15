package app.knotwork.android.presentation.ui.common

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A [PickedDocument] for tests of the save-to-file points: it records what was written
 * into it, whether its stream was closed, and whether it was asked to delete itself.
 *
 * @property name The name the document reports ([PickedDocument.displayName]).
 * @property opens Whether [openOutput] hands out a stream; `false` plays a provider that
 *   refuses the document.
 * @property deletes Whether [delete] succeeds; `false` plays a provider that cannot delete.
 * @param failOnWrite Whether a write to the stream throws, as a full disk does.
 * @param failOnClose Whether closing the stream throws, as a provider that went away does.
 */
class FakePickedDocument(
    private val name: String = "export.json",
    private val opens: Boolean = true,
    private val deletes: Boolean = true,
    failOnWrite: Boolean = false,
    failOnClose: Boolean = false,
) : PickedDocument {

    /** The document's stream: what was written, and whether it was closed. */
    val stream = RecordingStream(failOnWrite = failOnWrite, failOnClose = failOnClose)

    /** How many times [openOutput] was called. */
    var openCount = 0
        private set

    /** Whether [delete] was called, whatever it answered. */
    var deleteAsked = false
        private set

    /** The bytes written into the document. */
    val bytes: ByteArray get() = stream.bytes.toByteArray()

    override fun openOutput(): OutputStream? {
        openCount++
        return if (opens) stream else null
    }

    override fun delete(): Boolean {
        deleteAsked = true
        return deletes
    }

    override fun displayName(): String = name
}

/**
 * A stream that records its bytes and whether it was closed, and can be made to fail.
 *
 * @param failOnWrite Whether a write throws.
 * @param failOnClose Whether closing throws (after it has been recorded as closed).
 */
class RecordingStream(private val failOnWrite: Boolean = false, private val failOnClose: Boolean = false) :
    OutputStream() {

    /** Everything written so far. */
    val bytes = ByteArrayOutputStream()

    /** Whether [close] was called. */
    var closed = false
        private set

    override fun write(b: Int) {
        if (failOnWrite) throw IOException("no space left on device")
        bytes.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (failOnWrite) throw IOException("no space left on device")
        bytes.write(b, off, len)
    }

    override fun close() {
        closed = true
        if (failOnClose) throw IOException("provider went away")
    }
}
