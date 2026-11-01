package app.knotwork.design.screens.monitoring

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Behaviour of [MonitoringContent]'s recent-logs list. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MonitoringContentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `given the same message twice in one second when the logs render then both lines show`() {
        // The list keys its lines; a key built from the time and the text repeats here,
        // and a repeated key throws and takes the screen down.
        val text = "Destructive tools are blocked by Settings — delete_file was not executed."
        rule.setContent {
            KnotworkTheme {
                MonitoringContent(
                    state = MonitoringViewState(
                        visualState = MonitoringVisualState.Default,
                        logs = listOf(
                            MonitoringLogLine(id = "row:2", timestamp = "12:04:01", message = text),
                            MonitoringLogLine(id = "row:1", timestamp = "12:04:01", message = text),
                        ),
                    ),
                )
            }
        }

        rule.onAllNodesWithText(text).assertCountEquals(2)
    }
}
