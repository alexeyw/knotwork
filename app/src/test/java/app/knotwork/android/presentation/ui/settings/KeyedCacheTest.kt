package app.knotwork.android.presentation.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** [KeyedCache] builds once per key and again when the key changes. */
class KeyedCacheTest {

    @Test
    fun `given the same key when read twice then the value is built once`() {
        var builds = 0
        val cache = KeyedCache(key = { "en" }, build = { ++builds })

        cache.get()
        cache.get()

        assertEquals(1, builds)
    }

    @Test
    fun `given a new key when read then the value is built again for it`() {
        var language = "en"
        val cache = KeyedCache(key = { language }, build = { "index in $language" })
        assertEquals("index in en", cache.get())

        language = "ru"

        assertEquals("index in ru", cache.get())
    }

    @Test
    fun `given the key changes back when read then the value is rebuilt, not resurrected`() {
        var language = "en"
        var builds = 0
        val cache = KeyedCache(key = { language }, build = { ++builds })
        cache.get()
        language = "ru"
        cache.get()

        language = "en"

        assertEquals(3, cache.get())
    }
}
