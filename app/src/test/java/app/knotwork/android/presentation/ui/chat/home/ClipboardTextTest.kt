package app.knotwork.android.presentation.ui.chat.home

import android.os.TransactionTooLargeException
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What [setClipboardText] reports for each way the clipboard write ends.
 *
 * The platform's `ClipboardManager.setPrimaryClip` rethrows a failed Binder
 * transaction as a `RuntimeException` around the `TransactionTooLargeException`
 * (read in the platform sources); the fake below throws exactly that shape. A
 * real oversized clip was not sent through a device here.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardTextTest {

    @Test
    fun `given a clip that fits when copying then it is on the clipboard and the copy reports success`() {
        val clipboard = FakeClipboard()

        val copied = clipboard.setClipboardText("value")

        assertTrue(copied)
        assertEquals("value", clipboard.copied?.text)
    }

    @Test
    fun `given a clip too large for the transaction when copying then the copy reports failure instead of throwing`() {
        val clipboard = FakeClipboard(failure = RuntimeException(TransactionTooLargeException("data parcel size")))

        val copied = clipboard.setClipboardText("x".repeat(1_048_576))

        assertFalse(copied)
        assertNull(clipboard.copied)
    }

    @Test
    fun `given any other clipboard failure when copying then it is rethrown`() {
        val failure = RuntimeException(IllegalStateException("something else"))
        val clipboard = FakeClipboard(failure = failure)

        val thrown = runCatching { clipboard.setClipboardText("value") }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    /** A clipboard that keeps what it is given, or throws [failure] instead. */
    private class FakeClipboard(private val failure: RuntimeException? = null) : ClipboardManager {
        var copied: AnnotatedString? = null
            private set

        override fun setText(annotatedString: AnnotatedString) {
            failure?.let { throw it }
            copied = annotatedString
        }

        override fun getText(): AnnotatedString? = copied

        override fun hasText(): Boolean = copied != null
    }
}
