package app.knotwork.design.components.console

import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import org.robolectric.shadows.ShadowDialog

/**
 * Roborazzi baselines of the console's value viewer: a JSON value laid out and coloured,
 * a text value with its own lines, a value that starts like JSON but does not parse, one
 * long line without spaces cut into pieces, the formatting notice, and JSON at 200 %; a
 * long log with its scroll thumb at rest and dragged, and a back gesture's preview.
 * Reduced motion, so the opening is a short fade that the clock finishes — except for the
 * preview, which reduced motion turns off.
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
        capture("console_value_viewer_formatting")
    }

    @Test
    fun console_value_viewer_thumb() {
        render(logState())
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        rule.mainClock.advanceTimeBy(400)
        capture("console_value_viewer_thumb")
    }

    @Test
    fun console_value_viewer_thumb_drag() = dragSnapshot("console_value_viewer_thumb_drag")

    @Test
    @Config(fontScale = 2f)
    fun console_value_viewer_thumb_drag_fs20() = dragSnapshot("console_value_viewer_thumb_drag_fs20")

    @Test
    fun console_value_viewer_back_preview() {
        render(jsonState(), reducedMotion = false)
        rule.waitForIdle()
        val dispatcher = (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 600f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        capture("console_value_viewer_back_preview")
    }

    /** The log scrolled by a finger, then its thumb held 40 % of the way down. */
    private fun dragSnapshot(name: String) {
        render(logState())
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 520f))
        }
        rule.mainClock.advanceTimeBy(100)
        capture(name)
        rule.onNodeWithTag(CONSOLE_VALUE_THUMB_TAG).performTouchInput { up() }
    }

    /** 2 000 lines of a request log: well over five screens, so the thumb shows. */
    private fun logState(): ConsoleValueViewerState {
        val lines = (0 until 2_000).map { i ->
            "09:%02d:%02d.%03d GET /v1/forecast?latitude=52.37 %d %d ms".format(
                i / 60 % 60,
                i % 60,
                i * 37 % 1_000,
                if (i % 41 == 0) 503 else 200,
                20 + i * 13 % 90,
            )
        }
        return ConsoleValueViewerState(
            row = ConsoleVarRow(node = "TOOL#9d3e01", key = "body", value = lines.joinToString("\n")),
            body = ConsoleValueBody.Ready(ConsoleValueFormat.Text, lines.map { ConsoleValueLine(0, it) }),
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
        capture(name)
    }

    private fun capture(name: String) {
        rule.onNode(isDialog()).captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }

    private fun render(state: ConsoleValueViewerState, reducedMotion: Boolean = true) {
        rule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = reducedMotion)) {
                    ConsoleValueViewer(state = state, onClose = {}, onCopy = { true })
                }
            }
        }
    }
}
