package app.knotwork.android.presentation.ui.orchestrator.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme

/**
 * Renders the "Input Data" section of the node configuration dialog: a header,
 * five labelled checkboxes corresponding to the flags of
 * `NodeContextConfig`, and a hint banner advising the user to keep the input
 * minimal.
 *
 * The first checkbox ("Previous node output") is rendered locked-on because
 * `OrchestratorViewModel.updateNodeContextConfig` always forces
 * `nodeInput = true` — disabling it would silently break the chain. The
 * stateful caller is therefore not allowed to flip this flag from the UI; we
 * accept no callback for it.
 *
 * The composable is stateless: every visible flag is a parameter and every
 * change is reported through a dedicated lambda. State must be hoisted into
 * the parent dialog so that the user can review the changes before pressing
 * Save.
 *
 * @param originalTask Whether the "Original user task" checkbox is checked.
 * @param chatHistory Whether the "Chat history" checkbox is checked.
 * @param longTermMemory Whether the "Long-term memory" checkbox is checked.
 * @param toolResults Whether the "Tool results" checkbox is checked.
 * @param onOriginalTaskChange Invoked when the user toggles the "Original
 * user task" checkbox; receives the new value.
 * @param onChatHistoryChange Invoked when the user toggles the "Chat history"
 * checkbox; receives the new value.
 * @param onLongTermMemoryChange Invoked when the user toggles the "Long-term
 * memory" checkbox; receives the new value.
 * @param onToolResultsChange Invoked when the user toggles the "Tool results"
 * checkbox; receives the new value.
 * @param modifier Modifier applied to the root [Column] of the section.
 * @param inheritedBaseline When non-null (SKILL nodes), each editable row is
 * tagged "inherited" while its value matches the baseline flag and "overridden"
 * once the user diverges from it — the baseline is the skill's own default
 * context. `null` (every other node type) renders no tags.
 */
@Composable
fun NodeContextConfigSection(
    originalTask: Boolean,
    chatHistory: Boolean,
    longTermMemory: Boolean,
    toolResults: Boolean,
    onOriginalTaskChange: (Boolean) -> Unit,
    onChatHistoryChange: (Boolean) -> Unit,
    onLongTermMemoryChange: (Boolean) -> Unit,
    onToolResultsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    inheritedBaseline: NodeContextConfig? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.orchestrator_context_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3, bottom = KnotworkTheme.spacing.sp1),
        )

        ContextFlagRow(
            checked = true,
            enabled = false,
            label = stringResource(R.string.orchestrator_context_previous_node_output_label),
            hint = stringResource(R.string.orchestrator_context_previous_node_output_hint),
            onCheckedChange = null,
        )
        ContextFlagRow(
            checked = originalTask,
            enabled = true,
            label = stringResource(R.string.orchestrator_context_original_task_label),
            hint = stringResource(R.string.orchestrator_context_original_task_hint),
            onCheckedChange = onOriginalTaskChange,
            inheritedValue = inheritedBaseline?.originalTask,
        )
        ContextFlagRow(
            checked = chatHistory,
            enabled = true,
            label = stringResource(R.string.orchestrator_context_chat_history_label),
            hint = stringResource(R.string.orchestrator_context_chat_history_hint),
            onCheckedChange = onChatHistoryChange,
            inheritedValue = inheritedBaseline?.chatHistory,
        )
        ContextFlagRow(
            checked = longTermMemory,
            enabled = true,
            label = stringResource(R.string.orchestrator_context_long_term_memory_label),
            hint = stringResource(R.string.orchestrator_context_long_term_memory_hint),
            onCheckedChange = onLongTermMemoryChange,
            inheritedValue = inheritedBaseline?.longTermMemory,
        )
        ContextFlagRow(
            checked = toolResults,
            enabled = true,
            label = stringResource(R.string.orchestrator_context_tool_results_label),
            hint = stringResource(R.string.orchestrator_context_tool_results_hint),
            onCheckedChange = onToolResultsChange,
            inheritedValue = inheritedBaseline?.toolResults,
        )

        Surface(
            tonalElevation = KnotworkTheme.elevation.el1,
            shape = KnotworkTheme.shapes.sm,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = KnotworkTheme.spacing.sp2),
        ) {
            Text(
                text = stringResource(R.string.orchestrator_context_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(KnotworkTheme.spacing.sp2),
            )
        }
    }
}

/**
 * Single checkbox row used inside [NodeContextConfigSection]. Renders a
 * Material 3 `Checkbox` next to a two-line label/hint column.
 *
 * The whole [Row] is the click target — `Modifier.toggleable` with
 * `Role.Checkbox` flips the flag and announces the row as a single
 * checkbox to TalkBack, so users do not need to aim at the small icon.
 * Passing `null` for [onCheckedChange] disables the toggle and removes
 * the click target entirely; combined with `enabled = false` on the
 * `Checkbox`, this is how the locked-on "Previous node output" row is
 * rendered.
 *
 * @param checked Current value of the flag.
 * @param enabled Whether the user can toggle the flag.
 * @param label Bold primary label shown next to the checkbox.
 * @param hint Subtitle explaining what the flag controls.
 * @param onCheckedChange Invoked with the new value on toggle. Pass `null`
 * for read-only rows; the click target then becomes inert.
 * @param inheritedValue When non-null, the row shows an "inherited" tag while
 * [checked] equals this baseline value and an "overridden" tag otherwise. `null`
 * renders no tag.
 */
@Composable
private fun ContextFlagRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    hint: String,
    onCheckedChange: ((Boolean) -> Unit)?,
    inheritedValue: Boolean? = null,
) {
    val rowModifier = if (onCheckedChange != null && enabled) {
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = ContextRowVerticalPadding)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = ContextRowVerticalPadding)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier,
    ) {
        // Click handling is hoisted onto the Row, so the checkbox itself
        // does not need its own listener. Passing `null` here also lets
        // TalkBack treat the row as a single semantics node.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = KnotworkTheme.spacing.sp1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (inheritedValue != null) {
                    val tagRes = if (checked == inheritedValue) {
                        R.string.orchestrator_context_inherited_tag
                    } else {
                        R.string.orchestrator_context_overridden_tag
                    }
                    Text(
                        text = stringResource(tagRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = KnotworkTheme.spacing.sp1),
                    )
                }
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Off-scale micro-padding for checkbox-row vertical rhythm. The Knotwork
// spacing scale starts at 4 dp (`sp1`); the checkbox-row stack needs a
// tighter 2 dp gap so the five rows pack visually without sliding into
// `Checkbox` touch-target overlap. Captured as a private constant rather
// than an inline `2.dp` so the intent is searchable.
private val ContextRowVerticalPadding = 2.dp
