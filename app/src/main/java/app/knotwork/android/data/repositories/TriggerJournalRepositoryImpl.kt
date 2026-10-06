package app.knotwork.android.data.repositories

import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.local.dao.TriggerJournalDao
import app.knotwork.android.data.local.models.TriggerEvaluationEntity
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.TriggerEvaluation
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.models.TriggerHitlActivity
import app.knotwork.android.domain.models.TriggerHitlEvent
import app.knotwork.android.domain.models.TriggerHitlResolution
import app.knotwork.android.domain.models.TriggerRunOutcome
import app.knotwork.android.domain.models.TriggerSkipReason
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [TriggerJournalRepository].
 *
 * Flattens the sealed [TriggerEvaluationVerdict] / [TriggerRunOutcome] into the
 * string discriminators of [TriggerEvaluationEntity] on write and reconstitutes
 * them on read. A row whose stored discriminators cannot be decoded (an unknown
 * future source or verdict, or a corrupt value) is **skipped on read** rather
 * than crashing the journal load — mirroring [TriggerRepositoryImpl] — so one bad
 * row never hides the rest of a trigger's history.
 *
 * **Best-effort observer.** Every write goes through [absorbingStoreFailure] and
 * reads degrade to an empty list on any DAO/SQLCipher error, so a journal storage
 * failure can never propagate into (and abort) the trigger evaluation or run it
 * merely observes. `CancellationException` is always re-thrown by the wrapper.
 *
 * @property dao The journal DAO.
 */
@Singleton
class TriggerJournalRepositoryImpl @Inject constructor(private val dao: TriggerJournalDao) : TriggerJournalRepository {

    /** Dispatcher carrying every DAO call. Swapped in unit tests. */
    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override suspend fun recordEvaluation(evaluation: TriggerEvaluation) {
        absorbingStoreFailure({ "Trigger-journal recordEvaluation failed; ignored" }) {
            withContext(dispatcher) { dao.insert(evaluation.toEntity()) }
        }
    }

    override suspend fun recordRunOutcome(runId: String, outcome: TriggerRunOutcome) {
        val (kind, error) = outcome.encode()
        absorbingStoreFailure({ "Trigger-journal recordRunOutcome failed; ignored" }) {
            withContext(dispatcher) { dao.updateOutcome(runId, kind, error) }
        }
    }

    override suspend fun recordHitlEvent(runId: String, event: TriggerHitlEvent) {
        absorbingStoreFailure({ "Trigger-journal recordHitlEvent failed; ignored" }) {
            withContext(dispatcher) {
                when (event) {
                    is TriggerHitlEvent.Raised ->
                        dao.recordHitlGateRaised(runId, event.kind.name, TriggerHitlResolution.PENDING.name)
                    TriggerHitlEvent.Parked -> dao.recordHitlGateParked(runId)
                    is TriggerHitlEvent.Resolved -> dao.recordHitlGateResolved(runId, event.resolution.name)
                }
            }
        }
    }

    override suspend fun findTriggerIdForRun(runId: String): String? = absorbingStoreFailure(
        { "Trigger-journal findTriggerIdForRun failed; returning null" },
    ) {
        withContext(dispatcher) { dao.findTriggerIdByRunId(runId) }
    }

    override fun observeByTrigger(triggerId: String): Flow<List<TriggerEvaluation>> = dao.observeByTrigger(triggerId)
        .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
        // Degrade to an empty journal on a DAO/SQLCipher read error rather
        // than letting the exception cancel the screen's collector.
        .catch { e ->
            Timber.tag(TAG).w(e, "Trigger-journal read failed for %s; emitting empty.", triggerId)
            emit(emptyList())
        }
        .flowOn(dispatcher)

