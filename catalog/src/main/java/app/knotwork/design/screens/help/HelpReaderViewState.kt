package app.knotwork.design.screens.help

import androidx.compose.runtime.Immutable

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
