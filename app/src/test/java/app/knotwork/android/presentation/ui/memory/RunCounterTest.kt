package app.knotwork.android.presentation.ui.memory

import android.content.res.Resources
import app.knotwork.android.presentation.ui.models.SampleCaption
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The plural counters that screen texts carry compare equal when built twice
 * over the same resources — the Memory screen memoises its O(N) projection on
 * its texts, and a fresh lambda on every recomposition rebuilt it on every
 * keystroke of a search.
 */
@RunWith(RobolectricTestRunner::class)
class RunCounterTest {

    private val resources: Resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `given the same resources when a counter is built twice then the two are equal`() {
        assertEquals(RunCounter(resources), RunCounter(resources))
        assertEquals(SampleCaption(resources), SampleCaption(resources))
    }

    @Test
    fun `given English when counted then the plural form follows the count`() {
        assertEquals("1 run", RunCounter(resources)(1))
        assertEquals("6 runs", RunCounter(resources)(6))
        assertEquals("avg · last 8 runs", SampleCaption(resources)(8))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given Russian when counted then the few and many forms are used`() {
        assertEquals("2 прогона", RunCounter(resources)(2))
        assertEquals("5 прогонов", RunCounter(resources)(5))
        assertEquals("21 прогон", RunCounter(resources)(21))
    }
}
