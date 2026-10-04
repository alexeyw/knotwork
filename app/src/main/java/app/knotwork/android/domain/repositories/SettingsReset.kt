package app.knotwork.android.domain.repositories

/**
 * The two resets of tunable settings. Each spans several sections, so it lives in none of them; an
 * implementation writes each reset as one atomic edit.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface SettingsReset {

    /**
     * Resets the local-generation sampling parameters back to the
     * documented defaults ([SettingsDefaults.TEMPERATURE_DEFAULT],
     * [SettingsDefaults.TOP_K_DEFAULT], [SettingsDefaults.TOP_P_DEFAULT],
     * [SettingsDefaults.REPETITION_PENALTY_DEFAULT],
     * [SettingsDefaults.MAX_CONTEXT_LENGTH_DEFAULT],
     * [SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT]) — and, alongside them, the
     * autonomous-run ceilings, which share this reset because they are the same
     * family of runtime bound: the background step cap and both token caps. Used
     * by the "Reset to defaults" action in Settings → LLM parameters.
     */
    suspend fun resetSamplingDefaults()

    /**
     * Restores **every tunable preference** to its recommended default from
     * [app.knotwork.android.domain.constants.SettingsDefaults], in a single
     * atomic write. Backs the "Reset all settings" action in Settings → Privacy.
     *
     * Strictly scoped to *tunable values* — it deliberately does **not** touch
     * user-entered content or configuration: long-term memory, chats,
     * pipelines, presets, skills, secrets (API keys / HuggingFace token),
     * MCP servers, the `http_request` domain allowlist, per-tool enable/disable
     * and risk overrides, the user-authored system-instructions prefix, the
     * active embedding provider (and its re-embed
     * marker), the default-pipeline binding, the per-surface entry-point
     * pipeline bindings (share target, Quick Settings tile), the selected local
     * backend, and onboarding / transient state. Everything it resets has a documented
     * recommended default; everything it leaves alone is the user's own data.
     */
    suspend fun resetToRecommendedDefaults()
}
