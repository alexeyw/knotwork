package app.knotwork.design.screens.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins which Memory states are allowed to offer which affordances.
 *
 * Both controls this screen used to show were inert: the "Open chat" CTA was
 * wired to a handler the navigation graph never supplied, and the magnifier
 * opened a search field that the `Empty` branch of the body never rendered. An
 * external tester hit both in one go — "Тыкаю в эти две, ничо не происходит.
 * ваще". What remains is the explanation plus the working "Add memory" FAB.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MemoryAffordanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `empty memory does not offer the search action`() {
        composeTestRule.setContent {
            KnotworkTheme { MemoryContent(state = MemoryPreview.empty()) }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.knotwork_memory_search_cd))
            .assertDoesNotExist()
    }

    @Test
    fun `empty memory keeps its explanation and the add affordance`() {
        composeTestRule.setContent {
            KnotworkTheme { MemoryContent(state = MemoryPreview.empty()) }
        }

        composeTestRule.onNodeWithText(string(R.string.knotwork_memory_empty_title)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.knotwork_memory_empty_subtitle)).assertExists()
        // The FAB is the one thing on this screen that can actually act. Its label
        // lives under the button's merged semantics, hence the unmerged lookup.
        composeTestRule
            .onNodeWithText(string(R.string.knotwork_memory_add_memory), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `error state does not offer the search action`() {
        // `Error` outranks `Searching` in the screen's own projection, exactly as
        // `Empty` does, so a magnifier here would set the flag and change nothing.
        composeTestRule.setContent {
            KnotworkTheme { MemoryContent(state = MemoryPreview.error()) }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.knotwork_memory_search_cd))
            .assertDoesNotExist()
    }

    @Test
    fun `populated memory still offers the search action`() {
        composeTestRule.setContent {
            KnotworkTheme { MemoryContent(state = MemoryPreview.populated()) }
        }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.knotwork_memory_search_cd))
            .assertExists()
    }

    @Test
    fun `a sheet that switches to another entry opens at its top`() {
        // Given — a long entry scrolled down, as after reading its earlier versions.
        val long = "Prefers replies in English. ".repeat(60)
        val first = MemoryPreview.historySheet().let { state ->
            state.copy(expandedEntry = state.expandedEntry!!.copy(body = long))
        }
        var state by mutableStateOf(first)
        composeTestRule.setContent { KnotworkTheme { MemoryContent(state = state) } }
        composeTestRule.onNodeWithText("2 earlier versions").performScrollTo()

        // When — *Use the update* moves the sheet to another, equally long entry.
        state = first.copy(expandedEntry = first.expandedEntry!!.copy(id = "99", title = "Coffee order"))
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithText("Coffee order").assertIsDisplayed()
    }
}
