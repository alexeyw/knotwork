package app.knotwork.android.domain.engine.executors

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.PipelineExecutionDefaults
import app.knotwork.android.domain.engine.CloudErrorSanitizer
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.engine.retry.CollectingCloudRetryListener
import app.knotwork.android.domain.engine.structured.ReasoningBlockSplitter
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Executor for [NodeType.CLOUD][app.knotwork.android.domain.models.NodeType.CLOUD] nodes.
 *
 * Streams a response from one of the supported cloud LLM providers (OpenAI, Anthropic,
 * Google, DeepSeek, Ollama) using the Koog client abstraction. The active provider is
 * either taken from `node.cloudProvider` or auto-detected from the first configured API
 * key when the node is set to `"auto"`. The fully assembled `inputText` is sent verbatim:
 * `NodeContextBuilder` has already concatenated the context blocks selected by
 * [NodeContextConfig][app.knotwork.android.domain.models.NodeContextConfig], so the executor
 * must not re-fetch chat history or memory itself.
 *
 * Tokens are forwarded to the orchestrator as
 * [Thinking][app.knotwork.android.domain.models.AgentOrchestratorState.Thinking] /
 * [Answering][app.knotwork.android.domain.models.AgentOrchestratorState.Answering] states
 * during streaming, with the final aggregated text emitted as a
 * [NodeOutput.Result] together with an approximate token count for metrics.
 *
 * **Privacy contract — attachments never reach the cloud.** A run's image
 * attachment is delivered by [GraphExecutionEngine][app.knotwork.android.domain.engine.GraphExecutionEngine]
 * exclusively to an on-device `LITE_RT` node (via [ExecutionScope.imagePath]).
 * This executor deliberately ignores [ExecutionScope.imagePath] and only ever
 * sends the assembled text `inputText`, so a user's image is never transmitted
 * off-device. The send-time pre-flight guard blocks an image message whose
 * pipeline starts on a CLOUD node before it can run.
 */
