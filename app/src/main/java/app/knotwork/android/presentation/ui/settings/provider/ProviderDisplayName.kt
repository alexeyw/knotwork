package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.domain.models.ProviderId

/**
 * The provider's own name as every settings surface shows it — the External providers row,
 * the Add provider picker and the provider's detail screen. Not localized: these are product
 * names.
 *
 * One function instead of the three hand-written copies those surfaces used to carry, so a
 * provider added to [ProviderId] is named in one place — and the exhaustive `when` makes the
 * compiler ask for that name.
 *
 * @return The display name.
 */
fun ProviderId.displayName(): String = when (this) {
    ProviderId.OpenAi -> "OpenAI"
    ProviderId.Anthropic -> "Anthropic"
    ProviderId.Google -> "Google"
    ProviderId.DeepSeek -> "DeepSeek"
    ProviderId.OpenRouter -> "OpenRouter"
    ProviderId.Groq -> "Groq"
    ProviderId.Ollama -> "Ollama"
    ProviderId.OpenAiCompatible -> "OpenAI-compatible server"
}
