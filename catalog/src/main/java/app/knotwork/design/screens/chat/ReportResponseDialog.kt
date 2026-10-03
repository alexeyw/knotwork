@file:Suppress("MatchingDeclarationName") // File hosts ReportResponseDialog + its option and view-state payloads.

package app.knotwork.design.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.KnotworkFilterChip
import app.knotwork.design.components.controls.KnotworkTextArea
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * One selectable report category.
 *
 * @property id Opaque identifier handed back on selection; `:app` maps it to
 *   its own reason type.
 * @property label Resolved, translated label.
 */
data class ReportReasonOptionUi(val id: String, val label: String)

/**
 * Everything [ReportResponseDialog] renders.
 *
 * @property title Dialog title.
 * @property body The sentence explaining what the report is for.
 * @property reasons Selectable categories, in display order.
 * @property notePlaceholder Placeholder of the free-text area.
 * @property disclosure The line stating that nothing is transmitted from here.
 *   Load-bearing copy, not decoration: an app whose privacy claim is "no server
 *   of mine" has to say so on the one surface that looks like it might have one.
 * @property copyLabel Label of the copy-to-clipboard button.
 * @property openIssueLabel Confirm CTA, opening the tracker prefilled.
 * @property cancelLabel Dismiss CTA.
 */
data class ReportResponseDialogUi(
    val title: String,
    val body: String,
    val reasons: List<ReportReasonOptionUi>,
    val notePlaceholder: String,
    val disclosure: String,
    val copyLabel: String,
    val openIssueLabel: String,
    val cancelLabel: String,
)

/**
 * Dialog collecting a user's flag against a model-authored message.
 *
 * Exists because an app that generates content with a model has to let the user
 * report that content **from inside the app**. It collects a category and the
 * user's own words, then hands the finished report back to the caller, which
 * owns the two delivery routes. Nothing is transmitted by the dialog itself,
 * and [ReportResponseDialogUi.disclosure] says so on screen.
 *
 * Stateless: every value is hoisted, so the dialog re-renders from arguments.
 *
 * @param ui Resolved copy and the category options.
 * @param selectedReasonId Currently selected category.
 * @param onReasonSelected A category chip was tapped.
 * @param note Free-text detail typed by the user.
 * @param onNoteChange The note was edited.
 * @param onCopy Copy the rendered report to the clipboard.
 * @param onOpenIssue Open the public tracker with the report prefilled.
 * @param onDismiss Close without reporting.
 * @param modifier Optional layout modifier applied to the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportResponseDialog(
    ui: ReportResponseDialogUi,
    selectedReasonId: String,
    onReasonSelected: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onCopy: () -> Unit,
    onOpenIssue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(ui.title) },
        text = {
            ReportResponseDialogBody(
                ui = ui,
                selectedReasonId = selectedReasonId,
                onReasonSelected = onReasonSelected,
                note = note,
                onNoteChange = onNoteChange,
                onCopy = onCopy,
            )
        },
        confirmButton = { KnotworkTextButton(text = ui.openIssueLabel, onClick = onOpenIssue) },
        dismissButton = { KnotworkTextButton(text = ui.cancelLabel, onClick = onDismiss) },
    )
}

/**
 * The dialog's body, without the `AlertDialog` around it.
 *
 * Split for the reason `SaveAsPresetDialogBody` records: the text area inside a
 * dialog never lets the harness reach idle, so this is what a baseline can
 * photograph.
 *
 * @param ui Resolved copy and the category options.
 * @param selectedReasonId Currently selected category.
 * @param onReasonSelected A category chip was tapped.
 * @param note Free-text detail.
 * @param onNoteChange The note was edited.
 * @param onCopy Copy the rendered report.
 * @param modifier Optional layout modifier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportResponseDialogBody(
    ui: ReportResponseDialogUi,
    selectedReasonId: String,
    onReasonSelected: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(text = ui.body, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            ui.reasons.forEach { option ->
                KnotworkFilterChip(
                    label = option.label,
                    selected = option.id == selectedReasonId,
                    onClick = { onReasonSelected(option.id) },
                )
            }
        }
        KnotworkTextArea(
            value = note,
            onValueChange = onNoteChange,
            placeholder = ui.notePlaceholder,
            monospace = false,
            highlightVariables = false,
            minLines = 2,
            maxLines = 5,
        )
        Text(text = ui.disclosure, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurfaceDim)
        // "Copy report" lives in the body rather than in a button slot because
        // the two slots are spoken for: a dialog raised from a long-press menu
        // needs a visible way out, and "tap outside" is not one for anyone
        // driving the screen with a reader.
        KnotworkSecondaryButton(text = ui.copyLabel, onClick = onCopy, modifier = Modifier.fillMaxWidth())
    }
}
