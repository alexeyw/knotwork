package app.knotwork.design.screens.help

import androidx.compose.runtime.Immutable

/**
 * Where a document is read from, and therefore what tapping its row does.
 *
 * Mirrors the registry field the build owns. The catalog keeps its own copy so
 * the screen can be previewed and snapshotted without the app module, and
 * nothing in the layout hard-codes which documents are bundled: a document that
 * changes side changes one mark and one word.
 */
enum class HelpDelivery {

    /** Ships in the app and opens in the reader, with or without a network. */
    ON_DEVICE,

    /** Lives on the web and hands off to the browser. */
    IN_BROWSER,
}

/**
 * One row of the Help list.
 *
 * @property id Registry id, echoed back by the row's callback.
 * @property label The document's name.
 * @property description One line of what is inside. Resident rather than
 *   summoned by a glyph — this is the only list in the app whose rows have
 *   nothing else to say, and a document nobody can describe is a document
 *   nobody opens.
 * @property state The mono slot: delivery first, then size — `on this device ·
 *   11 problems`. Small muted mono says what a row *is*; it never says what it
 *   means.
 * @property delivery Whether following this row leaves the app.
 */
@Immutable
data class HelpDocument(
    val id: String,
    val label: String,
    val description: String,
    val state: String,
    val delivery: HelpDelivery,
)

/**
 * State of the Help list.
 *
 * @property title Screen title.
 * @property subtitle The mono count under it.
 * @property documents Every document, in the order the registry lists them —
 *   by how likely a reader is to need one while something is broken, not
 *   alphabetically and not by delivery.
 * @property refusedDocumentId Id of the row whose offline refusal is open, or
 *   `null`. One at a time: the panel answers the row that was tapped.
 * @property refusal The open refusal's copy, or `null` when none is open.
 */
@Immutable
data class HelpListViewState(
    val title: String,
    val subtitle: String,
    val documents: List<HelpDocument>,
    val refusedDocumentId: String? = null,
    val refusal: HelpRefusal? = null,
)

/**
 * The copy of an offline refusal.
 *
 * A refusal is not an apology. It names what is missing in one clause, then
 * offers a verb reachable *without* the thing that is missing — which is why
 * the body ends by naming the documents that do open, and why neither action
 * needs a network.
 *
 * @property title What is wrong, in one clause.
 * @property body Why this document cannot open, and what can.
 * @property copyLabel Label of the copy-the-address action.
 * @property alternativeLabel Label of the action that opens a bundled document
 *   instead, or `null` when there is none to offer.
 */
@Immutable
data class HelpRefusal(val title: String, val body: String, val copyLabel: String, val alternativeLabel: String?)

/**
 * What the reader is showing.
 */
enum class HelpReaderVisualState {

    /** The document is being read and parsed off the main thread. */
    LOADING,

    /** The document is on screen. */
    CONTENT,

    /** The copy shipped with the app could not be read. */
    ERROR,
}

/**
 * State of the document reader.
 *
 * @property title The document's name, shown in the bar.
 * @property fileName The source file name, mono, under the title.
 * @property markdown The document's text, empty until it is loaded.
 * @property visualState Which of the three frames to draw.
 * @property anchorOffset Character offset the reader should scroll to, or
 *   `null` to open at the top.
 * @property anchorMarked Whether the arrival mark is still showing. It outlives
 *   the scroll and is dismissed by the first gesture — a pulse would be over
 *   before the eye arrives, and under reduced motion it would be no signal at
 *   all.
 * @property offlineBarVisible Whether a body link was refused for want of a
 *   network. Anchored at the bottom so the reader keeps their place.
 */
@Immutable
data class HelpReaderViewState(
    val title: String,
    val fileName: String,
    val markdown: String = "",
    val visualState: HelpReaderVisualState = HelpReaderVisualState.LOADING,
    val anchorOffset: Int? = null,
    val anchorMarked: Boolean = false,
    val offlineBarVisible: Boolean = false,
)

/**
 * Every string the Help surfaces draw, supplied by the app's resources.
 *
 * @property readerAction Label of the open-in-browser action.
 * @property loadingNote Mono note under the loading skeleton.
 * @property anchorNote The arrival mark's one word.
 * @property errorTitle Title of the unreadable-copy frame.
 * @property errorBody Its body: the fix before the fallback, so the browser is
 *   not presented as the answer to a broken install.
 * @property errorPrimary Label of the browser fallback.
 * @property errorSecondary Label of the retry.
 * @property offlineBarText The in-body refusal.
 * @property offlineBarCopy Label of its copy action.
 * @property opensInBrowser What a screen reader announces after the name of a
 *   row that leaves the app.
 * @property backDescription Content description of the reader's back
 *   affordance.
 */
@Immutable
data class HelpStrings(
    val readerAction: String,
    val loadingNote: String,
    val anchorNote: String,
    val errorTitle: String,
    val errorBody: String,
    val errorPrimary: String,
    val errorSecondary: String,
    val offlineBarText: String,
    val offlineBarCopy: String,
    val opensInBrowser: String,
    val backDescription: String,
)

/**
 * Callbacks the Help list raises.
 *
 * @property onOpen A row was tapped.
 * @property onDismissRefusal The open refusal was dismissed.
 * @property onCopyLink The refused document's address was copied.
 * @property onOpenAlternative The offered bundled document was chosen.
 */
@Immutable
data class HelpListCallbacks(
    val onOpen: (String) -> Unit = {},
    val onDismissRefusal: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onOpenAlternative: () -> Unit = {},
)

/**
 * Callbacks the reader raises.
 *
 * @property onBack The back affordance was used.
 * @property onOpenInBrowser The bar's one action was used.
 * @property onLinkClick A link in the body was followed; carries the raw target
 *   exactly as the Markdown wrote it.
 * @property onRetry The unreadable copy should be read again.
 * @property onScrolled The reader scrolled, which dismisses the arrival mark.
 * @property onCopyLink The refused link's address was copied.
 * @property onDismissOfflineBar The in-body refusal was dismissed.
 */
@Immutable
data class HelpReaderCallbacks(
    val onBack: () -> Unit = {},
    val onOpenInBrowser: () -> Unit = {},
    val onLinkClick: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onScrolled: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onDismissOfflineBar: () -> Unit = {},
)
