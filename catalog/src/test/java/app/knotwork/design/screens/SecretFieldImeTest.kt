package app.knotwork.design.screens

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.screens.discover.DiscoverDetailContent
import app.knotwork.design.screens.discover.DiscoverPreview
import app.knotwork.design.screens.models.ModelsContent
import app.knotwork.design.screens.models.ModelsPreview
import app.knotwork.design.screens.settings.KnotworkProviderRow
import app.knotwork.design.screens.tools.AddMcpServerForm
import app.knotwork.design.screens.tools.McpAuthSelector
import app.knotwork.design.screens.tools.McpHeaderRow
import app.knotwork.design.screens.tools.McpServerConfigContent
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every field that takes a credential tells the keyboard it is a password.
 *
 * Masking the text is not enough: `PasswordVisualTransformation` changes only what
 * is drawn, and the input method learns what it is told by the input type. A
 * keyboard told nothing may show a pasted API key in its suggestion strip, keep
 * it in its personal dictionary and, if it syncs, carry it off the device.
 *
 * Each case focuses every editable field of one surface, in composition order,
 * and reads the input type the field asks the keyboard for through
 * [InterceptPlatformTextInput] — the request a real input method would receive.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h2400dp-xhdpi")
class SecretFieldImeTest {

    @get:Rule
    val rule = createComposeRule()

    private val inputTypes = mutableListOf<Int>()

    private fun render(content: @Composable () -> Unit) {
        rule.setContent {
            KnotworkTheme {
                InterceptPlatformTextInput(
                    interceptor = { request, _ ->
                        val info = EditorInfo()
                        request.createInputConnection(info)
                        inputTypes += info.inputType
                        awaitCancellation()
                    },
                ) { content() }
            }
        }
    }

    /**
     * Focuses every editable field in composition order.
     *
     * @return for each field, its text (as drawn) and whether it asked for a password.
     */
    private fun fields(): List<Pair<String, Boolean>> {
        val nodes = rule.onAllNodes(hasSetTextAction())
        val count = nodes.fetchSemanticsNodes().size
        return (0 until count).map { index ->
            inputTypes.clear()
            nodes[index].performClick()
            rule.waitForIdle()
            val type = checkNotNull(inputTypes.lastOrNull()) { "field $index started no input session" }
            val text = nodes[index].fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            text to ((type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
    }

    private fun passwordFieldsAmong(fields: List<Pair<String, Boolean>>) = fields.map { it.second }

    private val mcpForm = AddMcpServerForm(url = "https://mcp.example", name = "notes")

    @Test
    fun `given a bearer token field when focused then it asks for a password`() {
        render { McpServerConfigContent(form = mcpForm.copy(authType = McpAuthSelector.BEARER, bearerToken = "t0k3n")) }

        assertEquals(listOf(false, false, true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given basic auth when focused then only the password asks for a password`() {
        render {
            McpServerConfigContent(
                form = mcpForm.copy(authType = McpAuthSelector.BASIC, basicUsername = "me", basicPassword = "pw"),
            )
        }

        assertEquals(listOf(false, false, false, true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given an API key when focused then only its value asks for a password`() {
        render {
            McpServerConfigContent(
                form = mcpForm.copy(authType = McpAuthSelector.API_KEY, apiKeyHeaderName = "X-Key", apiKeyValue = "v"),
            )
        }

        assertEquals(listOf(false, false, false, true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given a custom header when focused then only its value asks for a password`() {
        render { McpServerConfigContent(form = mcpForm.copy(headers = listOf(McpHeaderRow("Authorization", "x")))) }

        assertEquals(listOf(false, false, false, true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given a cloud provider's API key when focused then it asks for a password`() {
        render {
            KnotworkProviderRow(
                title = "Anthropic",
                keyValue = "sk-ant",
                onKeyChange = {},
                keyLabel = "API key",
                modelValue = "claude",
                onModelChange = {},
                modelLabel = "Model",
                availableModels = listOf("claude"),
            )
        }
        rule.onNodeWithText("Anthropic").performClick()

        assertEquals(listOf(true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given the Hugging Face token on a gated model when focused then it asks for a password`() {
        render { DiscoverDetailContent(state = DiscoverPreview.detailGated()) }

        assertEquals(listOf(true), passwordFieldsAmong(fields()))
    }

    @Test
    fun `given the Hugging Face token on the models screen when focused then it asks for a password`() {
        render { ModelsContent(state = ModelsPreview.default()) }

        // The token field, then the custom-model URL.
        assertEquals(listOf(true, false), passwordFieldsAmong(fields()))
    }
}
