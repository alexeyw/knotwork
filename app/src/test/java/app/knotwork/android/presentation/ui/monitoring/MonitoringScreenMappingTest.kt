package app.knotwork.android.presentation.ui.monitoring

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.Role
import app.knotwork.design.screens.monitoring.MonitoringStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the pure `MonitoringUiState.toViewState()` projection used by `MonitoringScreen`. */
class MonitoringScreenMappingTest {

    private fun systemMessage(id: Long?, content: String, timestamp: Long) = ChatMessage(
        id = id,
        sessionId = "session",
        role = Role.SYSTEM,
        content = content,
        timestamp = timestamp,
    )

    private fun map(vararg logs: ChatMessage) =
        MonitoringUiState(recentLogs = logs.toList()).toViewState(MonitoringStrings())

    @Test
    fun `given the same message twice in one second when mapped then the lines keep distinct ids`() {
        val text = "User denied execution of tool: delete_file"

        val state = map(
            systemMessage(id = 8, content = text, timestamp = 1_000_400),
            systemMessage(id = 7, content = text, timestamp = 1_000_100),
        )

        // Same second, same text: everything the list used to key on is equal.
        assertEquals(state.logs[0].timestamp, state.logs[1].timestamp)
        assertEquals(listOf("row:8", "row:7"), state.logs.map { it.id })
    }

    @Test
    fun `given a message without a database id when mapped then its id comes from its position`() {
        val state = map(
            systemMessage(id = 1, content = "stored", timestamp = 0),
            systemMessage(id = null, content = "never saved", timestamp = 0),
        )

        // Position 1 and row 1 must not meet as the same key.
        assertEquals(listOf("row:1", "pos:1"), state.logs.map { it.id })
    }
}
