package app.knotwork.android.presentation.ui.help

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.presentation.ui.common.documentationUrl
import app.knotwork.android.presentation.ui.common.openDocumentationInBrowser
import app.knotwork.design.screens.help.HelpDelivery
import app.knotwork.design.screens.help.HelpDocument
import app.knotwork.design.screens.help.HelpListCallbacks
import app.knotwork.design.screens.help.HelpListContent
import app.knotwork.design.screens.help.HelpListViewState
import app.knotwork.design.screens.help.HelpRefusal
import java.text.NumberFormat

/**
 * The Help screen: every document the app can open.
 *
 * @param onOpenDocument Navigates to the reader for a bundled document.
 * @param modifier Layout modifier applied to the screen.
 * @param viewModel Screen's view model.
 */
@Composable
fun HelpScreen(
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val refusedEntry = uiState.refusedDocumentId?.let { DocumentationLinks.byId(it) }
    val alternativeId = viewModel.alternativeDocumentId()

    HelpListContent(
        state = HelpListViewState(
            title = stringResource(R.string.help_title),
            subtitle = pluralStringResource(
                R.plurals.help_subtitle,
                uiState.documents.size,
                uiState.documents.size,
                uiState.documents.count { it.delivery == DocumentationLinks.Delivery.BUNDLED },
            ),
            documents = uiState.documents.map { it.toRow() },
            refusedDocumentId = uiState.refusedDocumentId,
            refusal = refusedEntry?.let { entry ->
                HelpRefusal(
                    title = stringResource(R.string.help_offline_title),
                    body = stringResource(R.string.help_offline_body, entry.id.titleOf()),
                    copyLabel = stringResource(R.string.help_offline_copy),
                    alternativeLabel = alternativeId?.let { stringResource(R.string.help_offline_alternative) },
                )
            },
        ),
        strings = helpStrings(),
        callbacks = HelpListCallbacks(
            onOpen = { id ->
                viewModel.onDocumentClick(
                    id = id,
                    onOpenReader = onOpenDocument,
                    onOpenBrowser = { openDocumentationInBrowser(context, it) },
                )
            },
            onDismissRefusal = viewModel::dismissRefusal,
            onCopyLink = {
                refusedEntry?.let { context.copyDocumentationLink(documentationUrl(it)) }
                viewModel.dismissRefusal()
            },
            onOpenAlternative = {
                viewModel.dismissRefusal()
                alternativeId?.let(onOpenDocument)
            },
        ),
        modifier = modifier,
    )
}

/**
 * Maps a registry entry onto the row the catalog draws.
 *
 * @return The row, with its label, description and mono state resolved for this
 *   locale.
 */
@Composable
private fun DocumentationLinks.Entry.toRow(): HelpDocument {
    val bundled = delivery == DocumentationLinks.Delivery.BUNDLED
    val measure = stringResource(measureRes(), NumberFormat.getInstance().format(measureCount()))
    return HelpDocument(
        id = id,
        label = id.titleOf(),
        description = stringResource(descriptionRes()),
        state = stringResource(
            if (bundled) R.string.help_state_on_device else R.string.help_state_in_browser,
            measure,
        ),
        delivery = if (bundled) HelpDelivery.ON_DEVICE else HelpDelivery.IN_BROWSER,
    )
}

/**
 * Resolves a document's display name.
 *
 * @return The localized title.
 */
@Composable
private fun String.titleOf(): String = helpDocumentTitle(this)

/**
 * Resolves a document's one-line description.
 *
 * @return The description's resource id.
 */
private fun DocumentationLinks.Entry.descriptionRes(): Int = when (id) {
    DocumentationLinks.ID_TROUBLESHOOTING -> R.string.help_doc_troubleshooting_desc
    DocumentationLinks.ID_FAQ -> R.string.help_doc_faq_desc
    DocumentationLinks.ID_USER_GUIDE -> R.string.help_doc_user_guide_desc
    DocumentationLinks.ID_COOKBOOK -> R.string.help_doc_cookbook_desc
    else -> R.string.help_doc_external_automation_desc
}

/**
 * Chooses the noun a document's size is counted in.
 *
 * A bundled document is counted in what its reader came for — problems in the
 * troubleshooting guide, sections in the FAQ — because that reader is choosing
 * between two documents they can open right now. A document on the web is
 * counted in lines, which says "this is long" without pretending to know what
 * is in it.
 *
 * @return The measure string's resource id.
 */
private fun DocumentationLinks.Entry.measureRes(): Int = when {
    id == DocumentationLinks.ID_TROUBLESHOOTING -> R.string.help_measure_problems
    delivery == DocumentationLinks.Delivery.BUNDLED -> R.string.help_measure_sections
    else -> R.string.help_measure_lines
}

/**
 * The number the measure counts.
 *
 * @return The section count for a bundled document, the line count otherwise.
 */
private fun DocumentationLinks.Entry.measureCount(): Int =
    if (delivery == DocumentationLinks.Delivery.BUNDLED) sectionCount else lineCount
