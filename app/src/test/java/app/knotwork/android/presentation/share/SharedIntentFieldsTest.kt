package app.knotwork.android.presentation.share

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SharedIntentFields.read] — the only place [ShareReceiverActivity]
 * touches a caller's extras.
 *
 * The share target is exported without a permission, so any installed app can
 * start it with extras of its choosing. Unmarshalling a hostile or malformed
 * parcel throws, and a throw in `onCreate` takes the whole process down — every
 * run in flight with it — before a single gate has looked at the share.
 */
@RunWith(RobolectricTestRunner::class)
class SharedIntentFieldsTest {

    @Test
    fun `given a share intent when read then type text and stream come back`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_TEXT, "caption")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://sender.example/1"))

        val fields = SharedIntentFields.read(intent)

        assertEquals(SharedIntentFields("image/png", "caption", "content://sender.example/1"), fields)
    }

    @Test
    fun `given extras that cannot be unmarshalled when read then nothing is read and nothing throws`() {
        val hostile = mockk<Intent>()
        every { hostile.type } returns "text/plain"
        every { hostile.getStringExtra(any()) } throws RuntimeException("bad parcel")
        every { hostile.getParcelableExtra(any(), Uri::class.java) } throws RuntimeException("bad parcel")
        every { hostile.getParcelableExtra<Uri>(any()) } throws RuntimeException("bad parcel")

        assertNull(SharedIntentFields.read(hostile))
    }

    @Test
    fun `given a stream extra that cannot be unmarshalled when read then nothing is read and nothing throws`() {
        val hostile = mockk<Intent>()
        every { hostile.type } returns "image/png"
        every { hostile.getStringExtra(any()) } returns null
        every { hostile.getParcelableExtra(any(), Uri::class.java) } throws RuntimeException("bad parcel")
        every { hostile.getParcelableExtra<Uri>(any()) } throws RuntimeException("bad parcel")

        assertNull(SharedIntentFields.read(hostile))
    }
}
