package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ToolRisk

/**
 * Service to notify the user when the agent requires approval to execute an action.
 *
 * Notifications belong to **requests**, not sessions: each is posted, replaced
 * and removed by the identity of the one request it shows (`requestId`, minted
 * by the gate), and its buttons answer that request only. A session can hold
 * a parked request and a live one at the same time; one notification per
 * session would let the later request destroy the earlier one's — its only way
 * back — or answer it.
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
     * @param requestId Identity of the request; the notification's slot and the
     *   answer its buttons carry.
     * @param toolName The name of the tool.
     * @param arguments The arguments passed to the tool.
     * @param risk Risk classification of the tool, used to pick channel / icon / copy / actions.
     */
    fun sendApprovalRequest(sessionId: String, requestId: String, toolName: String, arguments: String, risk: ToolRisk)

    /**
     * Sends the persistent-phase approval request for a parked run.
     *
     * Posted when the live in-process waiting phase times out and the run
     * parks on its pending-interaction record — in the live notification's
     * slot, since both show the same request: the notification must outlive
     * the engine coroutine (ongoing, re-posted on dismissal) because it is
     * the user's primary path back to the parked run. Unlike
     * [sendApprovalRequest] it is posted even when the session is currently
     * on screen — the run is no longer live, so the in-chat card alone
     * cannot be relied on after the user navigates away.
     *
     * Actions are risk-gated: [ToolRisk.READ_ONLY] and [ToolRisk.SENSITIVE]
     * carry Approve / Deny buttons addressing the parked request;
     * [ToolRisk.DESTRUCTIVE] carries only Deny — approving a destructive
     * action requires the typed confirmation of the in-chat card, reached
     * via the notification's deep link.
     *
     * @param runId Id of the parked run, for the re-post of a dismissed notification.
     * @param sessionId The ID of the session that triggered the request.
     * @param requestId Identity of the parked request; the slot and the answer.
     * @param toolName The name of the tool.
     * @param arguments The arguments passed to the tool.
     * @param risk Risk classification of the tool, used to pick channel / icon / actions.
     */
    fun sendPersistentApprovalRequest(
        runId: String,
        sessionId: String,
        requestId: String,
        toolName: String,
        arguments: String,
        risk: ToolRisk,
    )

    /**
     * Removes the notification of approval request [requestId], if it is
     * showing. Other requests' notifications — of the same session included —
     * stay.
     *
     * Called whenever the request stops waiting other than through the
     * notification itself (the in-chat card, a stopped run, the approval-window
     * expiry pass) so a stale notification cannot offer a decision that was
     * already made, or no longer has anything to decide.
     *
     * @param requestId The request whose notification to remove.
     */
    fun cancelApprovalNotification(requestId: String)
}
