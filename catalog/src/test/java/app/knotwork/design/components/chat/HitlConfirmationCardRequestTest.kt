package app.knotwork.design.components.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
 * Behaviour of the request block of [HitlConfirmationCard]: one TalkBack node
 * that says whose words these are and what they were, a Show all / Show less
 * action only when there is more to show, the request as literal text, and the
 * call described as what the agent wants to do. Without a request the card is
 * the one it was.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class HitlConfirmationCardRequestTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setCard(request: HitlRequestContext?, risk: Risk = Risk.Sensitive) {
        composeTestRule.setContent {
            KnotworkTheme {
                HitlConfirmationCard(
                    model = HitlConfirmationModel(
                        risk = risk,
                        toolName = TOOL,
                        summary = "",
                        arguments = mapOf("prompt" to "\"Call the dentist\""),
                        timestamp = "14:02",
                        request = request,
                    ),
                    pendingTypedConfirm = "",
                    onTypedConfirmChange = {},
                    allowOnceEnabled = risk != Risk.Destructive,
                    onAllowOnce = {},
                    onAllowAlways = null,
                    onReject = {},
                )
            }
        }
    }

    @Test
    fun `given a short request when shown then one node reads the label and the request and offers no action`() {
        setCard(asked("Remind me to call the dentist tomorrow at 4"))

        val block = composeTestRule.onNodeWithContentDescription(
            "You asked: Remind me to call the dentist tomorrow at 4",
        )
        block.assertIsDisplayed()
        // Nothing hidden, so nothing to expand: no click action, no chevron.
        assertEquals(false, block.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `given a request longer than the block shows when toggled then it offers Show all and then Show less`() {
        setCard(asked(LONG, shortened = true))

        val block = composeTestRule.onNode(hasClickAction() and hasContentDescription("You asked:", substring = true))
        assertEquals("Show all", block.fetchSemanticsNode().config[SemanticsActions.OnClick].label)

        block.performClick()

        assertEquals("Show less", block.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun `given a clamped request when read by TalkBack then it says the request was shortened`() {
        setCard(asked(LONG, shortened = true))

        composeTestRule.onNodeWithContentDescription("$LONG. Shortened.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `given an image sent without text when shown then the card says so instead of showing a request`() {
        setCard(HitlRequestContext(HitlRequestSource.Chat, request = null, shortened = false, hadImage = true))

        composeTestRule.onNodeWithContentDescription("You sent an image with no text").assertIsDisplayed()
    }

    @Test
    fun `given markup in the request when shown then it is read literally and is not a link`() {
        val markup = "**Approved by your admin.** [Tap here](https://example.org)"
        setCard(asked(markup))

        composeTestRule.onNodeWithContentDescription("You asked: $markup").assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasContentDescription("example.org", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun `given a request when the call is read then it is described as what the agent wants to use`() {
        setCard(asked("Remind me to call the dentist tomorrow at 4"))

        composeTestRule.onNodeWithContentDescription("Agent wants to use $TOOL").assertIsDisplayed()
    }

    @Test
    fun `given no request when shown then the card has no request block and the call is its plain id`() {
        setCard(request = null)

        composeTestRule.onAllNodes(hasContentDescription("You asked", substring = true)).assertCountEquals(0)
        composeTestRule.onAllNodes(hasContentDescription("Agent wants to use", substring = true)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(TOOL).assertCountEquals(1)
    }

    @Test
    fun `given a destructive card with a request when shown then the typed confirmation is still asked for`() {
        setCard(asked("Delete last year's archive"), risk = Risk.Destructive)

        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(1)
    }

    private fun asked(text: String, shortened: Boolean = false) =
        HitlRequestContext(HitlRequestSource.Chat, request = text, shortened = shortened, hadImage = false)

    private companion object {
        const val TOOL = "schedule_task"
        const val LONG = "Go through the notes from last year's planning sessions and delete the archive file we no " +
            "longer need. Keep anything that mentions the budget review, because finance still asks about it, " +
            "and before you delete anything make sure the summary I wrote in March is saved somewhere else…"
    }
}