    override fun observeHealthInputs(): Flow<Map<String, TriggerHealthInputs>> = combine(
        dao.observeLatestPerTrigger(),
        dao.observeLatestFiredPerTrigger(),
    ) { latest, fired ->
        // The latest fired outcome per trigger — a `null` (never fired, still
        // pending, or an undecodable outcome) is a legitimate "no error yet".
        val firedOutcomeByTrigger: Map<String, TriggerRunOutcome?> =
            fired.associate { it.triggerId to decodeOutcome(it.outcomeKind, it.outcomeError) }
        // Staleness reads the raw row (triggerId + evaluatedAt), never the decoded
        // verdict, so an unknown-future verdict still counts as "was evaluated".
        latest.associate { row ->
            row.triggerId to TriggerHealthInputs(
                latestEvaluatedAt = row.evaluatedAt,
                latestFiredOutcome = firedOutcomeByTrigger[row.triggerId],
            )
        }
    }
        // Degrade to no health data on a DAO/SQLCipher read error rather than
        // letting the exception cancel the list's collector.
        .catch { e ->
            Timber.tag(TAG).w(e, "Trigger-journal health-inputs read failed; emitting empty.")
            emit(emptyMap())
        }
        .flowOn(dispatcher)

    override suspend fun readAll(): List<TriggerEvaluation> = absorbingStoreFailure(
        { "Trigger-journal readAll failed; returning empty snapshot" },
    ) {
        withContext(dispatcher) { dao.getAllOrderedByEvaluatedAt().mapNotNull { it.toDomainOrNull() } }
    } ?: emptyList()

    override suspend fun applyRetention(olderThanEpochMs: Long, maxRecords: Int): Int {
        // Fail fast on a misconfigured cap: `maxRecords <= 0` would make the
        // enforce-cap DELETE keep zero rows and wipe the whole journal. This is a
        // programming error, not a storage failure, so it is asserted rather than
        // absorbed.
        require(maxRecords > 0) { "maxRecords must be positive, was $maxRecords" }
        return absorbingStoreFailure({ "Trigger-journal retention pass failed; ignored" }) {
            withContext(dispatcher) { dao.applyRetention(olderThanEpochMs, maxRecords) }
        } ?: 0
    }

    /** Maps a domain [TriggerEvaluation] to its persisted row. */
    private fun TriggerEvaluation.toEntity(): TriggerEvaluationEntity {
        val (verdictKind, skipReason) = verdict.encode()
        val (outcomeKind, outcomeError) = outcome?.encode() ?: (null to null)
        return TriggerEvaluationEntity(
            id = id,
            triggerId = triggerId,
            evaluatedAt = evaluatedAt,
            source = source.name,
            verdictKind = verdictKind,
            skipReason = skipReason,
            runId = runId,
            outcomeKind = outcomeKind,
            outcomeError = outcomeError,
            hitlGateCount = hitl?.gateCount ?: 0,
            hitlLastKind = hitl?.lastKind?.name,
            hitlLastResolution = hitl?.lastResolution?.name,
            hitlParked = hitl?.parked ?: false,
        )
    }

    /**
     * Maps a row to its domain model, or `null` when a core discriminator (the
     * [source] or the [verdict]) is undecodable — the caller drops such rows.
     */
    private fun TriggerEvaluationEntity.toDomainOrNull(): TriggerEvaluation? {
        val decodedSource = enumOrNull<TriggerEvaluationSource>(source)
        if (decodedSource == null) {
            Timber.tag(TAG).w("Skipping journal row %s: unknown source '%s'.", id, source)
            return null
        }
        val decodedVerdict = decodeVerdict(verdictKind, skipReason)
        if (decodedVerdict == null) {
            Timber.tag(TAG).w("Skipping journal row %s: undecodable verdict '%s'.", id, verdictKind)
            return null
        }
        return TriggerEvaluation(
            id = id,
            triggerId = triggerId,
            evaluatedAt = evaluatedAt,
            source = decodedSource,
            verdict = decodedVerdict,
            runId = runId,
            outcome = decodeOutcome(outcomeKind, outcomeError),
            hitl = decodeHitl(),
        )
    }

    /**
     * Reconstitutes the row's HITL activity, or `null` when the run raised no
     * gate ([TriggerEvaluationEntity.hitlGateCount] is `0` — the default of every
     * pre-v56 row) or when a discriminator is undecodable.
     *
     * An undecodable HITL pair drops **only** the activity, never the row: unlike
     * the verdict, the gate history is an annotation on the evaluation, and losing
     * the fire itself over it would trade a small blind spot for a bigger one.
     */
    private fun TriggerEvaluationEntity.decodeHitl(): TriggerHitlActivity? {
        if (hitlGateCount <= 0) return null
        val kind = enumOrNull<PendingInteractionKind>(hitlLastKind)
        val resolution = enumOrNull<TriggerHitlResolution>(hitlLastResolution)
        if (kind == null || resolution == null) {
            Timber.tag(TAG).w("Dropping HITL activity of journal row %s: undecodable kind/resolution.", id)
            return null
        }
        return TriggerHitlActivity(
            gateCount = hitlGateCount,
            lastKind = kind,
            lastResolution = resolution,
            parked = hitlParked,
        )
    }

