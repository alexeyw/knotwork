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
