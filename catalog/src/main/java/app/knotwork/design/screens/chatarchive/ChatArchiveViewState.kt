package app.knotwork.design.screens.chatarchive

/**
 * Visual variant of the chat-archive surface.
 */
enum class ChatArchiveVisualState {
    /** Initial fetch in progress — skeleton rows. */
    Loading,

    /** Normal list of archived chats. */
    Default,

    /** Nothing archived — the teaching empty state (no CTA; see [ChatArchiveStrings]). */
    Empty,

    /** The archived list could not be read — message + retry CTA. */
    Error,
}

/**
 * One archived chat.
 *
 * @property id stable session id; the `LazyColumn` key and the argument every
 *   callback carries.
 * @property title chat display name (1 line, ellipsis).
 * @property archivedLabel pre-formatted **relative archived-at** label
 *   ("Archived 2 h ago"). Relative, and naming when the user *put the chat
 *   away* — not when it was last active. On this screen the question is "when
 *   did I archive this", and at archive time-scales (hours → weeks) relative is
 *   the readable form; the drawer's absolute `EEE HH:mm` is for a list scanned
 *   several times a day.
 * @property starred whether the chat is favorited — renders the same small
 *   leading star the drawer row uses.
 * @property ranAfterArchiving `true` when a background run settled *after* the
 *   user archived the chat (its last activity post-dates the archive instant).
 *   Renders the "Run finished after archiving" note so the user is never
 *   surprised by a chat that changed while it was put away.
 */
data class ChatArchiveRowUi(
    val id: String,
    val title: String,
    val archivedLabel: String,
    val starred: Boolean = false,
    val ranAfterArchiving: Boolean = false,
)

/**
 * Top-level immutable input to `ChatArchiveContent`.
 *
 * @property visualState rendering branch.
 * @property rows archived chats, most-recently-archived first.
 * @property subtitle TopAppBar subtitle (e.g. `"6 chats · newest first"`).
 * @property openMenuRowId id of the row whose overflow menu is open, or `null`.
 * @property revealedRowId **preview / snapshot control only**: when non-null,
 *   the named row is forced open and every other row forced shut, and the drag
 *   gesture is disabled. `null` (production) leaves every row's swipe under the
 *   user's finger, exactly like `PipelineListRow`.
 * @property deleteTarget the row awaiting delete-forever confirmation, or
 *   `null`. Deleting is reachable from the row overflow **only** — never from
 *   the swipe, which stays a single safe action.
 * @property errorMessage user-visible error; non-null iff [visualState] is
 *   [ChatArchiveVisualState.Error].
 */
data class ChatArchiveViewState(
    val visualState: ChatArchiveVisualState,
    val rows: List<ChatArchiveRowUi> = emptyList(),
    val subtitle: String = "",
    val openMenuRowId: String? = null,
    val revealedRowId: String? = null,
    val deleteTarget: ChatArchiveRowUi? = null,
    val errorMessage: String? = null,
) {
    init {
        require((visualState == ChatArchiveVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/**
 * Localised display strings for `ChatArchiveContent`. Defaults are the
 * English copy signed off with the design handoff; hosts pass localised
 * values from `:app` resources.
 *
 * @property title TopAppBar title.
 * @property back back-navigation content description.
 * @property restore restore verb — one word in the inline button, the menu item
 *   and the swipe strip.
 * @property export overflow item that hands the archived chat to the share
 *   sheet (the history-transfer path).
 * @property deleteForever destructive overflow item.
 * @property rowMenuCd content description of a row's overflow button.
 * @property ranAfterArchiving note shown on a row whose run settled after it
 *   was archived.
 * @property footer list footer reassuring the user nothing expires.
 * @property emptyTitle empty-state title.
 * @property emptySubtitle empty-state teaching sentence. Carries the discovery
 *   of *how* to archive, because the empty state deliberately has no CTA: the
 *   only action lives in the chat drawer, and a button that closes the screen
 *   the user just opened is a dead end.
 * @property errorTitle error-state title.
 * @property errorRetry error-state CTA.
 * @property deleteTitle delete-forever dialog title.
 * @property deleteBodyTemplate delete-forever dialog body; `%1$s` is the chat
 *   title.
 * @property deleteConfirm destructive confirm label.
 * @property deleteCancel dismiss label.
 */
data class ChatArchiveStrings(
    val title: String = "Archived chats",
    val back: String = "Back",
    val restore: String = "Restore",
    val export: String = "Export chat",
    val deleteForever: String = "Delete forever",
    val rowMenuCd: String = "More actions",
    val ranAfterArchiving: String = "Run finished after archiving",
    val footer: String = "Archived chats are kept until you delete them",
    val emptyTitle: String = "Nothing archived",
    val emptySubtitle: String = "Archiving takes a chat out of your list without deleting it. " +
        "In the chat list, swipe a chat or open its menu and pick Archive — " +
        "it waits here, whole, until you restore it.",
    val errorTitle: String = "Couldn't load the archive",
    val errorRetry: String = "Try again",
    val deleteTitle: String = "Delete this chat?",
    val deleteBodyTemplate: String = "\"%1\$s\" and all its messages will be deleted from this device. " +
        "This can't be undone.",
    val deleteConfirm: String = "Delete",
    val deleteCancel: String = "Cancel",
)

/** One-shot callbacks consumed by `ChatArchiveContent`. */
class ChatArchiveCallbacks(
    val onBack: () -> Unit = {},
    /** Opens the archived chat read-only. Does **not** un-archive it. */
    val onRowClick: (String) -> Unit = {},
    val onRowMenuOpen: (String) -> Unit = {},
    val onRowMenuDismiss: () -> Unit = {},
    val onRestore: (String) -> Unit = {},
    val onExport: (String) -> Unit = {},
    val onDeleteRequest: (String) -> Unit = {},
    val onDeleteConfirm: (String) -> Unit = {},
    val onDeleteDismiss: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopChatArchiveCallbacks(): ChatArchiveCallbacks = ChatArchiveCallbacks()
