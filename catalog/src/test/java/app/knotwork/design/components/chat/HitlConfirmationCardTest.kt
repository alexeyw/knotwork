package app.knotwork.design.components.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioural coverage for the summary line of [HitlConfirmationCard].
 *
 * The card shows the tool id in mono and the agent's explanation as prose
 * below it. A live pending confirmation carries no explanation, and the
 * screen used to pass the tool name into that slot — so the card printed the
 * same string twice, which reads as a rendering fault rather than as a
 * missing explanation. These tests pin both halves of the contract: a blank
 * summary drops the line, a real summary still renders.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class HitlConfirmationCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setCard(summary: String) {
        composeTestRule.setContent {
            KnotworkTheme {
                HitlConfirmationCard(
                    model = HitlConfirmationModel(
                        risk = Risk.Sensitive,
                        toolName = TOOL_NAME,
                        summary = summary,
                        arguments = mapOf("path" to "\"inbox/captures.md\""),
                        timestamp = "15:12",
                    ),
                    pendingTypedConfirm = "",
                    onTypedConfirmChange = {},
                    allowOnceEnabled = true,
                    onAllowOnce = {},
                    onAllowAlways = null,
                    onReject = {},
                )
            }
        }
    }

    @Test
    fun `given blank summary when rendered then the tool name appears exactly once`() {
        setCard(summary = "")

        assertEquals(
            1,
            composeTestRule.onAllNodesWithText(TOOL_NAME).fetchSemanticsNodes().size,
        )
        // The line is absent, not merely empty: an empty Text would still put a
        // blank row between the tool id and the arguments block.
        assertEquals(
            0,
            composeTestRule.onAllNodes(hasText("")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `given a real summary when rendered then both the tool name and the summary show`() {
        setCard(summary = SUMMARY)

        composeTestRule.onNodeWithText(TOOL_NAME).assertIsDisplayed()
        composeTestRule.onNodeWithText(SUMMARY).assertIsDisplayed()
    }

    private companion object {
        const val TOOL_NAME = "append_file"
        const val SUMMARY = "Appends the captured note to your workspace inbox."
    }
}
