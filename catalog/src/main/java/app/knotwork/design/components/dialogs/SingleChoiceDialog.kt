@file:Suppress("MatchingDeclarationName") // File hosts SingleChoiceDialog + its option and view-state payloads.

package app.knotwork.design.components.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text

/** Vertical padding of one choice row. Sized to clear the 48 dp touch minimum with the radio. */
private val RowVerticalPadding = 8.dp

/** Gap between a radio and its label. */
private val RadioLabelGap = 8.dp

/**
 * One selectable option.
 *
 * @property id Opaque identifier handed back on selection; `null` is reserved
 *   for the "none" row, which is a real choice rather than the absence of one.
 * @property label Resolved, translated label.
 */
data class SingleChoiceOptionUi(val id: String?, val label: String)

/**
 * Everything [SingleChoiceDialog] renders.
 *
 * @property title Dialog title.
 * @property options Selectable rows, in display order, including any "none".
 * @property selectedId Currently selected id, or `null` for the "none" row.
 * @property cancelLabel Dismiss CTA.
 */
data class SingleChoiceDialogUi(
    val title: String,
    val options: List<SingleChoiceOptionUi>,
    val selectedId: String?,
    val cancelLabel: String,
)

/**
 * A dialog that picks exactly one option from a radio list.
 *
 * There is no confirm button, and that is the design rather than an omission:
 * a radio list commits on tap, so a second confirmation would ask the user to
 * agree with something they just did. Dismiss is the only action, and it is
 * present because "tap outside" is not a discoverable way out for anyone
 * driving the screen with a reader.
 *
 * The list scrolls, because the caller does not control how many options exist.
 *
 * @param ui Resolved copy and the options.
 * @param onSelect An option was tapped; `null` for the "none" row.
 * @param onDismiss Cancel or scrim tap.
 * @param modifier Optional layout modifier applied to the dialog.
 */
@Composable
fun SingleChoiceDialog(
    ui: SingleChoiceDialogUi,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(ui.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ui.options.forEach { option ->
                    ChoiceRow(
                        label = option.label,
                        selected = option.id == ui.selectedId,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(ui.cancelLabel) } },
    )
}

/**
 * One selectable radio row.
 *
 * The whole row is `selectable`, not just the radio: a 20 dp target is below the
 * 48 dp minimum, and the label is the part a user aims at.
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = RowVerticalPadding),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier.padding(start = RadioLabelGap),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
