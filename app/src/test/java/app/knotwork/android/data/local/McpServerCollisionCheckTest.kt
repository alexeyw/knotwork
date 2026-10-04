package app.knotwork.android.data.local

import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.UpdateMcpServerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin unit coverage for [McpServerCollisionCheck.detectCollision].
 *
 * Each case mirrors a path in the production helper so a regression at the
 * persistence layer (`ToolSettingsStore.updateMcpServer`) surfaces here
 * before any DataStore plumbing is involved.
 */
class McpServerCollisionCheckTest {

    @Test
    fun `returns null when the new url matches the original url (no-op edit)`() {
        val list = listOf(
            McpServerConfig(url = "http://a"),
            McpServerConfig(url = "http://b"),
        )

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://a",
            newUrl = "http://a",
        )

        assertNull(result)
    }

    @Test
    fun `returns null when the new url is unique among other rows`() {
        val list = listOf(
            McpServerConfig(url = "http://a"),
            McpServerConfig(url = "http://b"),
        )

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://a",
            newUrl = "http://c",
        )

        assertNull(result)
    }

    @Test
    fun `returns UrlCollision when the new url matches another row's url`() {
        val list = listOf(
            McpServerConfig(url = "http://a", name = "A"),
            McpServerConfig(url = "http://b", name = "B"),
        )

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://a",
            newUrl = "http://b",
        )

        assertEquals(
            UpdateMcpServerResult.UrlCollision(
                collidingUrl = "http://b",
                collidingDisplayName = "B",
            ),
            result,
        )
    }

    @Test
    fun `returns UrlCollision with null display name when colliding row has no name set`() {
        val list = listOf(
            McpServerConfig(url = "http://a"),
            McpServerConfig(url = "http://b"),
        )

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://a",
            newUrl = "http://b",
        )

        assertEquals(
            UpdateMcpServerResult.UrlCollision(
                collidingUrl = "http://b",
                collidingDisplayName = null,
            ),
            result,
        )
    }

    @Test
    fun `treats blank display name as no name for the collision message`() {
        val list = listOf(McpServerConfig(url = "http://a"), McpServerConfig(url = "http://b", name = "   "))

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://a",
            newUrl = "http://b",
        )

        assertEquals(
            UpdateMcpServerResult.UrlCollision(
                collidingUrl = "http://b",
                collidingDisplayName = null,
            ),
            result,
        )
    }

    @Test
    fun `returns null when the edited row is not present in the current list (add path)`() {
        // Defensive: if the caller passes an originalUrl that isn't on disk
        // (race after a remove), the function must not synthesise a fake
        // collision against the brand-new url itself.
        val list = listOf(McpServerConfig(url = "http://a"))

        val result = McpServerCollisionCheck.detectCollision(
            currentList = list,
            originalUrl = "http://stale",
            newUrl = "http://b",
        )

        assertNull(result)
    }
}
