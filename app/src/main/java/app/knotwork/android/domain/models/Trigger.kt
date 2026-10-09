package app.knotwork.android.domain.models

/**
 * A user-defined automation rule: when its [condition] is met, the bound
 * [pipelineId] is run in the background with [prompt] as the input message.
 *
 * Triggers are the product-level payoff of the persisted background-run
 * infrastructure: a [condition] (a schedule, a charging event, a network
 * connection) fires a normal background pipeline run through the existing
 * scheduler → worker path, attributed to [RunOrigin.TRIGGER].
 *
 * **Inert until bound.** A trigger with a `null` [pipelineId] never fires — it
 * is created/edited freely but does nothing until the user binds a pipeline,
 * mirroring the privacy-default contract of the OS entry surfaces. The
 * [enabled] flag is the user's on/off switch independent of binding.
 *
 * @property id Stable unique identifier (UUID).
 * @property name Human-readable label shown in the trigger list.
 * @property condition The activation condition (the "when").
 * @property pipelineId Id of the pipeline run when the trigger fires, or `null`
 *   when the trigger is not yet bound (inert).
 * @property prompt The input message fed to the bound pipeline as the user
 *   prompt on every fire.
 * @property enabled Whether the trigger is active. A disabled trigger is not
 *   registered with the background runtime and never fires.
 * @property armed Edge-detection latch for [event][TriggerCondition.isEventTriggered]
 *   conditions (charging / network): `true` means the trigger is ready to fire
 *   the next time its condition becomes satisfied. It is cleared on fire and set
 *   again once the condition drops, so a sustained state (e.g. an overnight
 *   charge) fires exactly once, not once per poll. Unused by time-scheduled
 *   conditions (which fire on the clock).
 * @property createdAt Epoch-millis the trigger was created.
 * @property lastFiredAt Epoch-millis of the most recent fire, or `null` if it
 *   has never fired. Used for the interval-schedule debounce and for display.
 * @property sessionId Id of the chat session this trigger's runs land in, or
 *   `null` until the trigger has fired at least once. A trigger lazily owns a
 *   single bound session (named after the trigger) so the results of recurring
 *   fires accumulate in one conversation instead of spawning a fresh session
 *   each time. Reset behaviour: preserved across edits (renaming or
 *   reconfiguring a trigger keeps its result log); recreated on the next fire
 *   if the user has deleted the bound session. Like [armed] / [lastFiredAt]
 *   this is runtime-derived lifecycle state, never set by the editor UI.
 * @property activatedAt Epoch-millis the trigger last became [active][isActive] —
 *   enabled and bound — or `null` for a trigger last activated before this was
 *   recorded. The background runtime has nothing to show for a trigger before
 *   that moment, so its health is measured from whichever is later: this or its
 *   latest evaluation. Written by the repository on the transition; never by
 *   the editor.
 * @property staleNoticeFor The moment of the last sign of life (evaluation or
 *   activation) the user was last told the trigger has been silent since, or
 *   `null` when no such notice was sent. One notice per silence: a new sign of
 *   life starts the next. Written by the repository; never by the editor.
 */
data class Trigger(
    val id: String,
    val name: String,
    val condition: TriggerCondition,
    val pipelineId: String?,
    val prompt: String,
    val enabled: Boolean,
    val armed: Boolean = true,
    val createdAt: Long,
    val lastFiredAt: Long? = null,
    val sessionId: String? = null,
    val activatedAt: Long? = null,
    val staleNoticeFor: Long? = null,
) {
    /**
     * Whether the trigger is eligible to be registered with the background
     * runtime: it must be [enabled] **and** bound to a pipeline. An unbound or
     * disabled trigger is never scheduled, so it consumes no background wakeups.
     */
    val isActive: Boolean get() = enabled && pipelineId != null
}
