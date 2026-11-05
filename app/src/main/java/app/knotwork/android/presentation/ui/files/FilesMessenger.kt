package app.knotwork.android.presentation.ui.files

import android.content.Context
import androidx.annotation.StringRes
import app.knotwork.android.R
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.ui.common.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Everything the Files screen says to the user when an operation does not go
 * through.
 *
 * Its own class rather than a pair of ViewModel helpers for two reasons. The
 * outcome it reports is produced in two places — most failures inside
 * [FilesViewModel], but share staging inside `FilesScreen`, which performs the
 * staging itself because it needs a `Context` and a `FileProvider` — so a single
 * owner keeps the wording and the routing in one file. And the routing has a
 * rule worth stating once: these messages go to the activity-level
 * [TransientMessageRelay] rather than a screen-local host, because the host that
 * renders them lives above the NavGraph and a message therefore survives the
 * screen navigating away from under it.
 *
 * @property relay Activity-level one-shot snackbar bus.
 * @property context Application context, used only to resolve the copy.
 */
class FilesMessenger @Inject constructor(
    private val relay: TransientMessageRelay,
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Surfaces a failed file operation.
     *
     * Every one of these paths used to raise a `FilesEvent.ShowMessage` that
     * `FilesScreen` swallowed in an empty `when` branch, so eight distinct
     * failures — import, export, preview, partial delete — reached nobody.
     *
     * @param message String resource for the sentence the user reads.
     */
    fun failure(@StringRes message: Int) {
        relay.post(UiText.Dynamic(context.getString(message)))
    }

    /**
     * Reports the outcome of staging files for the system share sheet.
     *
     * Staging silently drops a file it cannot write, so a partial failure opened
     * the share sheet carrying fewer files than were selected, and a total
     * failure returned without opening anything at all — a tap on Share that did
     * visibly nothing. Saying nothing is correct only when every file was
     * staged.
     *
     * @param staged How many of the selected files were written successfully.
     * @param requested How many files the user selected.
     */
    fun shareStaged(staged: Int, requested: Int) {
        when {
            staged == requested -> Unit
            staged == 0 -> failure(R.string.files_message_share_failed)
            else -> relay.post(
                UiText.Dynamic(
                    context.resources.getQuantityString(
                        R.plurals.files_message_share_partial,
                        requested,
                        staged,
                        requested,
                    ),
                ),
            )
        }
    }
}
