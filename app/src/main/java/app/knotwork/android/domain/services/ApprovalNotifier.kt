package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ToolRisk

/**
 * Service to notify the user when the agent requires approval to execute an action.
 */
interface ApprovalNotifier {
    /**
     * Sends an approval request.
     *
     * The notification's channel, icon and title are derived from [risk]:
     * [ToolRisk.DESTRUCTIVE] routes through a dedicated high-importance channel
     * with a warning glyph so the user can distinguish hard-to-reverse actions
     * from reversible [ToolRisk.SENSITIVE] ones at a glance; [ToolRisk.READ_ONLY]
     * is only reached when the user has globally opted into "ask on every tool
     * call" and reuses the SENSITIVE channel.
     *
     * Actions are risk-gated the same way as in [sendPersistentApprovalRequest]:
     * a [ToolRisk.DESTRUCTIVE] request carries no Approve action — approving it
     * requires the typed confirmation of the in-chat card, reached via a
     * "Review in chat" deep link.
     *
     * @param sessionId The ID of the session that triggered the request.
     * @param toolName The name of the tool.
     * @param arguments The arguments passed to the tool.
     * @param risk Risk classification of the tool, used to pick channel / icon / copy / actions.
     */
    fun sendApprovalRequest(sessionId: String, toolName: String, arguments: String, risk: ToolRisk)

    /**
     * Sends the persistent-phase approval request for a parked run.
     *
     * Posted when the live in-process waiting phase times out and the run
     * parks on its pending-interaction record: the notification must outlive
     * the engine coroutine (ongoing, re-posted on dismissal) because it is
     * the user's primary path back to the parked run. Unlike
     * [sendApprovalRequest] it is posted even when the session is currently
     * on screen — the run is no longer live, so the in-chat card alone
     * cannot be relied on after the user navigates away.
     *
     * Actions are risk-gated: [ToolRisk.READ_ONLY] and [ToolRisk.SENSITIVE]
     * carry Approve / Deny buttons addressing the run directly;
     * [ToolRisk.DESTRUCTIVE] carries only Deny — approving a destructive
     * action requires the typed confirmation of the in-chat card, reached
     * via the notification's deep link.
     *
     * @param runId Id of the parked run the decision must address.
     * @param sessionId The ID of the session that triggered the request.
     * @param toolName The name of the tool.
     * @param arguments The arguments passed to the tool.
     * @param risk Risk classification of the tool, used to pick channel / icon / actions.
     */
    fun sendPersistentApprovalRequest(
        runId: String,
        sessionId: String,
        toolName: String,
        arguments: String,
        risk: ToolRisk,
    )

    /**
     * Removes the approval notification of [sessionId], if any is showing.
     *
     * Called when the pending request is settled from a surface other than
     * the notification itself (the in-chat card, the approval-window expiry
     * pass) so a stale notification cannot offer a decision that was already
     * made.
     *
     * @param sessionId The session whose approval notification to remove.
     */
    fun cancelApprovalNotification(sessionId: String)
}
