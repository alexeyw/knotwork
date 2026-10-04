package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.EngineStructuredInferenceClient
import app.knotwork.android.domain.engine.structured.GateResult
import app.knotwork.android.domain.engine.structured.RepairListener
import app.knotwork.android.domain.engine.structured.StructuredInferenceClient
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case for evaluating an [NodeType.IF_CONDITION] node in a pipeline.
 *
 * This use case determines whether the pipeline execution should proceed
 * along the "True" branch or the "False" branch based on the node's configuration.
 *
 * For the LLM-classified path the model's verdict is run through the
 * [StructuredOutputGate] as a constrained `{"True","False"}` token: a reply that
 * contains neither word is handed back to the model for a bounded number of
 * repair re-inferences before the gate gives up. **Per-node failure policy:**
 * an exhausted gate (or an inference error) does **not** fail the run — the
 * node keeps its `False` default branch — but the failure is reported via
 * [Outcome.gateFailed] so the executor can surface a console Error and the
 * repair attempts are counted; there are no more silent forks.
 *
 * The verdict is a constrained `{"True","False"}` token rather than a JSON
 * payload, so when the node selects a cloud provider its native-JSON capability
 * does not shorten the repair budget — the configured budget is always used.
 *
 * @property llmInferenceEngine The engine used to evaluate free-form prompt conditions via LLM.
 * @property structuredOutputGate Validate-and-repair gate wrapping the LLM verdict.
 * @property runSettings Source of the configured repair ceiling.
 * @property cloudStructuredFactory Builds a cloud-backed gate client when the
 *   node carries a [NodeModel.cloudProvider]; falls back to the local engine
 *   when the cloud provider is unavailable.
 */
