package app.knotwork.android.domain.models

/**
 * Identifier for one of the external LLM providers surfaced by the
 * Settings → External providers list, in the order the list shows them. Tied 1:1 to [CloudProvider] but
 * kept separate so the UI can ship localized display labels without
 * polluting the enum used by the inference pipeline.
 */
enum class ProviderId(
    /** Underlying [CloudProvider] used to dispatch inference calls. */
    val cloudProvider: CloudProvider,
) {
    /** OpenAI (GPT family). */
    OpenAi(CloudProvider.OPENAI),

    /** Anthropic (Claude family). */
    Anthropic(CloudProvider.ANTHROPIC),

    /** Google AI Studio / Gemini family. */
    Google(CloudProvider.GOOGLE),

    /** DeepSeek hosted API. */
    DeepSeek(CloudProvider.DEEPSEEK),

    /** Self-hosted Ollama instance (typically over Wi-Fi). */
    Ollama(CloudProvider.OLLAMA),
}

/**
 * Collapsed external-provider row rendered by Settings. Each provider
 * folds to a single tappable nav-row with fingerprint + model name +
 * chevron.
 *
 * @property id Stable identifier for the provider.
 * @property displayName Localized provider name shown as the row title.
 * @property keyFingerprint Masked fingerprint of the configured API key
 *   (e.g. `sk-…3a9f`). `null` when no key is configured — the UI then
 *   renders "Not configured · tap to add API key".
 * @property model Currently selected model name (e.g. `gpt-4o-mini`).
 *   `null` when no model has been picked.
 * @property isLanLocal `true` for a provider reached at an address the user
 *   enters ([CloudProvider.usesBaseUrl] — today Ollama) — drives the LAN pill
 *   rendered next to the row title.
 * @property endpointHint Optional secondary line (e.g. Ollama base URL)
 *   shown beneath the model name. `null` when the provider has no
 *   user-facing endpoint configuration.
 */
data class ProviderSummary(
    val id: ProviderId,
    val displayName: String,
    val keyFingerprint: String?,
    val model: String?,
    val isLanLocal: Boolean,
    val endpointHint: String? = null,
)
