package app.knotwork.android.domain.services

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.NodeInference
import app.knotwork.android.domain.engine.structured.EngineStructuredInferenceClient
import app.knotwork.android.domain.engine.structured.GateResult
import app.knotwork.android.domain.engine.structured.RepairListener
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.prompt.ChatTranscript
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Decides what a newly extracted memory fact is relative to a stored fact it lands
 * close to: the same fact reworded, an update of it, or a different fact.
 *
 * Embedding similarity finds the candidate but cannot make this call. On the
 * bundled on-device embedder a rewording of a fact, a correction of it, and a
 * different fact of the same shape ("allergic to peanuts" / "allergic to cats") can
 * all score above [MemoryVectorSimilarity.NEAR_DUPLICATE_THRESHOLD], and Cyrillic
 * text collapses to nearly one direction. The judge therefore reads the two texts: it
 * runs [DefaultPrompts.MemorySupersede.INSTRUCTION] once through the local model,
 * constrained by [StructuredOutputGate.runToken] to one verdict word, with the same
 * repair ceiling and counter as the other structured-output consumers.
 *
 * The call is made outside a pipeline run (after a conversation, like memory
 * extraction), so it goes through [NodeInference.Unrecorded]: the user's sampler,
 * nothing recorded.
 *
 * Never throws for a model that fails to answer — it returns
 * [SupersedeVerdict.UNDECIDED], which the caller must treat as "keep both facts".
 * Cancellation propagates.
 *
 * @property llmInferenceEngine Local model the verdict is asked of.
 * @property structuredOutputGate Validate-and-repair gate holding the reply to one
 *   verdict word.
 * @property runSettings Source of the configured repair ceiling
 *   ([RunSettings.structuredOutputMaxRepairs]).
 * @property metricsRepository Sink for the repair-attempt counter, keyed
 *   [METRICS_KEY].
 */
class MemorySupersedeJudge @Inject constructor(
    private val llmInferenceEngine: LlmInferenceEngine,
    private val structuredOutputGate: StructuredOutputGate,
    private val runSettings: RunSettings,
    private val metricsRepository: MetricsRepository,
) {

    /**
     * Asks the local model what [incoming] is relative to [stored].
     *
     * @param stored Text of the fact already in memory.
     * @param incoming Text of the fact just extracted.
     * @return The model's verdict, or [SupersedeVerdict.UNDECIDED] when the reply
     *   named no verdict after every repair or the inference failed.
     */
    suspend fun judge(stored: String, incoming: String): SupersedeVerdict {
        val maxRepairs = runSettings.structuredOutputMaxRepairs.first()
        val result = try {
            structuredOutputGate.runToken(
                inference = EngineStructuredInferenceClient(llmInferenceEngine, NodeInference.Unrecorded),
                prompt = prompt(stored = stored, incoming = incoming),
                allowed = VERDICT_BY_TOKEN.keys,
                nodeName = METRICS_KEY,
                maxRepairs = maxRepairs,
                listener = RepairListener { _, _, _ -> metricsRepository.recordStructuredOutputRepair(METRICS_KEY) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Supersede judge inference failed")
            return SupersedeVerdict.UNDECIDED
        }
        return when (result) {
            is GateResult.Success -> VERDICT_BY_TOKEN.getValue(result.value)
            is GateResult.Failed -> {
                Timber.tag(TAG).w("Supersede judge gave no verdict after %d repairs", result.repairs)
                SupersedeVerdict.UNDECIDED
            }
        }
    }

    /** Shared constants and the prompt builder, public so a measurement can send the exact prompt. */
    companion object {
        private const val TAG = "MemorySupersedeJudge"

        /**
         * Synthetic key under which the judge's structured-output repair attempts are
         * counted in [app.knotwork.android.domain.models.AgentMetrics.repairAttemptsPerNode]
         * (the judge is not a pipeline node).
         */
        const val METRICS_KEY: String = "MEMORY_SUPERSEDE"

        private val VERDICT_BY_TOKEN: Map<String, SupersedeVerdict> = mapOf(
            DefaultPrompts.MemorySupersede.VERDICT_SAME to SupersedeVerdict.SAME,
            DefaultPrompts.MemorySupersede.VERDICT_UPDATE to SupersedeVerdict.UPDATE,
            DefaultPrompts.MemorySupersede.VERDICT_DIFFERENT to SupersedeVerdict.DIFFERENT,
        )

        /**
         * Builds the full prompt: the instruction, then the stored fact and the new
         * fact, each rendered through [ChatTranscript.entry] so that neither text can
         * open a line of its own — a fact containing a line break followed by
         * `NEW: …` stays indented under its own entry.
         *
         * @param stored Text of the fact already in memory.
         * @param incoming Text of the fact just extracted.
         * @return The prompt sent to the model.
         */
        fun prompt(stored: String, incoming: String): String = DefaultPrompts.MemorySupersede.INSTRUCTION + "\n\n" +
            ChatTranscript.entry(prefix = "STORED: ", content = stored) + "\n" +
            ChatTranscript.entry(prefix = "NEW: ", content = incoming) + "\n\nANSWER: "
    }
}

/**
 * What a newly extracted memory fact is relative to a stored one, as decided by
 * [MemorySupersedeJudge].
 */
enum class SupersedeVerdict {
    /** The new fact says the same thing; nothing needs to be written. */
    SAME,

    /** The new fact changes the stored one; the stored one no longer holds. */
    UPDATE,

    /** The new fact is about something else, or both can hold at once. */
    DIFFERENT,

    /** The model gave no verdict; the caller keeps both facts. */
    UNDECIDED,
}
