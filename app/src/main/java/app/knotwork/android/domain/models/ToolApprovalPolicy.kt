package app.knotwork.android.domain.models

/**
 * Policy that drives the Human-in-the-Loop (HITL) approval gate
 * (`ToolInvocationGate`): which risk tiers stop and wait for the user.
 *
 * [requiresApproval] is the one place the rule is written down; the gate asks it
 * and interprets nothing itself. The rule has a floor no policy goes below:
 * **a [ToolRisk.DESTRUCTIVE] call always asks.** A quieter policy only drops the
 * prompts for the reversible tiers; the one control that removes the destructive
 * prompt is `SettingsRepository.blockDestructiveTools`, and it does so by
 * refusing the call, never by running it unasked.
 *
 * Wire key persists in DataStore so the value survives process death and
 * future evolutions of the enum order. Values added later MUST keep the
 * existing keys to avoid silently re-bucketing user preferences.
 *
 * Semantics:
 *  - [AllCalls] — every tool, regardless of risk, requires explicit approval.
 *    The most cautious posture; trades fluency for control.
 *  - [SensitiveOrDestructive] — `READ_ONLY` tools run silently;
 *    `SENSITIVE` and `DESTRUCTIVE` tools prompt. Default for new
 *    installs and the recommended posture.
 *  - [NeverPrompt] — `READ_ONLY` and `SENSITIVE` tools run silently;
 *    `DESTRUCTIVE` tools still prompt. For power-users running known-safe
 *    pipelines unattended.
 *
 * Migration from the legacy boolean `requires_user_confirmation` key lives in
 * `SettingsManager` and applies on read while no policy has been stored:
 * `true` → [SensitiveOrDestructive], `false` → [NeverPrompt] (the legacy switch
 * was the only way to quiet the prompts; destructive calls ask under it like
 * under every policy).
 */
enum class ToolApprovalPolicy(
    /** Wire identifier persisted to DataStore; stable across enum-order changes. */
    val key: String,
) {
    /** Every tool call requires explicit user approval, regardless of risk class. */
    AllCalls(key = "all_calls"),

    /** Default — prompt for `SENSITIVE` and `DESTRUCTIVE` calls only. */
    SensitiveOrDestructive(key = "sensitive_or_destructive"),

    /** Prompt for `DESTRUCTIVE` calls only; the reversible tiers run without asking. */
    NeverPrompt(key = "never_prompt"),
    ;

    /**
     * Whether a call of [risk] must stop for the user's approval under this policy.
     *
     * A node's own `alwaysConfirm` switch is ORed in by the gate on top of this
     * answer, so it can only add a prompt; nothing can remove the one this
     * function requires.
     *
     * @param risk The resolved risk of the call about to run.
     * @return `true` when the call must wait for an approval; always `true` for
     *   [ToolRisk.DESTRUCTIVE].
     */
    fun requiresApproval(risk: ToolRisk): Boolean = when (this) {
        AllCalls -> true
        SensitiveOrDestructive -> risk == ToolRisk.SENSITIVE || risk == ToolRisk.DESTRUCTIVE
        NeverPrompt -> risk == ToolRisk.DESTRUCTIVE
    }

    /** Wire-key parsing helpers + the [DEFAULT] sentinel. */
    companion object {
        /** Default for a fresh install — recommended posture. */
        val DEFAULT: ToolApprovalPolicy = SensitiveOrDestructive

        /**
         * Resolves the wire key back to an enum value. Returns [DEFAULT] for
         * unknown keys so a corrupt DataStore write cannot crash the app
         * (the user's choice is silently reset to the recommended posture).
         */
        fun fromKey(key: String?): ToolApprovalPolicy = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
