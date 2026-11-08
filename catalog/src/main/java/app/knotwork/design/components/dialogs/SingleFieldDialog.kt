@file:Suppress("MatchingDeclarationName") // File hosts SingleFieldDialog + its SingleFieldDialogUi payload.

package app.knotwork.design.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.text.Text

/**
 * Everything [SingleFieldDialog] renders.
 *
 * @property title Dialog title.
 * @property label Label of the one field.
 * @property initialValue Value the field opens with.
 * @property confirmLabel Confirm CTA.
 * @property cancelLabel Dismiss CTA.
 */
data class SingleFieldDialogUi(
    val title: String,
    val label: String,
    val initialValue: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

/**
 * A dialog that asks for exactly one non-blank string.
 *
 * Renaming a preset and naming a pipeline are the same interaction, and they
 * were two components until this one replaced both. That mattered less for the
 * duplication than for what the duplication hid: one of the two had a baseline
 * and the other did not, so the same shape was verified in one place and
 * unverified in the other.
 *
 * The dialog owns its field state, because a text field that round-trips every
 * keystroke through the caller is the shape that makes typing feel laggy.
 * Confirm is gated on a non-blank, trimmed value — a rule the callers all had
 * separately and now cannot disagree about.
 *
 * @param ui Resolved copy and the initial value.
 * @param onDismiss Cancel or scrim tap.
 * @param onConfirm Confirmed, with the value as typed (untrimmed: the host owns
 *   what trimming means for its own field).
 * @param modifier Optional layout modifier applied to the dialog.
 */
@Composable
fun SingleFieldDialog(
    ui: SingleFieldDialogUi,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember(ui.initialValue) { mutableStateOf(ui.initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(ui.title) },
        text = { SingleFieldDialogBody(ui = ui, value = value, onValueChange = { value = it }) },
        confirmButton = {
            TextButton(enabled = value.trim().isNotEmpty(), onClick = { onConfirm(value) }) {
                Text(ui.confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ui.cancelLabel) } },
    )
}

/**
 * The dialog's body, without the `AlertDialog` around it.
 *
 * Split because a text field inside an `AlertDialog` never lets the Robolectric
 * harness reach idle — `setContent` spins until Espresso raises
 * `AppNotIdleException`. Measured against the v2 compose rule and against a
 * clock frozen before `setContent`; neither helps, because the host is what
 * never settles rather than the field. `NodeConfigSheet` carries a body for the
 * same class of reason, `ModalBottomSheet` being the host there.
 *
 * @param ui Resolved copy.
 * @param value Current field value.
 * @param onValueChange The field was edited.
 * @param modifier Optional layout modifier.
 */
@Composable
fun SingleFieldDialogBody(
    ui: SingleFieldDialogUi,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No test tag on the field. The one the pipeline-name dialog used to carry
    // was referenced by nothing, and a tag here would not help anyway: it lands
    // on the decoration box, while text actions need the editable node beneath
    // it. Tests reach that node with `hasSetTextAction()`.
    KnotworkField(label = ui.label, modifier = modifier) {
        KnotworkTextField(value = value, onValueChange = onValueChange)
    }
}
