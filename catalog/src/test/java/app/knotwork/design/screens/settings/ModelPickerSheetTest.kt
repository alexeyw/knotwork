package app.knotwork.design.screens.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Behaviour of [ModelPickerSheetContent]: what a server's list becomes, and what a pick returns. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ModelPickerSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(ids: List<String>, onPick: (String) -> Unit = {}) {
        rule.setContent {
            KnotworkTheme {
                ModelPickerSheetContent(
                    state = ModelSheetUi(source = "server", ids = ids, selected = ""),
                    onPick = onPick,
                    focusSearch = false,
                )
            }
        }
    }

    @Test
    fun `given a server that lists an id twice when the list opens then it shows it once`() {
        // The ids are the list's keys; a repeated key throws and takes the screen down.
        render(listOf("qwen", "qwen", "llama"))

        rule.onAllNodesWithText("qwen").assertCountEquals(1)
        rule.onAllNodesWithText("llama").assertCountEquals(1)
    }

    @Test
    fun `given a search when typed then only matching ids remain, and a pick returns the id`() {
        var picked: String? = null
        render(listOf("meta-llama/llama-3.3-70b", "qwen2.5-7b", "nvidia/llama-3.3-nemotron")) { picked = it }

        rule.onNodeWithTag(MODEL_SHEET_SEARCH_TEST_TAG).performTextInput("LLAMA")
        rule.onAllNodesWithText("qwen2.5-7b").assertCountEquals(0)
        rule.onAllNodesWithText("llama-3.3-nemotron", substring = true)[0].performClick()

        assertEquals("nvidia/llama-3.3-nemotron", picked)
    }
}
