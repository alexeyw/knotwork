package app.knotwork.android.presentation.ui.settings.provider

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.ProviderId

/**
 * The provider's own name as every settings surface shows it — the External providers row,
 * the Add provider picker and the provider's detail screen. Product names are not localized;
 * the one that describes rather than names — a server the user runs — reads in the interface
 * language.
 *
 * One function instead of the three hand-written copies those surfaces used to carry, so a
 * provider added to [ProviderId] is named in one place — and the exhaustive `when` makes the
 * compiler ask for that name.
 *
 * @param context Resolves the descriptive name in the interface language.
 * @return The display name.
 */
fun ProviderId.displayName(context: Context): String = when (this) {
    ProviderId.OpenAi -> "OpenAI"
    ProviderId.Anthropic -> "Anthropic"
    ProviderId.Google -> "Google"
    ProviderId.DeepSeek -> "DeepSeek"
    ProviderId.OpenRouter -> "OpenRouter"
    ProviderId.Groq -> "Groq"
    ProviderId.Ollama -> "Ollama"
    ProviderId.OpenAiCompatible -> context.getString(R.string.settings_provider_openai_compatible)
}
