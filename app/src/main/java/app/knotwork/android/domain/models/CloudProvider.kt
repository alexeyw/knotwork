package app.knotwork.android.domain.models

/**
 * Type-safe identifier for the cloud LLM providers the agent can route to.
 *
 * Each enum constant carries the lowercase wire-id that is persisted in the
 * database (`pipeline_nodes.cloudProvider`), serialized in the pipeline JSON
 * (`PipelineJsonSerializer`), and exposed to the LLM as a tool-argument
 * (`DelegateTaskTool.executeDelegation`). Centralising the mapping here lets
 * every consumer perform exhaustive `when`-dispatch and removes the previous
 * cluster of `when (s: String)` blocks scattered across the data and domain
 * layers.
 *
 * The companion object owns the parsing rules — including the historical
 * `"gemini"` alias for [GOOGLE] — so that incoming wire data only has to be
 * decoded once on the boundary.
 */
enum class CloudProvider(
    /**
     * Lowercase wire-id persisted to disk and accepted by the cloud LLM router. It also
     * names the provider's entries in the credential store (`<id>_api_key`, `<id>_model`,
     * `<id>_base_url`), so it can never change once shipped.
     */
    val id: String,
    /** Whether the provider is reached with an API key the user saves. */
    val usesApiKey: Boolean,
    /**
     * Whether the provider is reached at a server address the user enters, rather than at
     * a host the client already knows. For such a provider the address — not a key — is
     * what decides whether it is set up at all.
     */
    val usesBaseUrl: Boolean,
) {
    /** OpenAI (GPT family). */
    OPENAI("openai", usesApiKey = true, usesBaseUrl = false),

    /** Anthropic (Claude family). */
    ANTHROPIC("anthropic", usesApiKey = true, usesBaseUrl = false),

    /** Google AI Studio / Gemini family. */
    GOOGLE("google", usesApiKey = true, usesBaseUrl = false),

    /** DeepSeek hosted API. */
    DEEPSEEK("deepseek", usesApiKey = true, usesBaseUrl = false),

    /** Self-hosted Ollama instance (typically over Wi-Fi); no key. */
    OLLAMA("ollama", usesApiKey = false, usesBaseUrl = true),
    ;

    /** Owns the wire-id ↔ enum parsing rules (including the legacy `"gemini"` alias). */
    companion object {
        /**
         * UI marker that means "let the executor pick a provider based on the
         * configured API keys". This is **not** a real provider — it never
         * survives [fromId] and must be filtered out before dispatching.
         */
        const val AUTO_KEY: String = "auto"

        /**
         * Parses a wire/UI provider id into a typed [CloudProvider].
         *
         * Matching is case-insensitive. The legacy alias `"gemini"` is mapped
         * to [GOOGLE] because earlier versions of the project persisted that
         * label for Google models. Unknown ids, [AUTO_KEY], and `null` all
         * return `null` — callers decide whether the absence is an error
         * (validation) or a fallback trigger (auto-detect).
         *
         * @param id Provider id as stored on disk or chosen by the user.
         * @return The matching [CloudProvider], or `null` when the id is
         *         unknown / blank / `null` / [AUTO_KEY].
         */
        fun fromId(id: String?): CloudProvider? = when (id?.lowercase()) {
            null -> null
            "openai" -> OPENAI
            "anthropic" -> ANTHROPIC
            "google", "gemini" -> GOOGLE
            "deepseek" -> DEEPSEEK
            "ollama" -> OLLAMA
            else -> null
        }
    }
}
