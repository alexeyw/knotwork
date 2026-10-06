package app.knotwork.android.domain.models

/**
 * What the run waiting for an approval was asked to do — shown next to the
 * tool call, so the user judges the call against the request it was derived
 * from instead of on its own.
 *
 * Always the **root** run's request: a sub-pipeline's own input is an
 * intermediate the user never wrote. Built from data the app already keeps
 * (the root run record, the trigger journal) — no model is asked to summarise
 * anything.
 *
 * The text is **untrusted** — a shared text, another app's request, an
 * instruction the agent composed — and arrives here already display-safe: one
 * line, control and bidi-override characters flattened, clamped on a word.
 * Surfaces show it as plain text, never as markup.
 *
 * @property source Whose words these are; decides the label shown above them.
 * @property request The request, one display-safe line of at most
 *   `ApprovalRequestText.MAX_CARD_LENGTH` characters; `null` when the message
 *   carried an image and no text — the text the run received then is the app's
 *   own default instruction, which the user never typed and is not shown as
 *   their words.
 * @property shortened `true` when [request] was clamped and ends in an ellipsis.
 * @property hadImage `true` when the message that started the run carried an image.
 */
data class ApprovalRequestContext(
    val source: ApprovalRequestSource,
    val request: String?,
    val shortened: Boolean,
    val hadImage: Boolean,
)

/**
 * Where the request behind an approval came from — the run's origin, as the
 * approval surfaces name it. Only [Chat] is the user's own message; a
 * [ScheduledTask]'s instruction was written by the agent.
 */
sealed interface ApprovalRequestSource {

    /** The user's message in this chat. */
    data object Chat : ApprovalRequestSource

    /** Text shared into the app from the system share sheet. */
    data object Shared : ApprovalRequestSource

    /**
     * An automation trigger's prompt.
     *
     * @property name The trigger's name, display-safe and clamped to
     *   `ApprovalRequestText.MAX_TRIGGER_NAME_LENGTH`; `null` when the trigger
     *   can no longer be named — deleted since it fired, or its journal row is
     *   gone.
     */
    data class Trigger(val name: String?) : ApprovalRequestSource

    /** An instruction the agent scheduled through `schedule_task`. */
    data object ScheduledTask : ApprovalRequestSource

    /** The Quick Settings tile's run. */
    data object QuickTile : ApprovalRequestSource

    /** A request from another app on the device (external automation). */
    data object OtherApp : ApprovalRequestSource
}
