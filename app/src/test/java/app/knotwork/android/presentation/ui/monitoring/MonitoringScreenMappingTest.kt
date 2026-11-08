package app.knotwork.android.presentation.ui.monitoring

import app.knotwork.android.domain.models.AgentMetrics
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Role
import app.knotwork.design.screens.monitoring.MonitoringStrings
import app.knotwork.design.screens.monitoring.NodeBreakdownRow
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
        MonitoringUiState(recentLogs = logs.toList()).toViewState(MonitoringStrings(), ENGLISH_LABELS)

    @Test
    fun `given time spent per node type when mapped then each row names the type, slowest first`() {
        val metrics = AgentMetrics(
            totalTokensProcessed = 12,
            timePerNodeType = mapOf(NodeType.LITE_RT to 640L, NodeType.INTENT_ROUTER to 900L),
        )

        val state = MonitoringUiState(metrics = metrics).toViewState(MonitoringStrings(), ENGLISH_LABELS)

        assertEquals(
            listOf(NodeBreakdownRow("Intent Router", "900 ms"), NodeBreakdownRow("LiteRT", "640 ms")),
            state.perNodeBreakdown,
        )
    }

    @Test
    fun `given a node type without a name when mapped then its row shows the code`() {
        val metrics = AgentMetrics(totalTokensProcessed = 12, timePerNodeType = mapOf(NodeType.TOOL to 5L))

        val state = MonitoringUiState(metrics = metrics).toViewState(MonitoringStrings(), ENGLISH_LABELS)

        assertEquals(listOf("TOOL"), state.perNodeBreakdown.map { it.nodeType })
    }

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

private val ENGLISH_LABELS = MonitoringStatLabels(
    inferenceTime = "INFERENCE TIME",
    tokensPerSecond = "TOKENS/S",
    totalTokens = "TOTAL TOKENS",
    milliseconds = "%1\$d ms",
    locale = java.util.Locale.US,
    nodeTypeNames = mapOf(NodeType.LITE_RT to "LiteRT", NodeType.INTENT_ROUTER to "Intent Router"),
)