class EvaluateIfConditionUseCase @Inject constructor(
    private val llmInferenceEngine: LlmInferenceEngine,
    private val structuredOutputGate: StructuredOutputGate,
    private val runSettings: RunSettings,
    private val cloudStructuredFactory: CloudStructuredInferenceClientFactory,
) {

    /**
     * Outcome of an IF_CONDITION evaluation.
     *
     * @property value The branch decision: `true` → "True" edge, `false` → "False" edge.
     * @property gateFailed `true` only when the LLM path ran the structured-output
     *   gate and it exhausted its repairs (or the inference errored), so the
     *   executor can emit a console Error for the silent-fork-free fallback. Always
     *   `false` for the keyword / complexity / no-config paths (no gate involved).
     * @property unavailableProvider The provider id the node chose when it could not be used
     *   and the local engine answered instead, so the executor can say so — `null` when the
     *   node chose none, or got the one it chose.
     */
    data class Outcome(val value: Boolean, val gateFailed: Boolean = false, val unavailableProvider: String? = null)

    /**
     * Evaluates the condition against the provided input text.
     *
     * The evaluation is done in the following priority:
     * 0. **Image presence:** If [NodeModel.conditionHasImage] is `true`, returns true as soon as
     *    the run input carries an image ([hasImage]). A deterministic, no-LLM check that runs
     *    before everything else so a pipeline can fork on "did the user send a picture?".
     * 1. **Keywords:** If [NodeModel.conditionKeywords] is provided, returns true if the input contains any of the keywords (case-insensitive).
     * 2. **Complexity:** If [NodeModel.conditionComplexity] is provided, returns true if the input length exceeds the complexity threshold.
     * 3. **Prompt:** If [NodeModel.conditionPrompt] is provided, uses the LLM (via the gate) to classify the input.
     *
     * If no configuration is provided, it defaults to false.
     *
     * @param node The [NodeModel] containing the condition configuration. Must be of type [NodeType.IF_CONDITION].
     * @param inputText The text (context or user message) to evaluate.
     * @param hasImage `true` when the current run's input carries an image attachment. Only
     *   consulted when the node opts in via [NodeModel.conditionHasImage]; defaults to `false`.
     * @param repairListener Sink the gate reports repair attempts to; defaults to
     *   [RepairListener.NONE]. The executor passes a buffering listener so it can
     *   surface each attempt as a console line.
     * @return The [Outcome] carrying the branch decision and whether the gate failed.
     */
    suspend operator fun invoke(
        node: NodeModel,
        inputText: String,
        hasImage: Boolean = false,
        repairListener: RepairListener = RepairListener.NONE,
    ): Outcome {
        require(node.type == NodeType.IF_CONDITION) { "Node must be an IF_CONDITION type" }

        // 0. Deterministic image-presence check (opt-in). Runs before the blank-input
        //    short-circuit: an image-only message has empty text but should still fork True.
        if (node.conditionHasImage == true) {
            return Outcome(value = hasImage)
        }

        if (inputText.isBlank()) return Outcome(value = false)

        // 1 & 2. Deterministic text heuristics: a keyword match (case-insensitive
        //    substring) or the input clearing the complexity (length) threshold.
        if (matchesKeywords(node, inputText) || matchesComplexity(node, inputText)) {
            return Outcome(value = true)
        }

        // 3. Evaluate using LLM (through the gate) if a prompt is provided
        val conditionPrompt = node.conditionPrompt
        if (!conditionPrompt.isNullOrBlank()) {
            return evaluateWithGate(node, conditionPrompt, inputText, repairListener)
        }

        return Outcome(value = false)
    }

    /**
     * Returns `true` when [inputText] contains any of the node's comma-separated
     * [NodeModel.conditionKeywords] (case-insensitive substring match). Blank or
     * absent keywords never match.
     */
    private fun matchesKeywords(node: NodeModel, inputText: String): Boolean {
        val keywords = node.conditionKeywords
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (keywords.isEmpty()) return false
        val lowerInput = inputText.lowercase()
        return keywords.any { lowerInput.contains(it) }
    }

    /**
     * Returns `true` when the node declares a positive [NodeModel.conditionComplexity]
     * threshold and [inputText] is at least that many characters long.
     */
    private fun matchesComplexity(node: NodeModel, inputText: String): Boolean {
        val threshold = node.conditionComplexity ?: return false
        return threshold > 0 && inputText.length >= threshold
    }

    /**
     * Runs the LLM classification through the [StructuredOutputGate] as a
     * constrained `{"True","False"}` token and maps the result onto an [Outcome].
     *
     * @param node The IF_CONDITION node (its label keys the repair metric / console line).
     * @param conditionPrompt The user-authored condition to classify against.
     * @param inputText The upstream text being classified.
     * @param repairListener Sink for repair attempts during the gate run.
     * @return `True`/`False` on a valid verdict; the `False` default with
     *   [Outcome.gateFailed] set when the gate exhausts its repairs or errors.
     */
    private suspend fun evaluateWithGate(
        node: NodeModel,
        conditionPrompt: String,
        inputText: String,
        repairListener: RepairListener,
    ): Outcome {
        val prompt = DefaultPrompts.renderTemplate(
            DefaultPrompts.IfCondition.EVALUATION_TEMPLATE,
            mapOf(
                "CONDITION_PROMPT" to conditionPrompt,
                "INPUT_TEXT" to inputText,
            ),
        )
        val maxRepairs = runSettings.structuredOutputMaxRepairs.first()
        val (inference, unavailableProvider) = resolveInference(node)

        val result = try {
            structuredOutputGate.runToken(
                inference = inference,
                prompt = prompt,
                allowed = setOf(TOKEN_TRUE, TOKEN_FALSE),
                nodeName = node.label,
                maxRepairs = maxRepairs,
                listener = repairListener,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An inference-layer failure is a gate failure for policy purposes:
            // keep the default branch but mark it so the executor surfaces it.
            return Outcome(value = false, gateFailed = true, unavailableProvider = unavailableProvider)
        }

        return when (result) {
            is GateResult.Success ->
                Outcome(value = result.value == TOKEN_TRUE, unavailableProvider = unavailableProvider)
            is GateResult.Failed -> Outcome(value = false, gateFailed = true, unavailableProvider = unavailableProvider)
        }
    }

    /**
     * Picks the gate's inference client for [node]: a cloud-backed client when the node
     * selects a cloud provider that can be used now, otherwise the local LiteRT engine.
     *
     * An unavailable provider — missing credentials, the local-only restriction, an unknown
     * id — falls back to the local engine, as the TOOL and system nodes do, and is reported
     * back so the executor writes the same console line they write. It used to fall back in
     * silence: a user whose condition ran on the phone instead of the provider they chose had
     * no way to tell.
     *
     * The use case streams no tokens, so the cloud client's token hook is a no-op.
     *
     * @return The client, and the id of the provider that was chosen but could not be used.
     */
    private suspend fun resolveInference(node: NodeModel): Pair<StructuredInferenceClient, String?> {
        val providerId = node.cloudProvider?.takeIf { it.isNotBlank() }
        val cloud = providerId?.let { CloudProvider.fromId(it) }?.let { cloudStructuredFactory.create(it) { } }
        return if (cloud != null) {
            cloud.inference to null
        } else {
            EngineStructuredInferenceClient(llmInferenceEngine) to providerId
        }
    }

    private companion object {
        /** Canonical accepted verdict tokens for the constrained classification. */
        const val TOKEN_TRUE = "True"
        const val TOKEN_FALSE = "False"
    }
}
