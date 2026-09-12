package app.knotwork.design.screens.help

import androidx.compose.runtime.Immutable

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
