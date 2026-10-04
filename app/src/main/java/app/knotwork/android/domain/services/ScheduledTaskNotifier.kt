package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.RunTerminationKind

/**
 * Posts user-facing notifications announcing background-run lifecycle events:
 * a trigger firing ("Trigger fired"), and the run outcome ("Task completed" /
 * "Task failed"). Powers the runtime side of the Settings → Notifications →
 * "Scheduled task results" toggle.
 *
 * Without these notifications a scheduler- or trigger-origin run that finishes
 * while the app is closed would succeed silently: the result lands in the bound
 * chat session, but nothing tells the user to look there. Each notification
 * deep-links into the session so a tap opens the conversation with the
 * freshly landed messages.
 *
 * The toggle's user-controlled flag is read from
 * `RunSettings.scheduledTaskNotificationsEnabled` by the
 * implementation — callers do NOT need to gate themselves. If the user has
 * the flag off (or the POST_NOTIFICATIONS permission is missing), both
 * notify methods are no-ops.
 */
interface ScheduledTaskNotifier {

    /**
     * Registers the underlying `NotificationChannel` so the system knows
     * about it on the very first post. Idempotent — safe to call from
     * `App.onCreate`.
     */
    fun registerChannel()

    /**
     * Announces that an automation trigger has just fired and enqueued a
     * background run, so the user knows an unattended run has started (it may be
     * constraint-deferred or take a while). Posted on the same channel and with
     * the same per-session notification id as the outcome notifications, so the
     * later [notifyCompleted] / [notifyFailed] supersedes it rather than stacking.
     *
     * @param sessionId Chat session the trigger's run will land its result in;
     *   the notification's tap action deep-links into this session.
     * @param triggerName Human-readable trigger label, shown as the body.
     */
    suspend fun notifyTriggerFired(sessionId: String, triggerName: String)

    /**
     * Announces a successfully completed scheduled run.
     *
     * @param sessionId Chat session the run landed its result in; the
     *   notification's tap action deep-links into this session.
     * @param resultPreview First line of the final agent answer, shown as the
     *   notification body so the user can triage without opening the app.
     */
    suspend fun notifyCompleted(sessionId: String, resultPreview: String)

    /**
     * Announces a failed scheduled run.
     *
     * @param sessionId Chat session the run was bound to; the notification's
     *   tap action deep-links into this session.
     * @param reason Human-readable failure reason recorded on the persistent
     *   run record, shown as the notification body.
     */
    suspend fun notifyFailed(sessionId: String, reason: String)

    /**
     * Announces a background run that the app itself decided to end.
     *
     * Separate from [notifyFailed] because it is not a failure and must not
     * read like one: a ceiling stop is the guard the user configured doing its
     * job, and the trigger journal has called it "Stopped by a safety limit"
     * since it was introduced. Posting it under the failure title and the
     * failure glyph left the notification as the last surface still calling a
     * working limit a defect.
     *
     * Takes the typed cause rather than a rendered sentence so the wording
     * stays owned by one place; the implementation resolves it to the same
     * words the chat and the journal use.
     *
     * @param sessionId Chat session the run was bound to; the notification's
     *   tap action deep-links into this session.
     * @param kind Why the run was ended.
     * @param runLabel Human name of the session, quoted in the body so a user
     *   with several automations knows which one this is about.
     * @param stepsSpent Node executions charged across the run tree.
     * @param tokensSpent Tokens accounted across the run tree. Note that this
     *   can exceed the ceiling that stopped the run: tokens are charged a whole
     *   node's usage at a time and compared afterwards, unlike steps. The
     *   implementation therefore reports both as spend and never as the
     *   allowance.
     */
    suspend fun notifyTerminated(
        sessionId: String,
        kind: RunTerminationKind,
        runLabel: String,
        stepsSpent: Int,
        tokensSpent: Int,
    )
}
