package app.knotwork.android.presentation.state

import app.knotwork.android.presentation.ui.common.UiText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide hot bus for one-shot user-visible messages that need to
 * survive a navigation event.
 *
 * Motivating case: the onboarding skip-flow emits a hint
 * (*"You can install a model from Settings → Models"*) but
 * `OnboardingScreen` pops itself off the back-stack the very same frame
 * the snackbar event would be collected, so a screen-local
 * `SnackbarHost` never gets a chance to render it. Routing the message
 * through this singleton lets any composable mounted at a stable level
 * (currently `MainActivity`) keep the host alive across navigation.
 *
 * The relay is intentionally minimal — a single `SharedFlow<UiText>`,
 * no severity / action / id metadata — because the only consumer today
 * is the activity-level snackbar host. If a second consumer surfaces
 * (e.g. a system-status banner), expand to a sealed `TransientMessage`
 * model rather than overloading this contract.
 */
@Singleton
class TransientMessageRelay @Inject constructor() {

    /**
     * Backing buffer:
     *  - `replay = 0` — late subscribers do NOT see prior messages
     *    (these are one-shot UI signals, not state);
     *  - `extraBufferCapacity = 1` — `emit` is non-suspending and a
     *    message published when the consumer is offline (e.g. mid-rotation)
     *    survives one round;
     *  - `BufferOverflow.DROP_OLDEST` — if a second message arrives
     *    before the first is drained, drop the older one; in practice
     *    this never happens but the deterministic policy beats hanging.
     */
    private val _messages: MutableSharedFlow<UiText> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Externally-observed stream consumed by the activity-level host. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /**
     * Publishes a single user-visible message. Non-suspending so the
     * caller (typically a ViewModel) can fire-and-forget from any
     * coroutine context.
     *
     * @param message the text to render in the snackbar. A resource is
     * resolved by the host when it shows the message, in the language the
     * screen is in then; a caller that already resolved its text passes
     * [UiText.Dynamic].
     */
    fun post(message: UiText) {
        _messages.tryEmit(message)
    }
}
