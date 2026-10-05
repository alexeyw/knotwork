package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import kotlinx.coroutines.flow.first

/**
 * What a node set to `"auto"` runs on: the first provider of [ORDER] with a saved API key.
 *
 * One rule, read by the CLOUD executor when it runs such a node and by the node sheet when it says
 * what *Auto* resolves to on this device, so the two cannot disagree.
 */
object AutoProvider {

    /**
     * The providers `"auto"` chooses among, in the order it tries them — the historical routing
     * priority, kept so existing pipelines keep their previous default.
     *
     * A list of its own rather than [CloudProvider.entries]: what `"auto"` may pick is a decision,
     * not a consequence of adding a provider. A provider appended to the enum would otherwise start
     * receiving every `"auto"` node's prompt the moment its key is saved, without the user ever
     * choosing it. Ollama is absent for the same reason it always was — it has no key to detect.
     */
    val ORDER: List<CloudProvider> = listOf(
        CloudProvider.GOOGLE,
        CloudProvider.ANTHROPIC,
        CloudProvider.OPENAI,
        CloudProvider.DEEPSEEK,
    )

    /**
     * Resolves `"auto"` against the keys saved now.
     *
     * @param apiKeyRepository Where the keys are.
     * @return The first provider of [ORDER] with a non-blank key, or `null` when none has one.
     */
    suspend fun resolve(apiKeyRepository: ApiKeyRepository): CloudProvider? =
        ORDER.firstOrNull { !apiKeyRepository.getApiKey(it).first().isNullOrBlank() }
}