class CloudLlmNodeExecutor @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val metricsRepository: MetricsRepository,
    private val cloudLlmClientFactory: CloudLlmClientFactory,
    private val cloudLlmModelResolver: CloudLlmModelResolver,
    private val networkActivityTracker: NetworkActivityTracker,
) : NodeExecutor {

    override fun execute(
        node: NodeModel,
        inputText: String,
        sessionId: String,
        originalPrompt: String,
        runId: String?,
        scope: ExecutionScope,
    ): Flow<NodeOutput> = channelFlow {
        val systemPromptPrefix = settingsRepository.systemPromptPrefix.first()
        val nodeSystemPrompt = node.systemPrompt ?: DefaultPrompts.Cloud.SYSTEM_FALLBACK
        val baseSystemPrompt = "$systemPromptPrefix\n$nodeSystemPrompt\n"

        // `inputText` is the assembled context produced upstream by NodeContextBuilder
        // according to the node's NodeContextConfig. Re-fetching chat history or
        // long-term memory here would silently override those flags and break the
        // per-node context-config contract.
        val fullPrompt = "$baseSystemPrompt\n\n$inputText\nAGENT: "

        val startTime = System.currentTimeMillis()

        // `node.cloudProvider` is the persisted UI selection: a real provider id, the
        // sentinel "auto" string, or `null`. `null`/"auto" trigger key-based detection;
        // everything else is parsed through CloudProvider.fromId so legacy aliases
        // (e.g. "gemini") still resolve correctly.
        val configuredProvider = node.cloudProvider
        val selectedProvider: CloudProvider? = if (
            configuredProvider == null || configuredProvider == CloudProvider.AUTO_KEY
        ) {
            autoDetectProvider()
        } else {
            CloudProvider.fromId(configuredProvider)
        }

        // Surfaces each transient-failure retry as a console line **at the moment
        // it happens**. This used to buffer and drain after the stream finished,
        // which put both lines at the end of the run: measured on the wire the
        // retries were at 1 s and 3 s, but the user saw nothing at all until the
        // whole thing failed, then everything at once. `onRetry` is not a suspend
        // callback, which is why the node is a `channelFlow` — `trySend` is the
        // only way to publish from inside it. Dropping is not a practical concern:
        // the channel buffers 64 elements and the retry ceiling is single digits.
        val retryListener = CloudRetryListener { provider, attempt, maxRetries ->
            val line = CollectingCloudRetryListener.RetryAttempt(
                provider = provider,
                attempt = attempt,
                maxRetries = maxRetries,
            ).consoleMessage()
            trySend(NodeOutput.Console(ConsoleEventType.CloudRetry, line))
        }

        // A node that cannot reach a provider has *failed*; it has not answered.
        // Feeding the explanation into the token stream (as this executor used to do)
        // made `NodeExecutionResult.error` stay null, so the engine read the run as a
        // success and passed the sentence "Error: … not configured" downstream as if it
        // were the model's reply. Configuration failures therefore terminate the node
        // through the same typed-error path as a mid-stream exception.
        if (selectedProvider == null) {
            emitFailure(NO_PROVIDER_CONFIGURED)
            return@channelFlow
        }
        val client = cloudLlmClientFactory.createClient(selectedProvider, retryListener) as? LLMClient
        if (client == null) {
            // A null client has several causes with different remedies — no key, the
            // "Block network from local model" restriction, an Ollama address that is not
            // local while it is on, a refused cleartext address. The factory decided which
            // one when it refused, so it is asked rather than guessed: guessing here once
            // blamed the restriction (or a missing key) for an address the cleartext rule
            // had refused.
            val cause = cloudLlmClientFactory.unavailabilityOf(selectedProvider)
            emitFailure(cause?.message(selectedProvider) ?: providerUnavailable(selectedProvider.id))
            return@channelFlow
        }
        // The resolver owns the per-provider configured-id ↔ default fallback,
        // so the executor stays out of the data layer's settings plumbing.
        val model = cloudLlmModelResolver.resolveModel(selectedProvider) as LLModel
        // Privacy-status pulse for the More tab footer. Recorded right before
        // the network call so the timestamp reflects the actual outbound moment.
        networkActivityTracker.recordOutbound()
        val responseStream = client.executeStreaming(prompt("default") { user(fullPrompt) }, model)

        val accumulatedResponse = StringBuilder()
        var emittedThinking = false
        var approximateTokenCount = 0
        // Whether the provider ever said *why* it stopped. A dropped connection ends the
        // stream with no finish reason, which is otherwise indistinguishable from a
        // complete answer — see [providerReportsFinishReason].
        var finishReason: String? = null
        // Prompt + completion tokens as the provider itself counted them, when it
        // counted them. This is the number a run ceiling should be charged, and the
        // one this executor used to throw away: the delta count below sees only the
        // answer, while a loop's real cost is dominated by the prompt being re-sent
        // on every call. Stays null for a provider that reports no usage — measured
        // in Koog 1.1.1: OpenAI (which asks for `include_usage`), Anthropic and
        // Google populate it, DeepSeek only if its API volunteers it, and the Ollama
        // client emits no end frame at all.
        var reportedTokenCount: Int? = null
        // The completion half on its own, kept apart because the run ceiling and
        // the generation-rate display want different numbers: the ceiling is
        // about what the call cost, the rate is about what it produced.
        var reportedOutputTokenCount: Int? = null

        try {
            responseStream.collect { frame ->
                // Told again on every frame: an answer can stream for minutes, and the
                // indicator must not read "no network calls in last 2 m" while it does.
                networkActivityTracker.recordOutbound()
                if (frame is StreamFrame.End) {
                    finishReason = frame.finishReason
                    val meta = frame.metaInfo
                    reportedTokenCount = meta.totalTokensCount
                        ?: listOfNotNull(meta.inputTokensCount, meta.outputTokensCount)
                            .takeIf { it.isNotEmpty() }
                            ?.sum()
                    reportedOutputTokenCount = meta.outputTokensCount
                    return@collect
                }
                val token = (frame as? StreamFrame.TextDelta)?.text ?: return@collect
                accumulatedResponse.append(token)
                // Cloud streams emit token-sized text deltas; counting per emission keeps the
                // metric consistent with the local LiteRT path. Length-based estimation was
                // double-counting characters and inflating the recorded token total.
                approximateTokenCount += 1

                if (!emittedThinking) {
                    send(NodeOutput.State(AgentOrchestratorState.Thinking(accumulatedResponse.toString())))
                    emittedThinking = true
                } else {
                    send(NodeOutput.State(AgentOrchestratorState.Answering(accumulatedResponse.toString())))
                }
            }
        } catch (e: CancellationException) {
            // Preserve structured-concurrency cancellation: a broad `catch (Exception)`
            // would silently swallow cancellation and leave the parent coroutine running.
            throw e
        } catch (e: Exception) {
            // Provider errors quote the failing request, and a provider that authenticates
            // by query parameter (Google) therefore hands us its own API key inside the
            // message. Scrub before it reaches the console, the trace or logcat — passing
            // the message rather than the throwable to Timber keeps the key out of the
            // logged stack trace too.
            val safeMessage = CloudErrorSanitizer.sanitize(e)
            // The exception type is kept because it is the fastest triage signal and
            // carries no credential; only the throwable itself is withheld, since
            // logging it would print the unscrubbed message inside the stack trace.
            Timber.tag("PipelineDebug").e(
                "[NODE_ERR] type=${node.type.name} id=${node.id} " +
                    "CloudLlmNodeExecutor generation failed with ${e::class.simpleName}: $safeMessage",
            )
            send(NodeOutput.State(AgentOrchestratorState.Error(safeMessage)))
            send(NodeOutput.Result(NodeExecutionResult(error = safeMessage)))
            return@channelFlow
        }

        // A provider whose connection dies mid-answer does not always raise: the
        // OpenAI-compatible clients end the stream normally and simply omit the finish
        // reason, so the half-written answer would otherwise be handed on as a complete
        // one. Measured on a stub that cut the socket mid-stream: identical frames in both
        // cases apart from `finishReason` (null when cut, "stop" when complete).
        if (providerReportsFinishReason(selectedProvider) && finishReason == null) {
            emitFailure(truncatedResponse(selectedProvider.id))
            return@channelFlow
        }

        // Prefer what the provider counted; fall back to the delta count so a
        // provider that reports nothing still charges the ceiling something rather
        // than running for free.
        val chargedTokenCount = reportedTokenCount ?: approximateTokenCount
        val tokensEstimated = reportedTokenCount == null

        val endTime = System.currentTimeMillis()
        // The metrics display divides this by elapsed time to show a generation
        // rate, so it gets the *completion* count only. Prompt tokens were not
        // produced during this call — folding them in would inflate the figure
        // and make it incomparable with the local-inference path that feeds the
        // same counter.
        metricsRepository.updateMetrics(endTime - startTime, reportedOutputTokenCount ?: approximateTokenCount)

        // Reasoning models reach this executor too — DeepSeek's R1 line natively,
        // and any Qwen3 served through an OpenAI-compatible endpoint or Ollama.
        // Split for the same reasons as the on-device path: the text below is
        // persisted as the agent's message, replayed into the next turn's
        // `--- Chat History ---`, and scanned brace-to-brace by
        // `JsonPayloadExtractor`. See `LiteRtNodeExecutor` for why the scratchpad
        // is reported on the console rather than carried in the node result.
        val split = ReasoningBlockSplitter.split(accumulatedResponse.toString().trim())
        val fullResponseText = split.answer
        split.reasoning?.let { reasoning ->
            trySend(
                NodeOutput.Console(
                    ConsoleEventType.NodeExecution,
                    "CLOUD '${node.label}' removed a ${reasoning.length}-character reasoning block " +
                        "from its answer",
                ),
            )
        }

        // Record the cloud provider as the answering "model" so the root OUTPUT
        // attributes the message to the cloud source rather than to whatever local
        // model happens to be active. selectedProvider is non-null on this success
        // path (a null provider returns early above).
        selectedProvider?.let { provider ->
            scope.generatingModel?.let { gm ->
                gm.cloudLabel = provider.id
                gm.localModelPath = null
            }
        }

        kotlinx.coroutines.delay(PipelineExecutionDefaults.NODE_RESULT_EMIT_DELAY_MS)

        send(
            NodeOutput.Result(
                NodeExecutionResult(
                    outputText = fullResponseText,
                    tokenCount = chargedTokenCount,
                    tokensEstimated = tokensEstimated,
                ),
            ),
        )
    }

    /**
     * Terminates the node with a typed failure, using the same two-emission shape as
     * the mid-stream exception path: an [AgentOrchestratorState.Error] for the live UI
     * and a [NodeExecutionResult] carrying `error`, which is what
     * [GraphExecutionEngine][app.knotwork.android.domain.engine.GraphExecutionEngine]
     * inspects to stop the run instead of forwarding the text to the next node.
     *
     * @param reason User-facing explanation of why no cloud call was attempted.
     */
    private suspend fun ProducerScope<NodeOutput>.emitFailure(reason: String) {
        Timber.tag("PipelineDebug").e("[NODE_ERR] type=CLOUD $reason")
        send(NodeOutput.State(AgentOrchestratorState.Error(reason)))
        send(NodeOutput.Result(NodeExecutionResult(error = reason)))
    }

    /**
     * Picks the first [CloudProvider] for which an API key is configured.
     *
     * Order mirrors the historical "auto" routing priority (Google → Anthropic → OpenAI →
     * DeepSeek) so existing pipelines keep their previous default behaviour. Returns
     * `null` when no provider has credentials, which the caller surfaces to the user as
     * "No cloud provider configured or selected".
     */
    private suspend fun autoDetectProvider(): CloudProvider? {
        if (!apiKeyRepository.getGoogleKey().first().isNullOrBlank()) return CloudProvider.GOOGLE
        if (!apiKeyRepository.getAnthropicKey().first().isNullOrBlank()) return CloudProvider.ANTHROPIC
        if (!apiKeyRepository.getOpenAIKey().first().isNullOrBlank()) return CloudProvider.OPENAI
        if (!apiKeyRepository.getDeepSeekKey().first().isNullOrBlank()) return CloudProvider.DEEPSEEK
        return null
    }

    /**
     * Whether an absent finish reason is evidence of a truncated answer for [provider].
     *
     * Only true where it was actually measured against a stub, because the inverse
     * mistake — treating a healthy stream as truncated — fails working runs:
     *
     * - `DEEPSEEK` / `OPENAI` — **checked**. Measured on DeepSeek: a socket cut mid-stream
     *   ends the flow with `End(finishReason = null)` and no exception, while a complete
     *   stream ends with `End(finishReason = "stop")`. OpenAI shares the same streaming
     *   implementation (`AbstractOpenAILLMClient`), so the signal is the same one.
     * - `GOOGLE` — **checked**, and already correct without help: its client throws
     *   `IncompleteStreamException` on a cut, so the guard below never fires for it.
     * - `OLLAMA` — **excluded**. Its client never emits a finish reason at all, so absence
     *   carries no information; enabling the check would fail every healthy Ollama run.
     * - `ANTHROPIC` — **excluded pending measurement**. The harness could not produce a
     *   stream its parser accepts, so there is no evidence either way, and a guess here
     *   is exactly the failure mode this task exists to avoid.
     */
    private fun providerReportsFinishReason(provider: CloudProvider): Boolean = when (provider) {
        CloudProvider.OPENAI, CloudProvider.DEEPSEEK, CloudProvider.GOOGLE -> true
        CloudProvider.ANTHROPIC, CloudProvider.OLLAMA -> false
    }

    private companion object {
        /** The provider stopped sending without ever saying the answer was finished. */
        fun truncatedResponse(providerId: String): String =
            "The response from '$providerId' was cut off before it finished — the connection " +
                "ended without a completion signal. The partial answer was discarded; try again."

        /** No provider is selected on the node and no configured key could be auto-detected. */
        const val NO_PROVIDER_CONFIGURED: String =
            "No cloud provider is configured. Add a provider API key in Settings, " +
                "or select a provider on this Cloud node."

        /**
         * The factory refused a client but no longer reports a cause — the settings changed
         * between the two reads. Rare, and not worth a guess that could name the wrong setting.
         */
        fun providerUnavailable(providerId: String): String =
            "Cloud provider '$providerId' is not available right now. Check its settings and try again."
    }
}
