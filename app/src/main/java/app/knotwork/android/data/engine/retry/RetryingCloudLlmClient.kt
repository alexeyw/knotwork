package app.knotwork.android.data.engine.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/**
 * An [LLMClient] that sends each call to [delegate] and, when it fails, retries it as
 * [policy] decides — the project's replacement for Koog's `RetryingLLMClient`.
 *
 * The contract is Koog's, kept on purpose:
 * - a streamed answer is retried only before its first frame; once text has reached the
 *   caller a retry would repeat it, so a later failure passes through;
 * - cancellation is never retried;
 * - every operation is covered — generation, streaming, embeddings, moderation, model listing.
 *
 * What changed is the decision ([CloudRetryPolicy]): the HTTP status decides instead of a
 * number found in the text, the `Retry-After` header captured in [retryAfter] is honoured, and
 * a wait longer than the policy's ceiling fails the call with [RetryAfterExceededException]
 * instead of being slept through.
 *
 * Each retry is reported to [listener] just before it is sent, after its wait, so the console
 * line appears when the retry happens. (Koog had no such callback; a pass-through client used to
 * be interposed only to count invocations.)
 *
 * **Single-operation contract.** One instance serves one logical operation: the slot holds one
 * client's latest error header. The factories build a fresh client per call.
 *
 * @property delegate The real cloud client.
 * @property provider Wire id of the provider (e.g. `"openai"`), for the console line and errors.
 * @property policy Whether, and after how long, a failed attempt is retried.
 * @property listener Told about each retry just before it is sent.
 * @property retryAfter The client's captured `Retry-After`, or `null` when the client was
 *   built without capture — a wait named in the error text is still honoured then.
 * @property pause How a wait is spent; replaced only by tests.
 */
internal class RetryingCloudLlmClient(
    private val delegate: LLMClient,
    private val provider: String,
    private val policy: CloudRetryPolicy,
    private val listener: CloudRetryListener,
    private val retryAfter: RetryAfterSlot?,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
) : LLMClient() {

    override fun llmProvider(): LLMProvider = delegate.llmProvider()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        withRetry { delegate.execute(prompt, model, tools) }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            var attempt = 0
            while (true) {
                var firstFrameReceived = false
                retryAfter?.clear()
                try {
                    delegate.executeStreaming(prompt, model, tools).collect { frame ->
                        firstFrameReceived = true
                        emit(frame)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Once a frame has reached the caller, a retry would repeat it; and a failure
                    // raised by the caller's own collector arrives here only after a frame too.
                    if (firstFrameReceived) throw e
                    attempt = waitBeforeRetry(e, attempt)
                }
            }
        }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = withRetry { delegate.executeMultipleChoices(prompt, model, tools) }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        withRetry { delegate.moderate(prompt, model) }

    override suspend fun models(): List<LLModel> = withRetry { delegate.models() }

    override suspend fun embed(text: String, model: LLModel): List<Double> = withRetry { delegate.embed(text, model) }

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> =
        withRetry { delegate.embed(inputs, model) }

    override fun close() = delegate.close()

    override fun getStandardJsonSchemaGenerator(): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator()

    override fun getBasicJsonSchemaGenerator(): BasicJsonSchemaGenerator = delegate.getBasicJsonSchemaGenerator()

    /** Runs [block], retrying it as the policy decides. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            retryAfter?.clear()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt = waitBeforeRetry(e, attempt)
            }
        }
    }

    /**
     * Applies the policy to the failure of attempt [attempt]: waits and returns the next
     * attempt's index, or throws — the original [error], or [RetryAfterExceededException].
     */
    private suspend fun waitBeforeRetry(error: Exception, attempt: Int): Int {
        when (val decision = policy.decide(error, attempt, retryAfter?.take())) {
            CloudRetryPolicy.RetryDecision.GiveUp -> throw error
            is CloudRetryPolicy.RetryDecision.WaitTooLong -> throw RetryAfterExceededException(
                provider = provider,
                requested = decision.requested,
                ceiling = policy.maxDelay,
                rateLimited = CloudRetryPolicy.statusOf(error) == HTTP_TOO_MANY_REQUESTS,
                cause = error,
            )
            is CloudRetryPolicy.RetryDecision.Retry -> {
                pause(decision.delay)
                listener.onRetry(provider = provider, attempt = attempt + 1, maxRetries = policy.maxAttempts - 1)
                return attempt + 1
            }
        }
    }

    private companion object {
        /** Rate limiting. */
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