    /** Encodes a verdict into its (kind, skipReason) column pair. */
    private fun TriggerEvaluationVerdict.encode(): Pair<String, String?> = when (this) {
        TriggerEvaluationVerdict.Fired -> VERDICT_FIRED to null
        TriggerEvaluationVerdict.ReArmed -> VERDICT_RE_ARMED to null
        is TriggerEvaluationVerdict.Skipped -> VERDICT_SKIPPED to reason.name
    }

    /** Reconstitutes a verdict from its columns, or `null` when undecodable. */
    private fun decodeVerdict(kind: String, skipReason: String?): TriggerEvaluationVerdict? = when (kind) {
        VERDICT_FIRED -> TriggerEvaluationVerdict.Fired
        VERDICT_RE_ARMED -> TriggerEvaluationVerdict.ReArmed
        VERDICT_SKIPPED -> enumOrNull<TriggerSkipReason>(skipReason)?.let { TriggerEvaluationVerdict.Skipped(it) }
        else -> null
    }

    /** Encodes a settled run outcome into its (kind, error) column pair. */
    private fun TriggerRunOutcome.encode(): Pair<String, String?> = when (this) {
        TriggerRunOutcome.Success -> OUTCOME_SUCCESS to null
        is TriggerRunOutcome.Failure -> OUTCOME_FAILURE to error
        TriggerRunOutcome.CancelledBySystem -> OUTCOME_CANCELLED_BY_SYSTEM to null
        TriggerRunOutcome.Cancelled -> OUTCOME_CANCELLED to null
        TriggerRunOutcome.HitlTimeout -> OUTCOME_HITL_TIMEOUT to null
        TriggerRunOutcome.StoppedByCeiling -> OUTCOME_STOPPED_BY_CEILING to null
    }

    /**
     * Reconstitutes a run outcome from its columns. A `null` [kind] (or an
     * unrecognised one) reads back as `null` — "no settled outcome yet". A
     * `FAILURE` kind whose [error] column is `null` is a corrupt half-written row
     * (writes always pair `FAILURE` with a message, even an empty one), so it too
     * reads back as `null` rather than being reported as a blank-reason failure —
     * this keeps a genuinely empty message (`error == ""`) distinguishable from
     * corruption.
     */
    private fun decodeOutcome(kind: String?, error: String?): TriggerRunOutcome? = when (kind) {
        OUTCOME_SUCCESS -> TriggerRunOutcome.Success
        OUTCOME_FAILURE -> error?.let { TriggerRunOutcome.Failure(it) }
        OUTCOME_CANCELLED_BY_SYSTEM -> TriggerRunOutcome.CancelledBySystem
        OUTCOME_CANCELLED -> TriggerRunOutcome.Cancelled
        OUTCOME_HITL_TIMEOUT -> TriggerRunOutcome.HitlTimeout
        OUTCOME_STOPPED_BY_CEILING -> TriggerRunOutcome.StoppedByCeiling
        else -> null
    }

    private companion object {
        const val TAG = "TriggerJournal"

        const val VERDICT_FIRED = "FIRED"
        const val VERDICT_RE_ARMED = "RE_ARMED"
        const val VERDICT_SKIPPED = "SKIPPED"

        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_FAILURE = "FAILURE"
        const val OUTCOME_CANCELLED_BY_SYSTEM = "CANCELLED_BY_SYSTEM"
        const val OUTCOME_CANCELLED = "CANCELLED"
        const val OUTCOME_HITL_TIMEOUT = "HITL_TIMEOUT"
        const val OUTCOME_STOPPED_BY_CEILING = "STOPPED_BY_CEILING"
    }
}

/**
 * Resolves [name] to an entry of enum [T] by its stable [Enum.name], or `null`
 * when [name] is `null` or matches no entry — the tolerant decode used for
 * persisted discriminators.
 */
private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
