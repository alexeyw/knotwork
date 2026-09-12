package app.knotwork.android.presentation.ui.help

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import app.knotwork.android.R
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.design.screens.help.HelpStrings

/**
 * Resolves the Help surfaces' copy for this locale.
 *
 * One factory rather than a literal at each call site: the list and the reader
 * share most of it, and a refusal that read one way on one screen and another
 * way on the next would be two answers to the same question.
 *
 * @return The strings both Help surfaces draw.
 */
@Composable
fun helpStrings(): HelpStrings = HelpStrings(
    readerAction = stringResource(R.string.help_reader_action),
    loadingNote = stringResource(R.string.help_reader_loading),
    anchorNote = stringResource(R.string.help_reader_anchor),
    errorTitle = stringResource(R.string.help_reader_error_title),
    errorBody = stringResource(R.string.help_reader_error_body),
    errorPrimary = stringResource(R.string.help_reader_error_primary),
    errorSecondary = stringResource(R.string.help_reader_error_secondary),
    offlineBarText = stringResource(R.string.help_offline_bar),
    offlineBarCopy = stringResource(R.string.help_offline_copy),
    opensInBrowser = stringResource(R.string.help_opens_in_browser),
    backDescription = stringResource(R.string.help_back),
)

/**
 * Resolves a document's display name from its registry id.
 *
 * One function rather than a `when` at each call site: the list, the reader's
 * bar and the offline refusal all name the same documents, and three copies of
 * the mapping would drift the day a document is renamed.
 *
 * @param id One of the `DocumentationLinks.ID_*` constants.
 * @return The localized title.
 */
@Composable
fun helpDocumentTitle(id: String): String = stringResource(
    when (id) {
        DocumentationLinks.ID_TROUBLESHOOTING -> R.string.help_doc_troubleshooting_title
        DocumentationLinks.ID_FAQ -> R.string.help_doc_faq_title
        DocumentationLinks.ID_USER_GUIDE -> R.string.help_doc_user_guide_title
        DocumentationLinks.ID_COOKBOOK -> R.string.help_doc_cookbook_title
        else -> R.string.help_doc_external_automation_title
    },
)

/**
 * Copies a documentation address to the clipboard.
 *
 * Offered wherever a link is refused for want of a network, so the address
 * survives until there is one.
 *
 * @param url The address to copy.
 */
fun Context.copyDocumentationLink(url: String) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(url, url))
}
