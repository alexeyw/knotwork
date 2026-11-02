package app.knotwork.design.components.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.components.knotworkMarkdownColor
import app.knotwork.design.components.knotworkMarkdownComponents
import app.knotwork.design.components.knotworkMarkdownTypography
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines of a long message's bubble, scrolled to the end of its preview: the
 * fade into the bubble's colour, *Read whole message* with the share shown, and the footer
 * under the bubble. The agent's Markdown answer in both themes and at 200 %, and the
 * user's own pasted text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class LongMessagePreviewSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    /** The design's restic guide, long enough to be cut: sections of prose, a list and code. */
    private val guide = (1..14).joinToString("\n\n") { n ->
        """
        |## Step $n: back up the server with restic
        |
        |Restic keeps **deduplicated, encrypted** snapshots. Run it from cron and keep the password in a
        |file only root can read, so that a stolen backup disk shows nothing but noise.
        |
        |1. Install it: `apt install restic`.
        |2. Create the repository once, on the disk you mount at night.
        |3. Add the nightly job and check its first log the next morning.
        |
        |```bash
        |export RESTIC_REPOSITORY=/mnt/backup/restic
        |restic backup /etc /home /var/lib/postgresql --exclude-caches
        |```
        """.trimMargin()
    }

    private val pasted = (1..120).joinToString("\n") { n ->
        "2026-11-01 09:%02d:%02d GET /v1/forecast?latitude=52.37&longitude=4.89 200 %d ms".format(
            n / 60,
            n % 60,
            20 + n % 70,
        )
    }

    @Test
    fun chat_long_preview_agent() = snapshot("chat_long_preview_agent", ChatRole.Assistant, markdown(guide))

    @Test
    fun chat_long_preview_agent_dark() =
        snapshot("chat_long_preview_agent_dark", ChatRole.Assistant, markdown(guide), dark = true)

    @Test
    @Config(fontScale = 2f)
    fun chat_long_preview_agent_fs20() = snapshot("chat_long_preview_agent_fs20", ChatRole.Assistant, markdown(guide))

    @Test
    fun chat_long_preview_user() = snapshot(
        "chat_long_preview_user",
        ChatRole.User,
        ChatContent.Text(pasted, chatMessagePreview(pasted, markdown = false)),
    )

    private fun markdown(source: String) = ChatContent.Markdown(source, chatMessagePreview(source, markdown = true))

    private fun snapshot(name: String, role: ChatRole, content: ChatContent, dark: Boolean = false) {
        val scroll = ScrollState(0)
        rule.setContent {
            KnotworkTheme(darkTheme = dark) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ChatMessage(
                        role = role,
                        content = content,
                        metadata = ChatMetadata(
                            timestamp = "09:14",
                            model = "gemma-3n-E4B".takeIf {
                                role !=
                                    ChatRole.User
                            },
                        ),
                        markdownRenderer = { source ->
                            Markdown(
                                content = source,
                                typography = knotworkMarkdownTypography(),
                                colors = knotworkMarkdownColor(),
                                components = knotworkMarkdownComponents(),
                            )
                        },
                    )
                }
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(
                hasText("Step 1", substring = true) or hasText("2026-11-01", substring = true),
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes().isNotEmpty()
        }
        // To the bottom: the end of the preview, its action and the footer under the bubble.
        rule.onNodeWithTag(CHAT_READ_WHOLE_TAG).assertExists()
        rule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }
}
