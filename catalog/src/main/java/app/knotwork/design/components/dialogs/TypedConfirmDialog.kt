package app.knotwork.design.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Single source of truth for the typed-confirm matching rule: the destructive
 * confirm button arms only when the typed [input] equals [keyword] after
 * trimming, case-insensitively. Exposed so ViewModels re-validating a confirm
 * action apply exactly the same rule as the dialog's enabled-state gate.
 *
 * @param input Live text of the typed-confirm field.
 * @param keyword Token the user must type.
 * @return `true` when the input arms the destructive action.
 */
fun typedConfirmMatches(input: String, keyword: String): Boolean = input.trim().equals(keyword, ignoreCase = true)

/**
 * Payload of a destructive typed-confirm dialog: the user must type [keyword]
 * into a text field before the confirm button arms.
 *
 * @property title Localized dialog headline.
 * @property body Localized warning text describing the irreversible consequence.
 * @property keyword Token the user must type to arm the confirm button.
 * @property hint Placeholder shown inside the typed-confirm field.
 * @property pendingInput Current value of the typed-confirm field.
 */
data class TypedConfirmDialogState(
    val title: String,
    val body: String,
    val keyword: String,
    val hint: String,
    val pendingInput: String,
)

/**
 * Canonical destructive typed-confirm dialog: title, warning body, a
 * single-line input gated by [typedConfirmMatches], a destructive-primary
 * confirm button, and a plain-text dismiss button. Used by every surface that
 * stages an irreversible action (Settings destructive actions, the splash
 * data-recovery wipe) so the interaction pattern cannot drift between screens.
 *
 * @param state Dialog payload (texts + live typed input).
 * @param confirmLabel Localized label of the destructive confirm button.
 * @param cancelLabel Localized label of the dismiss button.
 * @param onInputChange Live text change in the typed-confirm field.
 * @param onConfirm Invoked on confirm tap; only tappable while the input matches.
 * @param onCancel Invoked on dismiss (button or outside tap).
 * @param fieldTestTag Test tag applied to the typed-confirm text field.
 * @param confirmTestTag Test tag applied to the confirm button.
 */
@Composable
fun TypedConfirmDialog(
    state: TypedConfirmDialogState,
    confirmLabel: String,
    cancelLabel: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    fieldTestTag: String,
    confirmTestTag: String,
) {
    val canConfirm = typedConfirmMatches(input = state.pendingInput, keyword = state.keyword)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                Text(text = state.body, style = KnotworkTextStyles.BodyBase)
                OutlinedTextField(
                    value = state.pendingInput,
                    onValueChange = onInputChange,
                    placeholder = { Text(state.hint, style = KnotworkTextStyles.BodySm) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(fieldTestTag),
                )
            }
        },
        confirmButton = {
            KnotworkPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.testTag(confirmTestTag),
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = cancelLabel)
            }
        },
    )
}
