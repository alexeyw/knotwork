package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.report.ContentReportReason
import app.knotwork.design.screens.chat.ReportReasonOptionUi
import app.knotwork.design.screens.chat.ReportResponseDialogUi
import app.knotwork.design.screens.chat.ReportResponseDialog as CatalogReportResponseDialog

/**
 * `:app` binding of the catalog's content-report dialog: resolves the copy and
 * maps [ContentReportReason] to and from the catalog's opaque option ids.
 *
 * The dialog itself lives in `:catalog` so it can be photographed. It is the
 * app's only in-product reporting surface, and it had no baseline at all.
 *
 * @param reason Currently selected category.
 * @param onReasonChange Invoked when the user picks a different category.
 * @param note Free-text detail typed by the user.
 * @param onNoteChange Invoked on every edit of the note.
 * @param onCopy Copies the rendered report to the clipboard.
 * @param onOpenIssue Opens the public issue tracker with the report prefilled.
 * @param onDismiss Closes the dialog without reporting anything.
 */
@Composable
internal fun ReportResponseDialog(
    reason: ContentReportReason,
    onReasonChange: (ContentReportReason) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onCopy: () -> Unit,
    onOpenIssue: () -> Unit,
    onDismiss: () -> Unit,
) {
    CatalogReportResponseDialog(
        ui = ReportResponseDialogUi(
            title = stringResource(R.string.chat_report_dialog_title),
            body = stringResource(R.string.chat_report_dialog_body),
            reasons = ContentReportReason.entries.map { entry ->
                ReportReasonOptionUi(id = entry.name, label = stringResource(entry.labelRes()))
            },
            notePlaceholder = stringResource(R.string.chat_report_dialog_note_placeholder),
            disclosure = stringResource(R.string.chat_report_dialog_disclosure),
            copyLabel = stringResource(R.string.chat_report_dialog_copy),
            openIssueLabel = stringResource(R.string.chat_report_dialog_open_issue),
            cancelLabel = stringResource(R.string.chat_report_dialog_cancel),
        ),
        selectedReasonId = reason.name,
        onReasonSelected = { id ->
            // The id is the enum's own name, so an unknown value can only mean
            // the two lists have drifted; OTHER is the bucket that admits it
            // rather than guessing a category on the user's behalf.
            onReasonChange(ContentReportReason.entries.firstOrNull { it.name == id } ?: ContentReportReason.OTHER)
        },
        note = note,
        onNoteChange = onNoteChange,
        onCopy = onCopy,
        onOpenIssue = onOpenIssue,
        onDismiss = onDismiss,
    )
}

/**
 * Maps a report category to its localized chip label.
 *
 * Kept next to the binding rather than on the enum so the `domain` layer stays
 * free of Android resource identifiers.
 *
 * @return String resource for this category.
 */
private fun ContentReportReason.labelRes(): Int = when (this) {
    ContentReportReason.HARMFUL_OR_UNSAFE -> R.string.chat_report_reason_harmful
    ContentReportReason.SEXUALLY_EXPLICIT -> R.string.chat_report_reason_sexual
    ContentReportReason.HATE_OR_HARASSMENT -> R.string.chat_report_reason_hate
    ContentReportReason.MISLEADING -> R.string.chat_report_reason_misleading
    ContentReportReason.OTHER -> R.string.chat_report_reason_other
}
