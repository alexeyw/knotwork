package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.screens.chat.ChatHomeMessageRow

/**
 * Shared pure transformers over [ChatHomeScreenState] used by both the
 * [ChatHomeViewModel] core (send / lifecycle) and several of its delegates
 * (HITL, reattach). Kept as `internal` top-level extensions — rather than on
 * any single owner — because they encode cross-cutting visual/pending rules
 * that no one delegate owns, and they must compose into each caller's single
 * atomic `state.update { ... }` block (never their own emission) so multi-field
 * transitions remain one flow emission.
 */

/**
 * Resting (non-overlay) visual for this snapshot — a status card's own state
 * while its snapshot is pending (the card must survive transient overlays like
 * the drawer; dropping to Idle would strand its actions until the next thread
 * switch), otherwise `Empty` / `Idle` by message-list presence. Derived from
 * the receiver (never `_state.value`) so calls inside a `state.update` lambda
 * stay consistent with the snapshot being transformed.
 *
 * The ceiling pause is tested first. The two snapshots are mutually exclusive
 * in practice — a run cannot be both waiting and dead — but the pause is the
 * one with a live run behind it, so if they ever did coincide, showing the
 * decision the user can still act on beats showing the post-mortem.
 */
internal fun ChatHomeScreenState.restingVisual(): ChatHomeUiState = when {
    pending.ceiling != null -> ChatHomeUiState.CeilingPause
    pending.interrupted != null -> ChatHomeUiState.Interrupted
    messages.isEmpty() -> ChatHomeUiState.Empty
    else -> ChatHomeUiState.Idle
}

/**
 * The rows the thread shows: the stored [ChatHomeScreenState.messages] followed by
 * the just-sent message while it is still on its way to storage.
 */
internal val ChatHomeScreenState.visibleMessages: List<ChatHomeMessageRow>
    get() = sendingUserTurn?.let { messages + it.row } ?: messages

/**
 * Pure transformer: drops every pending HITL / clarification snapshot and
 * resets the typed-confirm input. Composed into the caller's `state.update`
 * block (never its own emission) so multi-field transitions remain a single
 * atomic flow emission.
 */
internal fun ChatHomeScreenState.withPendingCleared(): ChatHomeScreenState = copy(
    pending = ChatHomePendingState(),
    composer = composer.copy(typedConfirm = ""),
)

/**
 * Whether this visual is a resting or cold-start state that a reattach branch
 * may safely overwrite. Active overlays (Generating, HITL, Clarification, Error,
 * Drawer) are user-facing context that the asynchronous reattach lookup must
 * never yank away. Shared by the ViewModel's live-run collector and the reattach
 * delegate.
 */
internal fun ChatHomeUiState.isRestingOrCold(): Boolean =
    this is ChatHomeUiState.Loading || this is ChatHomeUiState.Empty || this is ChatHomeUiState.Idle
