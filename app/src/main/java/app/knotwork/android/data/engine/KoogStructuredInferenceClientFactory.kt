package app.knotwork.android.data.engine

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredInferenceClient
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton
import ai.koog.prompt.dsl.prompt as buildPrompt

/**
 * Data-layer [CloudStructuredInferenceClientFactory] backed by the Koog cloud
 * client.
 *
 * Reuses the retry-wrapped client from [CloudLlmClientFactory] and the model
 * resolution from [CloudLlmModelResolver], so a cloud structured-output call
 * inherits the same transient-failure resilience and per-provider model
 * defaults as a free-form CLOUD node. The returned [StructuredInferenceClient]
 * collapses the streamed response to a single string for the
 * [app.knotwork.android.domain.engine.structured.StructuredOutputGate] to
 * validate.
 *
 * **No JSON mode.** The request carries no response schema and switches no
 * JSON mode on, whatever the model advertises: the gate is invoked with three
 * different output shapes (a JSON object, a top-level JSON array, and a bare
 * constrained token), and no single response schema fits all three. A cloud
 * answer can therefore be prose like a local one, and the gate validates and
 * repairs it with the same configured budget. The lowered temperature on repair
 * attempts is forwarded through [LLMParams].
 *
 * @property cloudLlmClientFactory Builds the retry-wrapped Koog client.
 * @property cloudLlmModelResolver Resolves the configured model per provider.
 * @property networkActivityTracker Records the outbound network pulse for the
 *   privacy footer, symmetric to the free-form CLOUD path.
 */
@Singleton
class KoogStructuredInferenceClientFactory @Inject constructor(
    private val cloudLlmClientFactory: CloudLlmClientFactory,
    private val cloudLlmModelResolver: CloudLlmModelResolver,
    private val networkActivityTracker: NetworkActivityTracker,
) : CloudStructuredInferenceClientFactory {

    override suspend fun create(
        provider: CloudProvider,
        onToken: suspend (String) -> Unit,
    ): StructuredInferenceClient? {
        val client = cloudLlmClientFactory.createClient(provider) as? LLMClient ?: return null
        val model = cloudLlmModelResolver.resolveModel(provider) as LLModel
        return KoogStructuredInferenceClient(client, model, networkActivityTracker, onToken)
    }
}

/**
 * [StructuredInferenceClient] that runs one structured inference through a Koog
 * cloud client and returns the concatenated streamed text.
 *
 * @property client The retry-wrapped Koog client to call.
 * @property model The resolved model descriptor.
 * @property networkActivityTracker Records the outbound network pulse.
 * @property onToken Invoked with each streamed token for the live "Thinking" UI.
 */
private class KoogStructuredInferenceClient(
    private val client: LLMClient,
    private val model: LLModel,
    private val networkActivityTracker: NetworkActivityTracker,
    private val onToken: suspend (String) -> Unit,
) : StructuredInferenceClient {

    override suspend fun infer(prompt: String, temperature: Float?): String {
        networkActivityTracker.recordOutbound()
        val params = if (temperature != null) LLMParams(temperature = temperature.toDouble()) else LLMParams()
        val builtPrompt = buildPrompt(id = "structured", params = params) { user(prompt) }
        val accumulated = StringBuilder()
        client.executeStreaming(builtPrompt, model)
            // Told again per frame, so a long answer keeps the indicator "online".
            .onEach { networkActivityTracker.recordOutbound() }
            .mapNotNull { (it as? StreamFrame.TextDelta)?.text }
            .collect { token ->
                accumulated.append(token)
                onToken(token)
            }
        return accumulated.toString()
    }
}
