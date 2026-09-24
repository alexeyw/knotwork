package app.knotwork.design.screens.chat

/**
 * Preview fixtures for the content-report dialog.
 *
 * The five categories mirror the ones the application offers. A shorter list
 * would photograph a chip row that never wraps, and wrapping is exactly what
 * `FlowRow` is there to handle.
 */
object ReportResponseDialogPreview {

    /** The dialog as it opens, with the neutral category selected. */
    fun dialog(): ReportResponseDialogUi = ReportResponseDialogUi(
        title = "Report this response",
        body = "Tell me what is wrong with what the model produced. This helps improve the pipelines and " +
            "prompts that ship with the app.",
        reasons = listOf(
            ReportReasonOptionUi(id = "HARMFUL_OR_UNSAFE", label = "Harmful or unsafe"),
            ReportReasonOptionUi(id = "SEXUALLY_EXPLICIT", label = "Sexually explicit"),
            ReportReasonOptionUi(id = "HATE_OR_HARASSMENT", label = "Hate or harassment"),
            ReportReasonOptionUi(id = "MISLEADING", label = "Misleading"),
            ReportReasonOptionUi(id = "OTHER", label = "Something else"),
        ),
        notePlaceholder = "What happened? (optional)",
        disclosure = "Nothing is sent automatically. Open issue puts this report in a GitHub link, so GitHub " +
            "receives it when the page opens; it goes public only if you submit it. To edit it first, copy it instead.",
        copyLabel = "Copy report",
        openIssueLabel = "Open an issue",
        cancelLabel = "Cancel",
    )
}
