package app.knotwork.android.domain.engine.golden

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.engine.structured.CloudStructuredClient
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredInferenceClient
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RouteLabels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * The scripted model of the golden harness, behind every seam a node can reach a model
 * through: the on-device [LlmInferenceEngine], the cloud client a `CLOUD` node streams from,
 * and the cloud structured-inference client a router or tool node may use.
 *
 * It answers per node and per visit: the [GoldenNodeTracker] says which node is calling, the
 * scenario's [GoldenScript] may say what that visit answers, and otherwise a default by node
 * type keeps the run on its main path — the first labelled branch of a router, `Pass`, `True`,
 * a three-item plan, a question with two options, arguments for the configured tool, or a short
 * text naming the node. Every call is recorded with its full prompt, so a change to how a node
 * builds its prompt shows in the trace even when the routing does not move.
 *
 * Answers are streamed in word-sized chunks, as a real model's are, because the token count
 * a node reports — and the token ceiling it charges — counts chunks.
 *
 * @param tracker Which node is executing.
 * @param script The scenario's overrides.
 * @param rootPipelineId Id of the scenario's root pipeline.
 * @param log The run's event log.
 */
internal class GoldenModel(
    private val tracker: GoldenNodeTracker,
    private val script: GoldenScript,
    private val rootPipelineId: String,
    private val log: GoldenEventLog,
) {

    private val callsPerVisit = mutableMapOf<String, Int>()

    /**
     * Forgets the previous attempt's call counts, as [GoldenNodeTracker.startAttempt] forgets
     * its visits: the first call of a re-executed visit is call 1 again, so a scripted repair
     * sequence replays from its start.
     */
    fun startAttempt() {
        callsPerVisit.clear()
    }

    /** The on-device engine every local node calls. */
    val localEngine: LlmInferenceEngine = object : LlmInferenceEngine {
        override suspend fun initialize(
            modelPath: String,
            enableVision: Boolean,
            enableAudio: Boolean,
        ): Result<Unit, AppError> = log.violation("The golden harness loads no model; LoadModelUseCase is a fake")

        override val isInitialized: Boolean = true
        override val currentModelPath: String = MODEL_PATH
        override val isVisionEnabled: Boolean = false
        override val isAudioEnabled: Boolean = false
        override val activeBackend: LocalBackend = LocalBackend.CPU

        override fun transcribe(audioPath: String, prompt: String): Flow<String> =
            log.violation("No golden scenario sends audio")

        // Cold, as the real engine's stream is: the call happens — and is recorded — when a
        // node collects the stream, not when it builds it.
        override fun generateResponseStream(prompt: String, imagePath: String?, temperature: Float?): Flow<String> =
            flow { chunks(answer("local", prompt, temperature, imagePath)).forEach { emit(it) } }

        override fun close() = log.record("model.close")

        override suspend fun unload() = log.record("model.unload")
    }

    /** The cloud client factory a `CLOUD` node asks for its provider's client. */
    val cloudClientFactory: CloudLlmClientFactory = object : CloudLlmClientFactory {
        override suspend fun createClient(provider: CloudProvider, retryListener: CloudRetryListener): Any =
            ScriptedCloudClient(provider)

        override suspend fun unavailabilityOf(provider: CloudProvider): CloudClientUnavailability? = null
    }

    /** Resolves every provider to one fixed model descriptor. */
    val cloudModelResolver: CloudLlmModelResolver = object : CloudLlmModelResolver {
        override suspend fun resolveModel(provider: CloudProvider): Any =
            LLModel(LLMProvider.OpenAI, CLOUD_MODEL_ID, emptyList())
    }

    /** The cloud structured-inference factory a router, evaluator or tool node may use. */
    val structuredFactory: CloudStructuredInferenceClientFactory =
        CloudStructuredInferenceClientFactory { provider, onToken ->
            CloudStructuredClient(
                inference = StructuredInferenceClient { prompt, temperature ->
                    answer("cloud-structured ${provider.id}", prompt, temperature, imagePath = null)
                        .also { answer -> chunks(answer).forEach { onToken(it) } }
                },
                supportsNativeJson = false,
            )
        }

    private inner class ScriptedCloudClient(private val provider: CloudProvider) : LLMClient() {
        override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            log.violation("CLOUD nodes stream; nothing calls execute")

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            flow {
                // Roles, parameters and the tool list are part of the call: a refactoring that
                // moved the instruction into a system message, or set a temperature, shows.
                val text = prompt.messages.joinToString("\n") { "[${it.role}] ${it.textContent()}" }
                val answer = answer(
                    "cloud ${provider.id} model=${model.id} prompt=${prompt.id} tools=${tools.size}",
                    text,
                    temperature = prompt.params.temperature?.toFloat(),
                    imagePath = null,
                )
                chunks(answer).forEach { emit(StreamFrame.TextDelta(it)) }
                emit(StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty))
            }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            log.violation("Nothing moderates in a golden run")

        override fun close() = Unit
    }

    private fun answer(seam: String, prompt: String, temperature: Float?, imagePath: String?): String {
        val position = tracker.current ?: log.violation("A model was called outside any node ($seam)")
        val visitKey = "${position.runId}/${position.node.id}/${position.visit}"
        val call = (callsPerVisit[visitKey] ?: 0) + 1
        callsPerVisit[visitKey] = call
        val answer = script.answerFor(rootPipelineId, position.pipeline.id, position.node.id, position.visit, call)
            ?: defaultAnswer(position)
        log.record(
            "model $seam node=${position.node.id} call=$call temperature=${temperature ?: "default"} " +
                "image=${if (imagePath == null) "none" else "attached"}",
            "prompt" to prompt,
            "answer" to answer,
        )
        return answer
    }

    private fun defaultAnswer(position: GoldenNodeTracker.Position): String {
        val node = position.node
        return when (node.type) {
            NodeType.INTENT_ROUTER ->
                position.pipeline.connections
                    .firstOrNull { it.sourceNodeId == node.id && !it.label.isNullOrBlank() }
                    ?.label
                    ?: "Unrouted"
            NodeType.EVALUATION -> RouteLabels.PASS
            NodeType.IF_CONDITION -> RouteLabels.TRUE
            // Three items, not two: the engine takes a queue's first item itself and hands
            // only the rest to `stepQueue`, so with two items the order `stepQueue` takes
            // them in would be invisible (measured — a LIFO mutation passed every 2-item run).
            NodeType.DECOMPOSITION -> listOf("first", "second", "third")
                .joinToString(", ", "[", "]") { quote("${node.label}: $it part") }
            NodeType.CLARIFICATION ->
                "{\"question\": ${quote("${node.label}: which one do you mean?")}, " +
                    "\"options\": [\"The first one\", \"The second one\"]}"
            NodeType.TOOL -> toolAnswer(node.toolName, node.label)
            else -> "${node.label} answer, visit ${position.visit}."
        }
    }

    private fun toolAnswer(configuredTool: String?, label: String): String {
        val autoSelect = configuredTool.isNullOrBlank() || configuredTool.equals("auto", ignoreCase = true)
        return if (autoSelect) {
            "{\"tool\": \"$DEFAULT_AUTO_TOOL\", \"arguments\": " +
                "${GoldenToolRepository.defaultArguments(DEFAULT_AUTO_TOOL, label)}}"
        } else {
            GoldenToolRepository.defaultArguments(configuredTool, label)
        }
    }

    companion object {
        /** Path of the one local model every golden run "loads". */
        const val MODEL_PATH: String = "/models/golden-model.litertlm"

        /** Display name of that model, as `$MODEL` renders it. */
        const val MODEL_NAME: String = "Golden Model"

        /** Id of the cloud model every provider resolves to. */
        const val CLOUD_MODEL_ID: String = "golden-cloud-model"

        /** The tool an auto-select node picks unless the scenario scripts another. */
        private const val DEFAULT_AUTO_TOOL = "search_tool"

        private fun quote(text: String): String = JSONObject.quote(text)

        /**
         * Splits [text] into word-sized chunks, each keeping its trailing whitespace, so the
         * concatenation is the original text.
         *
         * @param text The answer.
         * @return The chunks, at least one.
         */
        fun chunks(text: String): List<String> {
            if (text.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            val current = StringBuilder()
            text.forEach { char ->
                if (!char.isWhitespace() && current.isNotEmpty() && current.last().isWhitespace()) {
                    result += current.toString()
                    current.clear()
                }
                current.append(char)
            }
            result += current.toString()
            return result
        }
    }
}
