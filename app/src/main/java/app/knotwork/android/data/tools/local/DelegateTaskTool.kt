package app.knotwork.android.data.tools.local

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * A specialized tool designed for the main (local) AI agent to delegate complex or
 * specialized tasks to powerful external Large Language Models (LLMs) such as Claude,
 * OpenAI, or Gemini, through the same client factory and model resolver as a Cloud node.
 *
 * This class exposes a function that the local agent can call. When called,
 * it routes the prompt to the specified external model, awaits the response asynchronously,
 * and returns the whole response to the caller. The response is not written to long-term
 * memory: it is a cloud model's text, not something the user said, and a node on the
 * on-device model already cuts a long tool result to its read budget.
 *
 * This allows the local agent to remain lightweight and responsive, while offloading
 * computationally expensive or highly specialized reasoning to cloud models.
 *
 * The client and the model come from the same [CloudLlmClientFactory] and [CloudLlmModelResolver]
 * a Cloud node uses, so a delegated call is built — gated, retried and defaulted — exactly like
 * one, and a provider added to [CloudProvider] reaches this tool without a branch of its own.
 * The tool used to build both itself, with a third copy of every provider's default model.
 *
 * @property cloudLlmClientFactory Builds the retry-wrapped client and names the cause when it cannot.
 * @property cloudLlmModelResolver Resolves the provider's chosen (or default) model.
 * @property networkActivityTracker Told about the delegated call before it is sent, so the
 *   More tab's privacy indicator counts it.
 */
class DelegateTaskTool @Inject constructor(
    private val cloudLlmClientFactory: CloudLlmClientFactory,
    private val cloudLlmModelResolver: CloudLlmModelResolver,
    private val networkActivityTracker: NetworkActivityTracker,
) {

    /**
     * Executes the task delegation process.
     *
     * It performs the following steps:
     * 1. Validates the target model.
     * 2. Instantiates the client for the target model.
     * 3. Sends the prompt to the external model with a 60-second timeout to avoid blocking.
     * 4. Returns the whole response, under a line naming the provider that answered.
     *
     * The entire operation is wrapped in a [Dispatchers.IO] context to prevent blocking
     * the main thread or the agent's Foreground Service.
     *
     * @param taskDescription A detailed explanation of the task to be delegated. This will be used as the prompt for the external LLM.
     * @param targetModel The [CloudProvider] wire id of the provider to use (aliases such as `"gemini"` included).
     * @return A summary string detailing the outcome of the delegation, including whether it succeeded, timed out, or encountered an error. This summary is returned back to the calling agent.
     */
    suspend fun executeDelegation(taskDescription: String, targetModel: String): String = withContext(Dispatchers.IO) {
        // Tool arguments arrive as raw JSON strings produced by the LLM, so the
        // provider id is parsed (with legacy aliases) on the way in. Unknown ids
        // surface as a typed error rather than silently falling through.
        val provider = CloudProvider.fromId(targetModel)
            ?: return@withContext "Error: Unsupported target model '$targetModel'." +
                " Supported models: ${CloudProvider.entries.joinToString { it.id }}."

        val client = cloudLlmClientFactory.createClient(provider) as? LLMClient

        if (client == null) {
            // Name the real cause — a missing key, the "Block network from local model"
            // restriction, or a refused address — so the model can relay the right remedy.
            val cause = cloudLlmClientFactory.unavailabilityOf(provider)?.message(provider)
                ?: "Check the provider's settings."
            return@withContext "Error: Client for '${provider.id}' could not be initialized. $cause"
        }

        return@withContext try {
            val model = cloudLlmModelResolver.resolveModel(provider) as LLModel

            networkActivityTracker.recordOutbound()
            // Apply a 60-second timeout for the external API call
            val result = withTimeoutOrNull(LLM_CALL_TIMEOUT_MS) {
                val stream = client.executeStreaming(prompt("default") { user(taskDescription) }, model)
                stream
                    // Told again per frame, so a long answer keeps the indicator "online".
                    .onEach { networkActivityTracker.recordOutbound() }
                    .mapNotNull { frame -> (frame as? StreamFrame.TextDelta)?.text }
                    .toList()
                    .joinToString("")
            }

            if (result.isNullOrBlank()) {
                "Error: Task delegation to '${provider.id}' timed out or returned empty after 60 seconds."
            } else {
                "Success: Task completed by '${provider.id}'. Response:\n$result"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The result becomes the node's output, the console line and the model's
            // next observation — scrubbed, since a provider error can quote its key.
            "Error: Task delegation failed due to an exception: ${CloudErrorSanitizer.sanitize(e)}"
        }
    }

    private companion object {
        /** Maximum wall-clock time, in milliseconds, allowed for a single delegated cloud-LLM call. */
        const val LLM_CALL_TIMEOUT_MS: Long = 60_000L
    }
}
