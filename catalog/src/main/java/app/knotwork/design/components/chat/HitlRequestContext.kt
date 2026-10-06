package app.knotwork.design.components.chat

import androidx.annotation.StringRes
import app.knotwork.design.R

/**
 * What the run behind an approval was asked to do — the request block a
 * [HitlConfirmationCard] shows above the call, so the user judges the call
 * against the request it came from.
 *
 * The text is untrusted (a shared text, another app's request, an instruction
 * the agent composed) and arrives already made one display-safe line and
 * clamped; the card shows it as plain text, never as markup.
 *
 * @property source whose words these are; decides the label above them.
 * @property request the request; `null` when the message carried an image and
 *   no text — the card then says so instead of showing words the user never typed.
 * @property shortened `true` when [request] was clamped and ends in an ellipsis.
 * @property hadImage `true` when the message that started the run carried an image.
 */
data class HitlRequestContext(
    val source: HitlRequestSource,
    val request: String?,
    val shortened: Boolean,
    val hadImage: Boolean,
)

/**
 * Where the request behind an approval came from. Only [Chat] is the user's
 * own message; a [ScheduledTask]'s instruction was written by the agent, and no
 * label says otherwise.
 */
sealed interface HitlRequestSource {

    /** The user's message in this chat. */
    data object Chat : HitlRequestSource

    /** Text shared into the app from the system share sheet. */
    data object Shared : HitlRequestSource

    /**
     * An automation trigger's prompt.
     *
     * @property name the trigger's name, already display-safe and clamped;
     *   `null` when the trigger can no longer be named.
     */
    data class Trigger(val name: String?) : HitlRequestSource

    /** An instruction the agent scheduled through `schedule_task`. */
    data object ScheduledTask : HitlRequestSource

    /** The Quick Settings tile's run. */
    data object QuickTile : HitlRequestSource

    /** A request from another app on the device. */
    data object OtherApp : HitlRequestSource
}

/**
 * A source label as a string resource and the one value it quotes, so a
 * surface without Compose — the approval notification — can resolve it too.
 *
 * @property text the label's string resource.
 * @property argument the trigger name the label quotes, for the trigger label only.
 */
data class HitlRequestLabel(@StringRes val text: Int, val argument: String? = null)

/**
 * The label above a request: whose words these are. Written once and read by
 * the card and the approval notification, so the two cannot name a source
 * differently.
 *
 * An image sent without text is labelled as such whatever the source; an image
 * sent with text in the chat says so beside "You asked".
 */
val HitlRequestContext.label: HitlRequestLabel
    get() = when {
        request == null -> HitlRequestLabel(R.string.knotwork_hitl_request_source_image_only)
        else -> when (val from = source) {
            HitlRequestSource.Chat -> if (hadImage) {
                HitlRequestLabel(R.string.knotwork_hitl_request_source_chat_image)
            } else {
                HitlRequestLabel(R.string.knotwork_hitl_request_source_chat)
            }
            HitlRequestSource.Shared -> HitlRequestLabel(R.string.knotwork_hitl_request_source_shared)
            is HitlRequestSource.Trigger ->
                from.name
                    ?.let { HitlRequestLabel(R.string.knotwork_hitl_request_source_trigger, it) }
                    ?: HitlRequestLabel(R.string.knotwork_hitl_request_source_trigger_deleted)
            HitlRequestSource.ScheduledTask -> HitlRequestLabel(R.string.knotwork_hitl_request_source_scheduled)
            HitlRequestSource.QuickTile -> HitlRequestLabel(R.string.knotwork_hitl_request_source_tile)
            HitlRequestSource.OtherApp -> HitlRequestLabel(R.string.knotwork_hitl_request_source_other_app)
        }
    }
