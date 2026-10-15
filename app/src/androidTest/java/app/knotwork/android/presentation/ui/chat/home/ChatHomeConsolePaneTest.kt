package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the Console pane plumbing as exposed through
 * [ChatHomeScreen]: tab switching, source-filter toggling, inline-search
 * input, Clear with confirm-dialog, and the Copy-line round-trip into the
 * Compose [ClipboardManager].
 *
 * Clipboard verification injects a [RecordingClipboardManager] via the
 * Compose [LocalClipboardManager] composition local so the test stays
 * isolated from the platform-level clipboard service (which is rate-limited
 * on Android 10+ and produces noisy logs in CI).
 */
@Suppress("DEPRECATION") // ClipboardManager is deprecated in favour of Clipboard; ChatHomeScreen still uses it.
class ChatHomeConsolePaneTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Captures every `setText` call so the test can assert the exact
     * payload `ChatHomeScreen` puts on the clipboard for `Copy line` /
     * `Copy all`.
     */
    private class RecordingClipboardManager : ClipboardManager {
        var lastText: AnnotatedString? = null
            private set

        override fun setText(annotatedString: AnnotatedString) {
            lastText = annotatedString
        }

        override fun getText(): AnnotatedString? = lastText

        override fun hasText(): Boolean = lastText?.text?.isNotEmpty() == true
    }

    private fun setContentWithConsoleOpen(
        viewModel: ChatHomeViewModel,
        clipboard: ClipboardManager = RecordingClipboardManager(),
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun tabStrip_clickingVars_invokesOnConsoleTabChange() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleLines = listOf(sampleLine("first line")),
        )
        setContentWithConsoleOpen(viewModel)

        // FullTabStrip renders the tab label as `ConsoleTab.entries[i].name`
        // verbatim — PascalCase, not uppercase. See catalog ConsolePane.kt.
        composeTestRule.onNodeWithText(ConsoleTab.Vars.name).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.console.onConsoleTabChange(ConsoleTab.Vars) }
    }

    @Test
    fun tabStrip_clickingTraces_invokesOnConsoleTabChange() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
        )
        setContentWithConsoleOpen(viewModel)

        composeTestRule.onNodeWithText(ConsoleTab.Traces.name).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.console.onConsoleTabChange(ConsoleTab.Traces) }
    }

    @Test
    fun sourceFilterChip_tap_invokesOnConsoleFilterChange() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleLines = listOf(sampleLine("hello")),
        )
        setContentWithConsoleOpen(viewModel)

        // The NODE chip is initially ON; tapping should remove NODE from the
        // active filter set.
        composeTestRule.onNodeWithText("NODE").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) {
            handles.console.onConsoleFilterChange(
                match {
                    it.sources == setOf(
                        ConsoleSource.TOOL,
                        ConsoleSource.MEMORY,
                        ConsoleSource.RUNTIME,
                        ConsoleSource.USER,
                    )
                },
            )
        }
    }

    @Test
    fun memoryRetrievalLine_isVisibleInLogsTab() {
        val memoryLine = ConsoleLine(
            timestamp = "12:00:00.000",
            source = ConsoleSource.MEMORY,
            level = ConsoleLevel.Info,
            text = "Memory: query='what is my UI preference' → 2 hits (0.83, 0.40)",
        )
        val (viewModel, _) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleLines = listOf(memoryLine),
            initialConsoleFilter = ConsoleFilter.allOn,
        )
        setContentWithConsoleOpen(viewModel)

        // The MEMORY filter chip is rendered and the retrieval line is shown.
        composeTestRule.onNodeWithText("MEMORY").assertExists()
        composeTestRule
            .onNodeWithText("Memory: query='what is my UI preference' → 2 hits (0.83, 0.40)")
            .assertExists()
    }

    @Test
    fun memoryFilterChip_tap_invokesOnConsoleFilterChange() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleLines = listOf(sampleLine("hello")),
        )
        setContentWithConsoleOpen(viewModel)

        // The MEMORY chip is initially ON; tapping should remove MEMORY only.
        composeTestRule.onNodeWithText("MEMORY").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) {
            handles.console.onConsoleFilterChange(
                match {
                    it.sources == setOf(
                        ConsoleSource.NODE,
                        ConsoleSource.TOOL,
                        ConsoleSource.RUNTIME,
                        ConsoleSource.USER,
                    )
                },
            )
        }
    }

    @Test
    fun searchHeaderIcon_tap_invokesToggleConsoleSearch() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
        )
        setContentWithConsoleOpen(viewModel)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val searchCd = ctx.getString(KnotworkR.string.knotwork_console_action_search)
        composeTestRule.onNodeWithContentDescription(searchCd).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.console.toggleConsoleSearch() }
    }

    @Test
    fun searchField_textInput_forwardsToOnConsoleSearchQueryChange() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            // Non-null searchQuery makes the inline search bar visible.
            initialConsoleSearchQuery = "",
        )
        setContentWithConsoleOpen(viewModel)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val fieldCd = ctx.getString(KnotworkR.string.knotwork_console_search_field_cd)
        composeTestRule.onNodeWithContentDescription(fieldCd).performTextInput("err")
        composeTestRule.waitForIdle()

        verify(atLeast = 1) { handles.console.onConsoleSearchQueryChange(any()) }
    }

    @Test
    fun clearHeaderIcon_tap_invokesRequestConsoleClear() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
        )
        setContentWithConsoleOpen(viewModel)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val clearCd = ctx.getString(KnotworkR.string.knotwork_console_action_clear)
        composeTestRule.onNodeWithContentDescription(clearCd).performClick()
        composeTestRule.waitForIdle()

        verify(atLeast = 1) { handles.console.requestConsoleClear() }
    }

    @Test
    fun clearConfirmDialog_confirmButton_invokesConfirmConsoleClear() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleClearConfirm = true,
        )
        setContentWithConsoleOpen(viewModel)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val confirmLabel = ctx.getString(R.string.chat_console_clear_dialog_confirm)
        composeTestRule.onNodeWithText(confirmLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.console.confirmConsoleClear() }
    }

    @Test
    fun clearConfirmDialog_cancelButton_invokesDismissConsoleClear() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleClearConfirm = true,
        )
        setContentWithConsoleOpen(viewModel)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cancelLabel = ctx.getString(R.string.chat_console_clear_dialog_cancel)
        composeTestRule.onNodeWithText(cancelLabel).performClick()
        composeTestRule.waitForIdle()

        verify(atLeast = 1) { handles.console.dismissConsoleClear() }
    }

    @Test
    fun copyLine_longPressThenMenuItem_writesPayloadToClipboard() {
        val line = sampleLine("the line we copy")
        val (viewModel, handles) = mockChatHomeViewModel(
            initialConsoleSnap = ConsoleSnap.Partial,
            initialConsoleLines = listOf(line),
            initialConsoleFilter = ConsoleFilter.allOn,
        )
        val clipboard = RecordingClipboardManager()
        setContentWithConsoleOpen(viewModel, clipboard = clipboard)

        // Long-press the row to surface the per-row dropdown menu.
        composeTestRule
            .onNodeWithText("the line we copy")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val copyLabel = ctx.getString(KnotworkR.string.knotwork_console_line_copy)
        // The dropdown menu is anchored to the row; if there is more than one
        // matching node in the tree we take the first one.
        composeTestRule.onAllNodesWithText(copyLabel)[0].performClick()
        composeTestRule.waitForIdle()

        // Asserted against the formatter rather than a literal: this test owns the
        // wiring — that the long-pressed row's own line reaches the clipboard — while
        // the format itself is pinned by `ConsoleCopyPayloadsTest` on the JVM. A
        // literal here would duplicate that format in a source set `check` does not
        // even compile, which is how this test came to reference a method that no
        // longer exists.
        assertEquals("Clipboard payload mismatch", ConsoleCopyPayloads.line(line), clipboard.lastText?.text)
        verify(exactly = 1) { handles.console.signalConsoleLineCopied() }
    }

    private fun sampleLine(text: String): ConsoleLine = ConsoleLine(
        timestamp = "12:00:00.000",
        source = ConsoleSource.NODE,
        level = ConsoleLevel.Info,
        text = text,
    )
}
