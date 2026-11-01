package app.knotwork.design.components.console

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines of the console's value viewer: a JSON value laid out and coloured,
 * a text value with its own lines, a value that starts like JSON but does not parse, one
 * long line without spaces cut into pieces, the formatting notice, and JSON at 200 %.
 * Reduced motion, so the opening is a short fade that the clock finishes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleValueViewerSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val weatherJson = """{"latitude":52.37,"longitude":4.89,"timezone":"Europe\/Amsterdam",""" +
        """"current":{"time":"2026-11-01T09:00","temperature_2m":11.4,"is_day":true,"rain":null},""" +
        """"hourly":{"time":["2026-11-01T00:00","2026-11-01T01:00"],"temperature_2m":[9.8,9.5]},"alerts":[]}"""

    @Test
    fun console_value_viewer_json() = snapshot("console_value_viewer_json", jsonState())

    @Test
    @Config(fontScale = 2f)
    fun console_value_viewer_json_fs20() = snapshot("console_value_viewer_json_fs20", jsonState())

    @Test
    fun console_value_viewer_text() {
        val prompt = "You are summarising today's calendar for one person.\n\nRules:\n" +
            "- One sentence per event, in time order.\n\t- Give the start time first.\n" +
            "- Leave out events that were declined."
        snapshot(
            "console_value_viewer_text",
            ConsoleValueViewerState(
                row = ConsoleVarRow(node = "LITE_RT#501fab", key = "input", value = prompt, depth = 1),
                body = ConsoleValueBody.Ready(ConsoleValueFormat.Text, textLines(prompt)),
            ),
        )
    }

    @Test
    fun console_value_viewer_not_valid_json() {
        val cut = "{\"items\":[\n  {\"id\":1,\"title\":\"Design review\"},\n  {\"id\":2,\"title\":\"Vendor ca"
        snapshot(
            "console_value_viewer_not_valid_json",
            ConsoleValueViewerState(
                row = ConsoleVarRow(node = "TOOL#9c41d2", key = "body", value = cut),
                body = ConsoleValueBody.Ready(ConsoleValueFormat.NotValidJson, textLines(cut)),
            ),
        )
    }

    @Test
    fun console_value_viewer_no_spaces() {
        val blob = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAABHNCSVQICAgIfAhkiAAA".repeat(120)
        snapshot(
            "console_value_viewer_no_spaces",
            ConsoleValueViewerState(
                row = ConsoleVarRow(node = "TOOL#2e7c40", key = "attachment", value = blob),
                body = ConsoleValueBody.Ready(ConsoleValueFormat.Text, listOf(ConsoleValueLine(0, blob))),
            ),
        )
    }

    @Test
    fun console_value_viewer_formatting() {
        rule.mainClock.autoAdvance = false
        render(
            ConsoleValueViewerState(
                row = ConsoleVarRow(node = "TOOL#9c41d2", key = "body", value = weatherJson),
                body = ConsoleValueBody.Formatting,
            ),
        )
        rule.mainClock.advanceTimeBy(500)
        rule.onNode(isDialog()).captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/console_value_viewer_formatting.png",
        )
    }

    private fun jsonState() = ConsoleValueViewerState(
        row = ConsoleVarRow(node = "TOOL#9c41d2", key = "body", value = weatherJson),
        body = ConsoleValueBody.Ready(ConsoleValueFormat.Json, weatherLines()),
    )

    /** [weatherJson] as the app's formatter lays it out, spelled out here: the catalog formats nothing. */
    private fun weatherLines(): List<ConsoleValueLine> {
        fun key(
            indent: Int,
            key: String,
            rest: String,
            restToken: ConsoleValueToken?,
            comma: Boolean = true,
        ): ConsoleValueLine {
            val text = "\"$key\": $rest" + if (comma) "," else ""
            val keyEnd = key.length + 2
            val spans = buildList {
                add(ConsoleValueSpan(0, keyEnd, ConsoleValueToken.Key))
                add(ConsoleValueSpan(keyEnd, keyEnd + 1, ConsoleValueToken.Punctuation))
                if (restToken != null) add(ConsoleValueSpan(keyEnd + 2, keyEnd + 2 + rest.length, restToken))
                if (comma) add(ConsoleValueSpan(text.length - 1, text.length, ConsoleValueToken.Punctuation))
            }
            return ConsoleValueLine(indent, text, spans)
        }
        fun item(indent: Int, value: String, token: ConsoleValueToken, comma: Boolean): ConsoleValueLine {
            val text = value + if (comma) "," else ""
            val spans = buildList {
                add(ConsoleValueSpan(0, value.length, token))
                if (comma) add(ConsoleValueSpan(value.length, value.length + 1, ConsoleValueToken.Punctuation))
            }
            return ConsoleValueLine(indent, text, spans)
        }
        fun punct(indent: Int, text: String) =
            ConsoleValueLine(indent, text, listOf(ConsoleValueSpan(0, text.length, ConsoleValueToken.Punctuation)))
        return listOf(
            punct(0, "{"),
            key(1, "latitude", "52.37", ConsoleValueToken.Number),
            key(1, "longitude", "4.89", ConsoleValueToken.Number),
            key(1, "timezone", "\"Europe\\/Amsterdam\"", ConsoleValueToken.Str),
            key(1, "current", "{", ConsoleValueToken.Punctuation, comma = false),
            key(2, "time", "\"2026-11-01T09:00\"", ConsoleValueToken.Str),
            key(2, "temperature_2m", "11.4", ConsoleValueToken.Number),
            key(2, "is_day", "true", ConsoleValueToken.Literal),
            key(2, "rain", "null", ConsoleValueToken.Literal, comma = false),
            punct(1, "},"),
            key(1, "hourly", "{", ConsoleValueToken.Punctuation, comma = false),
            key(2, "time", "[", ConsoleValueToken.Punctuation, comma = false),
            item(3, "\"2026-11-01T00:00\"", ConsoleValueToken.Str, comma = true),
            item(3, "\"2026-11-01T01:00\"", ConsoleValueToken.Str, comma = false),
            punct(2, "],"),
            key(2, "temperature_2m", "[", ConsoleValueToken.Punctuation, comma = false),
            item(3, "9.8", ConsoleValueToken.Number, comma = true),
            item(3, "9.5", ConsoleValueToken.Number, comma = false),
            punct(2, "]"),
            punct(1, "},"),
            key(1, "alerts", "[]", ConsoleValueToken.Punctuation, comma = false),
            punct(0, "}"),
        )
    }

    /** A text value's lines, split as the app splits them; the catalog formats nothing. */
    private fun textLines(value: String): List<ConsoleValueLine> =
        value.split("\n").map { ConsoleValueLine(0, it.replace("\t", "    ")) }

    private fun snapshot(name: String, state: ConsoleValueViewerState) {
        render(state)
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }

    private fun render(state: ConsoleValueViewerState) {
        rule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    ConsoleValueViewer(state = state, onClose = {}, onCopy = { true })
                }
            }
        }
    }
}
