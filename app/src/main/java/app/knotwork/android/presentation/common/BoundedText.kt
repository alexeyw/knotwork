package app.knotwork.android.presentation.common

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What reading a picked file within a size limit produced.
 */
sealed interface BoundedText {

    /**
     * The whole file, decoded as UTF-8.
     *
     * @property text The file's content.
     */
    data class Read(val text: String) : BoundedText

    /**
     * The file is larger than the limit; reading stopped at the limit and
     * nothing was decoded.
     *
     * @property limitBytes The limit the file exceeded, for the message.
     */
    data class TooLarge(val limitBytes: Long) : BoundedText
}

/**
 * Reads this stream as UTF-8 text, giving up as soon as more than [maxBytes]
 * bytes have arrived.
 *
 * An import reads the file the user picked into one string before parsing it,
 * so the file's size is the file's choice. Reading it unbounded let a large
 * enough file end in an `OutOfMemoryError` — in the parser, where nothing
 * catches it, the app crashes. Stopping at the limit turns that into a
 * message, and never holds more than [maxBytes] plus one buffer.
 *
 * The stream is not closed; the caller owns it.
 *
 * @param maxBytes Largest file accepted, in bytes. Must be positive.
 * @return [BoundedText.Read] with the text, or [BoundedText.TooLarge].
 */
fun InputStream.readTextWithin(maxBytes: Long): BoundedText {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) return BoundedText.TooLarge(maxBytes)
        out.write(buffer, 0, count)
    }
    return BoundedText.Read(out.toString(Charsets.UTF_8.name()))
}

/**
 * The largest memory file this device can import at once, from its heap
 * ceiling.
 *
 * A memory export cannot have a fixed ceiling that means anything: the app's
 * own export of the default 5 000 chunks at 512 dimensions is about 50 MB, and
 * of the 20 000 the store can be set to keep, about 200 MB (computed from the
 * export format, not measured). What bounds an import is the heap: the text is
 * held as a string at two bytes per character while the parser builds a tree
 * of about the same size again. A quarter of the heap leaves room for both and
 * for the app around them. The divisor is an estimate, not a measurement.
 *
 * @param maxHeapBytes The process heap ceiling (`Runtime.maxMemory()`).
 * @return The limit, in bytes.
 */
fun memoryImportLimitBytes(maxHeapBytes: Long): Long = maxHeapBytes / HEAP_SHARE_DIVISOR

/** How much of the file a single read pulls in. */
private const val BUFFER_BYTES = 64 * 1024

/** The memory import may take at most one heap-quarter of file bytes. */
private const val HEAP_SHARE_DIVISOR = 4L
