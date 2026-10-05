@file:Suppress("MatchingDeclarationName") // File hosts ConfirmDialog + its ConfirmDialogUi payload.

package app.knotwork.design.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.theme.KnotworkTheme

/**
 * Everything [ConfirmDialog] renders.
 *
 * @property title One line naming what is about to happen.
 * @property body The sentence saying what it costs. Blank renders no body,
 *   which is right for a question whose title already asks it in full.
 * @property confirmLabel The affirmative CTA. A verb, not "OK": a dialog whose
 *   buttons read "OK / Cancel" makes the reader re-read the title to find out
 *   what OK agrees to.
 * @property cancelLabel The dismiss CTA.
 * @property destructive Whether confirming destroys something. Tints the
 *   confirm CTA with the error colour — the one signal that separates "delete
 *   this conversation" from "clear the console". Rendered by the design
 *   system's own text button, which owns what destructive looks like.
 */
data class ConfirmDialogUi(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val destructive: Boolean = false,
)

/**
 * A dialog that asks one yes-or-no question.
 *
 * This shape had been written out eight separate times across the application —
 * clearing the console, deleting a conversation, deleting a pipeline, removing
 * a connection, leaving onboarding, resetting usage statistics. None of the
 * eight had a baseline, and they had already drifted: some tinted the
 * destructive action, some did not, so "this deletes something" was a signal
 * the user could only sometimes rely on.
 *
 * A question whose answer needs more than one sentence — a count, an estimate in
 * mono, an inset — passes [content] instead of a body. It scrolls, and the two
 * buttons stay where they are, at any font scale.
 *
 * @param ui Resolved copy.
 * @param onConfirm The affirmative CTA was tapped.
 * @param onDismiss Cancel, or the scrim.
 * @param modifier Optional layout modifier applied to the dialog.
 * @param content The body, drawn instead of [ConfirmDialogUi.body] when given.
 */
@Composable
fun ConfirmDialog(
    ui: ConfirmDialogUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(ui.title) },
        text = when {
            content != null -> (
                {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        content = content,
                    )
                }
                )
            ui.body.isBlank() -> null
            else -> ({ Text(ui.body) })
        },
        // The design system's text button, not Material's: it already owns what
        // "destructive" looks like, and the sites this replaced were split
        // between the two — which is how the tint became unreliable.
        confirmButton = {
            KnotworkTextButton(text = ui.confirmLabel, destructive = ui.destructive, onClick = onConfirm)
        },
        dismissButton = { KnotworkTextButton(text = ui.cancelLabel, onClick = onDismiss) },
    )
}
