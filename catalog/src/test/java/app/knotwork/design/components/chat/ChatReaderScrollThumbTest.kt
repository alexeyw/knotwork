package app.knotwork.design.components.chat

import app.knotwork.design.components.ScrollThumbTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the reader's scroll thumb maps onto its items, and when it shows. */
class ChatReaderScrollThumbTest {

    @Test
    fun `given exactly five screens of items when asked then no thumb, and a little more shows it`() {
        assertFalse(chatReaderThumbShown(itemCount = 100, averageItemPx = 10f, viewportPx = 200))
        assertTrue(chatReaderThumbShown(itemCount = 101, averageItemPx = 10f, viewportPx = 200))
    }

    @Test
    fun `given a scroll position when mapped then the share is the first item among those that can top the list`() {
        // 110 items, 10 on screen: items 0 to 100 can be at the top.
        val map = ChatReaderScrollMap(itemCount = 110) { 10 }

        assertEquals(0f, map.fraction(firstIndex = 0, firstOffsetPx = 0), 0f)
        assertEquals(0.5f, map.fraction(firstIndex = 50, firstOffsetPx = 30), 0f)
        assertEquals(1f, map.fraction(firstIndex = 105, firstOffsetPx = 0), 0f)
    }

    @Test
    fun `given a thumb share when mapped then the list scrolls to the top of the item at that share`() {
        val map = ChatReaderScrollMap(itemCount = 110) { 10 }

        assertEquals(ScrollThumbTarget(index = 0, offsetPx = 0), map.scrollTarget(0f))
        assertEquals(ScrollThumbTarget(index = 42, offsetPx = 0), map.scrollTarget(0.42f))
        assertEquals(ScrollThumbTarget(index = 100, offsetPx = 0), map.scrollTarget(3f))
    }

    @Test
    fun `given the items on screen change when mapped again then the same map follows them`() {
        var onScreen = 10
        val map = ChatReaderScrollMap(itemCount = 110) { onScreen }
        assertEquals(ScrollThumbTarget(index = 100, offsetPx = 0), map.scrollTarget(1f))

        onScreen = 20

        assertEquals(ScrollThumbTarget(index = 90, offsetPx = 0), map.scrollTarget(1f))
    }

    @Test
    fun `given everything on one screen when mapped then the thumb stays at the top`() {
        val map = ChatReaderScrollMap(itemCount = 5) { 10 }

        assertEquals(0f, map.fraction(firstIndex = 3, firstOffsetPx = 0), 0f)
        assertEquals(ScrollThumbTarget(index = 0, offsetPx = 0), map.scrollTarget(1f))
    }
}
