package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
 * Visual baselines for the node configuration sheets.
 *
 * These had none until the sheets were pruned, which is the reason to add them
 * now rather than later: twelve controls were removed from seven sheets and two
 * were added, and nothing in the build could see any of it. A sheet is exactly
 * the kind of surface that rots unseen — it is reached in three taps from a
 * screen most people never open, so a control that quietly stops rendering, or
 * one that never starts, is found by a person or not at all.
 *
 * Deliberately not one shot per node type: these are the seven sheets this
 * change touched. Photographing the other seven would pin frames nobody has
 * inspected, which is the failure mode of pinning frames rather than states.
 *
 * [NodeConfigSheetBody] rather than `NodeConfigSheet`: the sheet wraps its body
 * in a `ModalBottomSheet`, which does not lay out under Robolectric. The body is
 * the whole of what these sheets show.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class NodeConfigSheetSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun node_config_input_light() = snapshot(name = "input", dark = false) {
        Sheet(InputConfig(title = "Input"))
    }

    @Test
    fun node_config_input_dark() = snapshot(name = "input", dark = true) {
        Sheet(InputConfig(title = "Input"))
    }

    @Test
    fun node_config_output_light() = snapshot(name = "output", dark = false) {
        Sheet(OutputConfig(title = "Answer", systemPrompt = "Reply in one short paragraph."))
    }

    @Test
    fun node_config_if_condition_light() = snapshot(name = "if_condition", dark = false) {
        Sheet(
            IfConditionConfig(
                title = "Is it urgent?",
                expression = "Does the message need an answer today?",
                keywords = "urgent, asap, today",
                complexityThreshold = 400,
            ),
        )
    }

    @Test
    fun node_config_if_condition_dark() = snapshot(name = "if_condition", dark = true) {
        Sheet(
            IfConditionConfig(
                title = "Is it urgent?",
                expression = "Does the message need an answer today?",
                keywords = "urgent, asap, today",
                complexityThreshold = 400,
            ),
        )
    }

    /** The two deterministic checks off, which is how every new node starts. */
    @Test
    fun node_config_if_condition_checks_off_light() = snapshot(name = "if_condition_checks_off", dark = false) {
        Sheet(IfConditionConfig(title = "Is it urgent?", expression = "Does it need an answer today?"))
    }

    @Test
    fun node_config_tool_light() = snapshot(name = "tool", dark = false) {
        Sheet(ToolConfig(title = "Call a tool", toolId = "search_tool"))
    }

    @Test
    fun node_config_decomposition_light() = snapshot(name = "decomposition", dark = false) {
        Sheet(DecompositionConfig(title = "Plan", planningPrompt = "Break the task into steps.", maxSubtasks = 5))
    }

    @Test
    fun node_config_queue_processor_light() = snapshot(name = "queue_processor", dark = false) {
        Sheet(QueueProcessorConfig(title = "Each subtask"))
    }

    @Test
    fun node_config_summary_light() = snapshot(name = "summary", dark = false) {
        Sheet(SummaryConfig(title = "Summarise", customPrompt = "Three bullets, no preamble."))
    }

    // ─── Provider and engine fields ────────────────────────────────────────

    @Test
    fun node_config_cloud_provider_light() = snapshot(name = "cloud_provider", dark = false) {
        Sheet(cloud(CloudProvider.OPENAI_COMPATIBLE))
    }

    @Test
    fun node_config_cloud_provider_dark() = snapshot(name = "cloud_provider", dark = true) {
        Sheet(cloud(CloudProvider.OPENAI_COMPATIBLE))
    }

    /** A provider not set up on this device: still chosen, marked, and the hint says what happens. */
    @Test
    fun node_config_cloud_provider_not_configured_light() =
        snapshot(name = "cloud_provider_not_configured", dark = false) { Sheet(cloud(CloudProvider.GROQ)) }

    @Test
    fun node_config_cloud_provider_not_configured_dark() =
        snapshot(name = "cloud_provider_not_configured", dark = true) { Sheet(cloud(CloudProvider.GROQ)) }

    /** *Auto*: the field says what it resolves to here; the hint names the four it chooses among. */
    @Test
    fun node_config_cloud_provider_auto_light() =
        snapshot(name = "cloud_provider_auto", dark = false) { Sheet(cloud(CloudProvider.AUTO)) }

    @Test
    fun node_config_cloud_provider_auto_dark() =
        snapshot(name = "cloud_provider_auto", dark = true) { Sheet(cloud(CloudProvider.AUTO)) }

    @Test
    fun node_config_cloud_provider_picker_light() = snapshot(name = "cloud_provider_picker", dark = false) {
        ProviderList(title = "Provider", first = AUTO_ROW, selected = CloudProvider.OPENAI_COMPATIBLE)
    }

    @Test
    fun node_config_cloud_provider_picker_dark() = snapshot(name = "cloud_provider_picker", dark = true) {
        ProviderList(title = "Provider", first = AUTO_ROW, selected = CloudProvider.OPENAI_COMPATIBLE)
    }

    /** At 200 % all nine names show in full and the list scrolls; the chosen row is in view. */
    @Test
    fun node_config_cloud_provider_picker_fontscale200_light() =
        snapshot(name = "cloud_provider_picker_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ProviderList(title = "Provider", first = AUTO_ROW, selected = CloudProvider.OPENAI_COMPATIBLE)
        }

    @Test
    fun node_config_cloud_provider_picker_fontscale200_dark() =
        snapshot(name = "cloud_provider_picker_fontscale200", dark = true, fontScale = FONT_SCALE_200) {
            ProviderList(title = "Provider", first = AUTO_ROW, selected = CloudProvider.OPENAI_COMPATIBLE)
        }

    @Test
    fun node_config_tool_engine_light() = snapshot(name = "tool_engine", dark = false) {
        Sheet(ToolConfig(title = "fetch-weather", toolId = "search_tool", engineProvider = CloudProvider.OLLAMA))
    }

    @Test
    fun node_config_tool_engine_dark() = snapshot(name = "tool_engine", dark = true) {
        Sheet(ToolConfig(title = "fetch-weather", toolId = "search_tool", engineProvider = CloudProvider.OLLAMA))
    }

    @Test
    fun node_config_tool_engine_picker_light() = snapshot(name = "tool_engine_picker", dark = false) {
        ProviderList(title = "Engine", first = ON_DEVICE_ROW, selected = CloudProvider.OLLAMA)
    }

    @Test
    fun node_config_tool_engine_picker_dark() = snapshot(name = "tool_engine_picker", dark = true) {
        ProviderList(title = "Engine", first = ON_DEVICE_ROW, selected = CloudProvider.OLLAMA)
    }

    @Test
    fun node_config_tool_engine_picker_fontscale200_light() =
        snapshot(name = "tool_engine_picker_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
            ProviderList(title = "Engine", first = ON_DEVICE_ROW, selected = CloudProvider.OLLAMA)
        }

    @Test
    fun node_config_tool_engine_picker_fontscale200_dark() =
        snapshot(name = "tool_engine_picker_fontscale200", dark = true, fontScale = FONT_SCALE_200) {
            ProviderList(title = "Engine", first = ON_DEVICE_ROW, selected = CloudProvider.OLLAMA)
        }

    private fun cloud(provider: CloudProvider) =
        CloudConfig(title = "summarise", provider = provider, systemPrompt = "Summarise the input in three bullets.")

    /** The provider list as it opens over the node sheet. */
    @Composable
    private fun ProviderList(title: String, first: ProviderChoiceRowUi, selected: CloudProvider?) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            ProviderChoiceListContent(
                title = title,
                options = listOf(
                    first,
                ) + PROVIDER_ROWS,
                selected = selected,
                onPick = {
                },
            )
        }
    }

    /** Renders one sheet body with every optional hook left out. */
    @Composable
    private fun Sheet(config: NodeConfig) {
        NodeConfigSheetBody(
            config = config,
            errors = NodeConfigValidation.validate(config = config, peerTitles = emptySet()),
            onChange = {},
            onCancel = {},
            onSave = {},
            availableToolIds = listOf("search_tool", "schedule_task"),
        )
    }

    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalProviderChoices provides CHOICES,
                ) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/node_config_${name}_$themeTag.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200 = 2f

        /** Three providers set up on this device; *Auto* resolves to OpenAI. */
        val CHOICES = ProviderChoices(
            configuredModels = mapOf(
                CloudProvider.OPEN_AI to "gpt-4o-mini",
                CloudProvider.OLLAMA to "llama3.2:3b",
                CloudProvider.OPENAI_COMPATIBLE to "qwen2.5-7b-instruct",
            ),
            autoResolvesTo = CloudProvider.OPEN_AI,
            onDeviceModel = "gemma-4-E2B",
        )

        private const val NOT_HERE = "not configured on this device"

        val AUTO_ROW = ProviderChoiceRowUi(
            value = CloudProvider.AUTO,
            name = "Auto",
            line = "first configured of Google, Anthropic, OpenAI, DeepSeek",
            here = true,
        )

        val ON_DEVICE_ROW = ProviderChoiceRowUi(value = null, name = "On-device", line = "gemma-4-E2B", here = true)

        /** Every provider as the sheet lists it with [CHOICES]. */
        val PROVIDER_ROWS = listOf(
            ProviderChoiceRowUi(CloudProvider.OPEN_AI, "OpenAI", "gpt-4o-mini", here = true),
            ProviderChoiceRowUi(CloudProvider.ANTHROPIC, "Anthropic", NOT_HERE, here = false),
            ProviderChoiceRowUi(CloudProvider.GOOGLE, "Google", NOT_HERE, here = false),
            ProviderChoiceRowUi(CloudProvider.DEEPSEEK, "DeepSeek", NOT_HERE, here = false),
            ProviderChoiceRowUi(CloudProvider.OPENROUTER, "OpenRouter", NOT_HERE, here = false),
            ProviderChoiceRowUi(CloudProvider.GROQ, "Groq", NOT_HERE, here = false),
            ProviderChoiceRowUi(CloudProvider.OLLAMA, "Ollama", "llama3.2:3b", here = true),
            ProviderChoiceRowUi(
                CloudProvider.OPENAI_COMPATIBLE,
                "OpenAI-compatible server",
                "qwen2.5-7b-instruct",
                here = true,
            ),
        )
    }
}
