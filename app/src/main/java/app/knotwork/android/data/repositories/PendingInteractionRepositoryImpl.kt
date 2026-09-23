package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.PendingInteractionDao
import app.knotwork.android.data.local.models.PendingInteractionEntity
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [PendingInteractionRepository].
 *
 * Maps between the domain `PendingInteraction` and the persistence
 * [PendingInteractionEntity] (enums stored as `name` strings, answer choices
 * as a JSON array string) and routes all DAO calls through [Dispatchers.IO].
 * The first-writer-wins guard of the response-recording operations is
 * implemented in SQL (`decision IS NULL` / `answer IS NULL`), so it holds
 * across racing coroutines and processes.
 *
 * Follows the best-effort contract of the run store for reads and deletes
 * (failures logged and absorbed); [save] surfaces failure as `false` per the
 * interface contract — the caller must fail the run rather than park it on a
 * record that was never written.
 */
@Singleton
class PendingInteractionRepositoryImpl @Inject constructor(private val pendingInteractionDao: PendingInteractionDao) :
    PendingInteractionRepository {

    override suspend fun save(interaction: PendingInteraction): Boolean = absorbing("save") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.upsert(interaction.toEntity())
        }
        true
    } ?: false

    override suspend fun getForRun(runId: String): PendingInteraction? = absorbing("getForRun") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.getForRun(runId)?.toDomain()
        }
    }

    override suspend fun getForSession(sessionId: String): PendingInteraction? = absorbing("getForSession") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.getForSession(sessionId)?.toDomain()
        }
    }

    override suspend fun getForRequest(requestId: String): PendingInteraction? = absorbing("getForRequest") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.getForRequest(requestId)?.toDomain()
        }
    }

    override suspend fun recordDecision(runId: String, decision: PendingDecision): Boolean =
        absorbing("recordDecision") {
            withContext(Dispatchers.IO) {
                pendingInteractionDao.recordDecision(runId, decision.name) == 1
            }
        } ?: false

    override suspend fun recordApprovalDecision(runId: String, requestId: String, decision: PendingDecision): Boolean =
        absorbing("recordApprovalDecision") {
            withContext(Dispatchers.IO) {
                pendingInteractionDao.recordApprovalDecision(runId, requestId, decision.name) == 1
            }
        } ?: false

    override suspend fun recordAnswer(runId: String, answer: String): Boolean = absorbing("recordAnswer") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.recordAnswer(runId, answer) == 1
        }
    } ?: false

    override suspend fun delete(runId: String) {
        absorbing("delete") {
            withContext(Dispatchers.IO) {
                pendingInteractionDao.delete(runId)
            }
        }
    }

    override suspend fun getRequestedAtOrBefore(cutoffEpochMillis: Long): List<PendingInteraction> =
        absorbing("getRequestedAtOrBefore") {
            withContext(Dispatchers.IO) {
                pendingInteractionDao.getRequestedAtOrBefore(cutoffEpochMillis).map { it.toDomain() }
            }
        } ?: emptyList()

    override suspend fun getAllRunIds(): Set<String> = absorbing("getAllRunIds") {
        withContext(Dispatchers.IO) {
            pendingInteractionDao.getAllRunIds().toSet()
        }
    } ?: emptySet()

    /**
     * Runs [block] under the best-effort contract via the shared
     * [absorbingStoreFailure] helper, branding the log line with the
     * pending-interaction store prefix.
     *
     * @param operation Name used in the failure log line.
     * @param block The storage operation to attempt.
     * @return The block's result, or `null` when the store failed.
     */
    private suspend fun <T> absorbing(operation: String, block: suspend () -> T): T? =
        absorbingStoreFailure({ "Pending-interaction store failure in $operation; continuing without it" }, block)
}

/**
 * Maps the domain record to its persistence entity, storing enums as `name`
 * strings and answer choices as a JSON array string.
 *
 * @return The entity ready for insertion.
 */
private fun PendingInteraction.toEntity(): PendingInteractionEntity = PendingInteractionEntity(
    runId = runId,
    sessionId = sessionId,
    kind = kind.name,
    toolName = toolName,
    toolArgs = toolArgs,
    risk = risk?.name,
    question = question,
    optionsJson = options?.let { list -> JSONArray(list).toString() },
    decision = decision?.name,
    answer = answer,
    ceilingAxis = ceilingAxis?.name,
    ceilingLimit = ceilingLimit,
    ceilingSpent = ceilingSpent,
    requestedAt = requestedAt,
    requestId = requestId,
)

/**
 * Maps the persistence entity back to the domain record. Enum and JSON
 * columns are parsed strictly — an unreadable row is data corruption; the
 * repository's best-effort wrapper turns it into a logged degraded read
 * instead of a crash.
 *
 * @return The domain model.
 */
private fun PendingInteractionEntity.toDomain(): PendingInteraction = PendingInteraction(
    runId = runId,
    sessionId = sessionId,
    kind = PendingInteractionKind.valueOf(kind),
    toolName = toolName,
    toolArgs = toolArgs,
    risk = risk?.let { ToolRisk.valueOf(it) },
    question = question,
    options = optionsJson?.let { json ->
        val array = JSONArray(json)
        List(array.length()) { index -> array.getString(index) }
    },
    decision = decision?.let { PendingDecision.valueOf(it) },
    answer = answer,
    ceilingAxis = ceilingAxis?.let { RunCeilingAxis.valueOf(it) },
    ceilingLimit = ceilingLimit,
    ceilingSpent = ceilingSpent,
    requestedAt = requestedAt,
    requestId = requestId,
)
