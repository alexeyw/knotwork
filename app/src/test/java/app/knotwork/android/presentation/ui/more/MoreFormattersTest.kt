package app.knotwork.android.presentation.ui.more

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The More tab's counters and their words: the ViewModel hands over numbers,
 * [MoreSubtitles] writes them in the interface language — English as it read
 * before the counters moved out of the ViewModel, Russian with its own plural
 * forms.
 */
@RunWith(RobolectricTestRunner::class)
class MoreFormattersTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun subtitles(state: MoreUiState) = MoreSubtitles.of(state, context)

    @Test
    fun `given more than 999 chunks when worded then the count is grouped with spaces`() {
        val line = subtitles(MoreUiState(memory = MoreMemoryCount(chunks = 1248, bytes = 14_900_000L))).memory
        assertEquals("1 248 chunks · 14.2 MB", line)
    }

    @Test
    fun `given a few chunks under a megabyte when worded then the size is in kilobytes`() {
        assertEquals("42 chunks · 488 KB", subtitles(MoreUiState(memory = MoreMemoryCount(42, 500_000L))).memory)
    }

    @Test
    fun `given no memory when worded then it reads zero megabytes`() {
        assertEquals("0 chunks · 0 MB", subtitles(MoreUiState(memory = MoreMemoryCount(0, 0L))).memory)
    }

    @Test
    fun `given counters not yet known when worded then each row reads a dash`() {
        val empty = subtitles(MoreUiState())
        assertEquals("—", empty.memory)
        assertEquals("—", empty.models)
        assertEquals("—", empty.files)
        assertEquals("", empty.network)
    }

    @Test
    fun `given prompt counts when worded then both are joined with a separator`() {
        assertEquals("5 categories · 24 prompts", subtitles(MoreUiState(prompts = MorePromptCount(5, 24))).prompts)
    }

    @Test
    fun `given no outbound call when computed then the footer says none yet`() {
        val stat = networkStat(now = 1_000_000L, lastOutboundAt = null)
        assertEquals(MoreNetworkStat.NoCallsYet, stat)
        assertEquals("on-device · no network calls yet", subtitles(MoreUiState(network = stat)).network)
    }

    @Test
    fun `given a call 14 minutes ago when computed then the footer counts the minutes`() {
        val now = 1_000_000_000L
        val stat = networkStat(now = now, lastOutboundAt = now - 14 * 60_000L)
        val words = subtitles(MoreUiState(network = stat))
        assertEquals("on-device · no network calls in last 14 m", words.network)
        assertTrue(words.networkOk)
    }

    @Test
    fun `given a call 30 seconds ago when computed then the footer reads online`() {
        val now = 1_000_000_000L
        val words = subtitles(MoreUiState(network = networkStat(now = now, lastOutboundAt = now - 30_000L)))
        assertEquals("online · network call just now", words.network)
        assertFalse(words.networkOk)
    }

    @Test
    fun `given zero one and many saved presets when worded then each reads in its form`() {
        assertEquals("no saved presets", subtitles(MoreUiState(savedPresets = 0)).library)
        assertEquals("1 saved preset", subtitles(MoreUiState(savedPresets = 1)).library)
        assertEquals("7 saved presets", subtitles(MoreUiState(savedPresets = 7)).library)
    }

    @Test
    fun `given workspace files when worded then the count and size are shown`() {
        assertEquals("empty", subtitles(MoreUiState(files = MoreWorkspaceStat.Empty)).files)
        assertEquals("—", subtitles(MoreUiState(files = MoreWorkspaceStat.Unavailable)).files)
        assertEquals("3 files · 2 KB", subtitles(MoreUiState(files = MoreWorkspaceStat.Files(3, 2_048L))).files)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when worded then plurals and units follow Russian`() {
        val words = subtitles(
            MoreUiState(
                memory = MoreMemoryCount(chunks = 22, bytes = 14_900_000L),
                savedPresets = 5,
                files = MoreWorkspaceStat.Files(21, 2_048L),
            ),
        )
        assertEquals("22 фрагмента · 14,2 МБ", words.memory)
        assertEquals("5 сохранённых пресетов", words.library)
        assertEquals("21 файл · 2 КБ", words.files)
    }
}
