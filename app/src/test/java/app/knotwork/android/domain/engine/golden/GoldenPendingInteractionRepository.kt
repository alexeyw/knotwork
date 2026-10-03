package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.repositories.PendingInteractionRepository

/**
 * In-memory store of parked interactions for the golden harness; records what the engine
 * parks and deletes.
 *
 * The request id and the time a request was raised are random and wall-clock respectively,
 * so neither enters the trace. The user's side — a decision or an answer recorded on a parked
 * request before the run is resumed — is applied by the harness through [decide] and
 * [answer], unrecorded, exactly as the app's answering surfaces write it.
 *
 * @param log The run's event log.
 */
internal class GoldenPendingInteractionRepository(private val log: GoldenEventLog) : PendingInteractionRepository {

    private val byRun = mutableMapOf<String, PendingInteraction>()

    /**
     * The interactions currently parked, keyed by run id.
     *
     * @return A snapshot of the store.
     */
    fun parked(): Map<String, PendingInteraction> = byRun.toMap()

    /**
     * Records the user's decision on the parked approval of [runId].
     *
     * @param runId The run the approval is parked on.
     * @param decision The decision.
     */
    fun decide(runId: String, decision: PendingDecision) {
        byRun[runId] = requireNotNull(byRun[runId]) { "No parked approval on $runId" }.copy(decision = decision)
    }

    /**
     * Records the user's answer to the parked clarification of [runId].
     *
     * @param runId The run the clarification is parked on.
     * @param text The answer.
     */
    fun answer(runId: String, text: String) {
        byRun[runId] = requireNotNull(byRun[runId]) { "No parked clarification on $runId" }.copy(answer = text)
    }

    override suspend fun save(interaction: PendingInteraction): Boolean {
        byRun[interaction.runId] = interaction
        log.record(
            buildString {
                append("pending.save ${interaction.runId} ${interaction.kind}")
                interaction.toolName?.let { append(" tool=$it") }
                interaction.risk?.let { append(" risk=$it") }
                interaction.ceilingAxis?.let { append(" axis=$it limit=${interaction.ceilingLimit}") }
                interaction.ceilingSpent?.let { append(" spent=$it") }
            },
            *listOfNotNull(
                interaction.toolArgs?.let { "arguments" to it },
                interaction.question?.let { "question" to it },
                interaction.options?.let { "options" to it.joinToString("\n") },
            ).toTypedArray(),
        )
        return true
    }

    override suspend fun getForRun(runId: String): PendingInteraction? = byRun[runId]

    override suspend fun delete(runId: String) {
        val removed = byRun.remove(runId)
        log.record("pending.delete $runId kind=${removed?.kind}")
    }

    override suspend fun getForSession(sessionId: String): PendingInteraction? = unused("getForSession")

    override suspend fun getForRequest(requestId: String): PendingInteraction? = unused("getForRequest")

    override suspend fun recordDecision(runId: String, decision: PendingDecision): Boolean = unused("recordDecision")

    override suspend fun recordApprovalDecision(runId: String, requestId: String, decision: PendingDecision): Boolean =
        unused("recordApprovalDecision")

    override suspend fun recordAnswer(runId: String, answer: String): Boolean = unused("recordAnswer")

    override suspend fun getRequestedAtOrBefore(cutoffEpochMillis: Long): List<PendingInteraction> =
        unused("getRequestedAtOrBefore")

    override suspend fun getAllRunIds(): Set<String> = unused("getAllRunIds")

    private fun unused(method: String): Nothing =
        error("PendingInteractionRepository.$method is not on any golden run path; extend the harness deliberately")
}
