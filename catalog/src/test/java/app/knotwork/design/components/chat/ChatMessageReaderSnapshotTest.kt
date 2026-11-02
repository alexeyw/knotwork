package app.knotwork.design.components.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

/**
 * Roborazzi baselines of the chat's message reader: an agent's Markdown answer in both
 * themes and at 200 %, the user's own message as plain text, the seam between two chunks
 * of a long code block, and the end of the list while the rest of a long message is
 * being prepared. Reduced motion, so the opening is a short fade that the clock finishes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ChatMessageReaderSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    /** Runs every step at once on the caller's thread, except the one numbered [held] (from 1), which never runs. */
    private class HoldStep(private val held: Int) : CoroutineDispatcher() {
        private var step = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (++step != held) block.run()
        }
    }

    /** Runs every step at once on the caller's thread, so a snapshot sees the whole message without waiting. */
    private object AtOnce : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    private val guide = """
        |## Back up the server with restic
        |
        |Restic keeps **deduplicated, encrypted** snapshots. Run it from cron and keep the
        |password in a file only root can read.
        |
        |1. Install it: `apt install restic`.
        |2. Create the repository once.
        |3. Add the nightly job.
        |
        |```bash
        |export RESTIC_REPOSITORY=/mnt/backup/restic
        |export RESTIC_PASSWORD_FILE=/root/.restic-pass
        |restic init
        |```
        |
        |> Test a restore before you need one.
        |
        |### Keep a week, then a month
        |
        |`restic forget --keep-daily 7 --keep-monthly 6 --prune` drops everything else.
    """.trimMargin()

    @Test
    fun chat_message_reader_markdown() = snapshot("chat_message_reader_markdown", agent(guide))

    @Test
    fun chat_message_reader_markdown_dark() = snapshot("chat_message_reader_markdown_dark", agent(guide), dark = true)

    @Test
    @Config(fontScale = 2f)
    fun chat_message_reader_markdown_fs20() = snapshot("chat_message_reader_markdown_fs20", agent(guide))

    @Test
    fun chat_message_reader_user() {
        val pasted = "Can you check this config before I deploy it?\n\n" +
            "server:\n  port: 8443\n  tls: true\n  workers: 12\n\n" +
            "The workers line is the one I'm least sure about — the box has 8 cores."
        snapshot(
            "chat_message_reader_user",
            ChatMessageReaderState(ChatRole.User, "09:12", pasted, markdown = false),
        )
    }

    @Test
    fun chat_message_reader_code_chunks() {
        val code = (1..60).joinToString("\n") { "    val step$it = pipeline.node(\"step-$it\").run(input$it)" }
        val message = "The generated module:\n\n```kotlin\n$code\n```\n\nThat is all of it."
        render(agent(message))
        rule.waitForIdle()
        // The second chunk at the top of the list, then pulled down slowly, so the seam
        // between the first two chunks is on screen.
        val start = message.indexOf("```")
        val list = rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG)
        list.performScrollToKey("c$start:1")
        list.performTouchInput { swipeDown(startY = centerY, endY = centerY + 400f, durationMillis = 2_000) }
        rule.waitForIdle()
        capture("chat_message_reader_code_chunks")
    }

    @Test
    fun chat_message_reader_preparing() {
        val long = (1..30).joinToString("\n\n") { "Paragraph $it. " + "Words to read slowly. ".repeat(25) }
        // The steps in order: the head's parse, the whole's parse (held), the head's items.
        render(agent(long), work = HoldStep(2))
        rule.waitForIdle()
        rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG).performScrollToKey("preparing")
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = false
        capture("chat_message_reader_preparing")
    }

    @Test
    fun chat_message_reader_thumb_drag() {
        val sections = (1..30).joinToString("\n\n") { n ->
            "## Section $n: restore the database\n\n" + "Stop the service, restore the dump, start it again. ".repeat(6)
        }
        render(agent(sections))
        rule.waitForIdle()
        val list = rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG)
        rule.mainClock.autoAdvance = false
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag(CHAT_READER_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 520f))
        }
        rule.mainClock.advanceTimeBy(100)
        capture("chat_message_reader_thumb_drag")
        rule.onNodeWithTag(CHAT_READER_THUMB_TAG).performTouchInput { up() }
    }

    @Test
    fun chat_message_reader_table_chunks() {
        val rows = (1..30).joinToString("\n") { n -> "| Station $n | ${n * 3} | ${n * 7 % 40} |" }
        val message = "Bike stations near you:\n\n| Station | Bikes | Docks |\n|---|---|---|\n$rows"
        render(agent(message))
        rule.waitForIdle()
        // The seam between the first two chunks, pulled into view.
        val list = rule.onNodeWithTag(CHAT_MESSAGE_READER_LIST_TAG)
        list.performScrollToKey("g${message.indexOf("| Station |")}:1")
        list.performTouchInput { swipeDown(startY = centerY, endY = centerY + 400f, durationMillis = 2_000) }
        rule.waitForIdle()
        capture("chat_message_reader_table_chunks")
    }

    @Test
    fun chat_message_reader_table_long_cells() {
        val message = """
            |What to back up, and how:
            |
            || What | Where | Tool |
            ||---|---|---|
            || Container data | Docker volumes and the compose files that start them | `tar` of each volume |
            || Web server | Configuration files and virtual hosts, with their certificates | `tar` |
            || Scheduled jobs | The crontab of root and of every service user | `crontab -l` |
            || SSH | Host keys and `authorized_keys` of each account | `tar` + `gpg` |
            || Mail | Mailboxes of every domain the server receives mail for | `rsync` |
        """.trimMargin()
        snapshot("chat_message_reader_table_long_cells", agent(message))
    }

    private fun agent(text: String) = ChatMessageReaderState(ChatRole.Assistant, "09:14 · gemma-3n-E4B", text, true)

    private fun snapshot(name: String, state: ChatMessageReaderState, dark: Boolean = false) {
        render(state, dark)
        rule.waitForIdle()
        capture(name)
    }

    private fun capture(name: String) {
        rule.onNode(isDialog()).captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }

    private fun render(state: ChatMessageReaderState, dark: Boolean = false, work: CoroutineDispatcher = AtOnce) {
        rule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                    LocalChatReaderWork provides work,
                ) {
                    ChatMessageReader(state = state, onClose = {}, onCopy = { true })
                }
            }
        }
    }
}
