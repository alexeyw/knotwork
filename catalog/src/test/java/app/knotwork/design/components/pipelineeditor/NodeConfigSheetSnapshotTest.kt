package app.knotwork.design.components.pipelineeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
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
}
