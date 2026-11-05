package app.knotwork.android.presentation.ui.chat.archive

import app.knotwork.android.presentation.ui.common.UiText

/**
 * One archived chat as the ViewModel models it — the presentation-layer twin of
 * the catalog `ChatArchiveRowUi`, holding the *bucket* rather than the
 * formatted label so the localisation stays in the composable.
 *
 * @property id session id.
 * @property title chat display name as stored; blank for a chat never named, which the
 *   screen words in the interface language.
 * @property archivedAt bucket describing how long ago the user archived it.
 * @property starred whether the chat is favorited.
 * @property ranAfterArchiving whether the session was written to *after* being
 *   archived — i.e. a background run settled while it was put away. Surfaced so
 *   a chat that changed on its own never looks like it did not.
 */
data class ChatArchiveRow(
    val id: String,
    val title: String,
    val archivedAt: ArchivedAtLabel,
    val starred: Boolean,
    val ranAfterArchiving: Boolean,
)

/**
 * Aggregated state of the chat-archive screen.
 *
 * @property loading `true` until the first emission of the archived-sessions
 *   flow lands — the screen shows skeleton rows rather than flashing the
 *   teaching empty state for a frame on every open.
 * @property rows archived chats, most-recently-archived first (the repository
 *   flow already orders them).
 * @property errorMessage read failure — the store's own text, or the app's sentence
 *   when it has none — or `null`. Non-null wins over
 *   [loading] and [rows] in the visual mapping.
 * @property openMenuRowId id of the row whose overflow menu is open.
 * @property deleteTargetId id of the row awaiting delete-forever confirmation.
 */
data class ChatArchiveUiState(
    val loading: Boolean = true,
    val rows: List<ChatArchiveRow> = emptyList(),
    val errorMessage: UiText? = null,
    val openMenuRowId: String? = null,
    val deleteTargetId: String? = null,
)
