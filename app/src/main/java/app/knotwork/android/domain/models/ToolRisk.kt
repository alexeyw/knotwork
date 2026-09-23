package app.knotwork.android.domain.models

/**
 * Per-tool risk classification used by the Human-in-the-Loop (HITL) gate to decide
 * whether a tool invocation requires explicit user confirmation.
 *
 * The classification is **policy, not capability**: it expresses the assumed
 * side-effect profile of the tool, not what the tool literally does. The single
 * source of truth for resolving a given tool name to a [ToolRisk] is
 * `ToolRepository.getRisk` (built-in defaults + per-AppFunction overrides + MCP
 * blanket policy). Which tiers stop for an approval is decided by
 * [ToolApprovalPolicy.requiresApproval].
 */
enum class ToolRisk {
    /**
     * Pure read-only tool with no externally observable side effects (no writes,
     * no network mutations, no user-data changes). Runs without a prompt unless
     * the user chose [ToolApprovalPolicy.AllCalls] or the node always confirms.
     */
    READ_ONLY,

    /**
     * Tool whose effects are reversible or limited in scope but still
     * user-observable (e.g. scheduling a background task, delegating to a cloud
     * model, writing to local app state). Prompts under the default policy;
     * [ToolApprovalPolicy.NeverPrompt] lets it run without asking.
     */
    SENSITIVE,

    /**
     * Tool whose effects are hard or impossible to reverse (sending messages,
     * deleting files, purchases, system-level mutations). Prompts under every
     * [ToolApprovalPolicy]; the one control that removes the prompt is the
     * destructive block, and it refuses the call instead of running it.
     */
    DESTRUCTIVE,
}
