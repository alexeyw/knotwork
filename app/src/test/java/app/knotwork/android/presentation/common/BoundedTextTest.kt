package app.knotwork.android.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [readTextWithin] — an import reads the picked file only up to its ceiling,
 * and stops reading there rather than after the whole file is in memory — plus
 * the heap-derived ceiling for memory files.
 */
class BoundedTextTest {

    @Test
    fun `given a file within the limit when read then the whole text is returned`() {
        val text = "{\"name\":\"Ünïcode ✓\"}"

        val read = ByteArrayInputStream(text.toByteArray()).readTextWithin(maxBytes = 1_000)

        assertEquals(BoundedText.Read(text), read)
    }

    @Test
    fun `given a file of exactly the limit when read then it is accepted`() {
        val bytes = ByteArray(100) { 'a'.code.toByte() }

        val read = ByteArrayInputStream(bytes).readTextWithin(maxBytes = 100)

        assertEquals(BoundedText.Read("a".repeat(100)), read)
    }

    @Test
    fun `given a file one byte over the limit when read then it is refused`() {
        val read = ByteArrayInputStream(ByteArray(101)).readTextWithin(maxBytes = 100)

        assertEquals(BoundedText.TooLarge(100), read)
    }

    @Test
    fun `given an endless stream when read then reading stops at the limit`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'x'.code
            }
        }

        val read = endless.readTextWithin(maxBytes = 1_000_000)

        assertEquals(BoundedText.TooLarge(1_000_000), read)
        assertTrue("read far past the limit: $served bytes", served <= 1_000_000 + 64 * 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given a non-positive limit when read then it is refused`() {
        ByteArrayInputStream(ByteArray(1)).readTextWithin(maxBytes = 0)
    }

    @Test
    fun `given a heap ceiling when the memory limit is derived then it is a quarter of it`() {
        assertEquals(128L * 1024 * 1024, memoryImportLimitBytes(maxHeapBytes = 512L * 1024 * 1024))
    }
}
