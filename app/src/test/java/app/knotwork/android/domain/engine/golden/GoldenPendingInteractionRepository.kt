package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.repositories.PendingInteractionRepository

/**
 * In-memory store of parked interactions for the golden harness; records what is parked,
 * answered and deleted.
 *
 * Its writes keep the guards of the production DAO, because the answering use cases rely on
 * them: a decision is recorded only while none is (`decision IS NULL`), an approval decision
 * only for the request it names (`requestId = :requestId`), an answer only while none is, and
 * the session lookup returns the most recent request. The request id is random, so the trace
 * shows it as a stable alias (`#1`); the time a request was raised is wall-clock and is left
 * out.
 *
 * @param log The run's event log.
 */
internal class GoldenPendingInteractionRepository(private val log: GoldenEventLog) : PendingInteractionRepository {

    private val byRun = linkedMapOf<String, PendingInteraction>()

    override suspend fun save(interaction: PendingInteraction): Boolean {
        byRun.remove(interaction.runId)
        byRun[interaction.runId] = interaction
        log.record(
            buildString {
                append(
                    "pending.save ${interaction.runId} ${interaction.kind} request=${log.alias(interaction.requestId)}",
                )
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

    override suspend fun getForSession(sessionId: String): PendingInteraction? =
        byRun.values.lastOrNull { it.sessionId == sessionId }

    override suspend fun getForRequest(requestId: String): PendingInteraction? =
        byRun.values.firstOrNull { it.requestId == requestId }

    override suspend fun recordDecision(runId: String, decision: PendingDecision): Boolean =
        update(runId, "decision $decision") { it.takeIf { it.decision == null }?.copy(decision = decision) }

    override suspend fun recordApprovalDecision(runId: String, requestId: String, decision: PendingDecision): Boolean =
        update(runId, "approval $decision request=${log.alias(requestId)}") { pending ->
            pending.takeIf { it.requestId == requestId && it.decision == null }?.copy(decision = decision)
        }

    override suspend fun recordAnswer(runId: String, answer: String): Boolean =
        update(runId, "answer") { it.takeIf { it.answer == null }?.copy(answer = answer) }

    override suspend fun delete(runId: String) {
        val removed = byRun.remove(runId)
        log.record("pending.delete $runId kind=${removed?.kind}")
    }

    override suspend fun getRequestedAtOrBefore(cutoffEpochMillis: Long): List<PendingInteraction> =
        unused("getRequestedAtOrBefore")

    override suspend fun getAllRunIds(): Set<String> = unused("getAllRunIds")

    private fun update(runId: String, what: String, change: (PendingInteraction) -> PendingInteraction?): Boolean {
        val updated = byRun[runId]?.let(change)
        if (updated != null) byRun[runId] = updated
        log.record("pending.record $runId $what applied=${updated != null}")
        return updated != null
    }

    private fun unused(method: String): Nothing = log.violation(
        "PendingInteractionRepository.$method is not on any golden run path; extend the harness deliberately",
    )
}
