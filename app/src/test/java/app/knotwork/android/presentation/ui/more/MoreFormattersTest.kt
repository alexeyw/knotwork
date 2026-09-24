package app.knotwork.android.presentation.ui.more

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin formatter helpers feeding the More tab
 * subtitle counters and footer pill.
 */
class MoreFormattersTest {

    @Test
    fun `formatMemoryStats renders chunk count with thin spaces above 999`() {
        val result = formatMemoryStats(chunkCount = 1248, totalBytes = 14_900_000L)
        assertEquals("1 248 chunks · 14.2 MB", result)
    }

    @Test
    fun `formatMemoryStats renders small chunk count without space`() {
        val result = formatMemoryStats(chunkCount = 42, totalBytes = 500_000L)
        assertEquals("42 chunks · 488 KB", result)
    }

    @Test
    fun `formatMemoryStats handles zero stats`() {
        val result = formatMemoryStats(chunkCount = 0, totalBytes = 0L)
        assertEquals("0 chunks · 0 MB", result)
    }

    @Test
    fun `formatPromptsStats joins counts with separator`() {
        assertEquals("5 categories · 24 prompts", formatPromptsStats(5, 24))
    }

    @Test
    fun `formatNetworkStatus null timestamp renders no-calls-yet`() {
        val result = formatNetworkStatus(now = 1_000_000L, lastOutboundAt = null)
        assertEquals("on-device · no network calls yet", result)
    }

    @Test
    fun `formatNetworkStatus stale timestamp renders minute count`() {
        val now = 1_000_000_000L
        val lastAt = now - 14 * 60_000L
        val result = formatNetworkStatus(now = now, lastOutboundAt = lastAt)
        assertEquals("on-device · no network calls in last 14 m", result)
    }

    @Test
    fun `formatNetworkStatus fresh timestamp renders a call just now`() {
        val now = 1_000_000_000L
        val lastAt = now - 30_000L
        val result = formatNetworkStatus(now = now, lastOutboundAt = lastAt)
        assertEquals("online · network call just now", result)
    }

    @Test
    fun `formatLibraryStats handles zero one and many`() {
        assertEquals("no saved presets", formatLibraryStats(0))
        assertEquals("1 saved preset", formatLibraryStats(1))
        assertEquals("7 saved presets", formatLibraryStats(7))
    }
}
