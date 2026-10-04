package app.knotwork.android.data.repositories

import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.local.dao.UsageTelemetryCategories
import app.knotwork.android.data.local.dao.UsageTelemetryDao
import app.knotwork.android.data.local.models.OnboardingMilestoneEntity
import app.knotwork.android.data.local.models.UsageCounterEntity
import app.knotwork.android.data.local.models.UsagePipelineDayEntity
import app.knotwork.android.domain.models.OnboardingJourney
import app.knotwork.android.domain.models.OnboardingMilestone
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.PipelineRunTally
import app.knotwork.android.domain.models.UsagePipelineDay
import app.knotwork.android.domain.models.UsageRetention
import app.knotwork.android.domain.models.UsageTelemetrySummary
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.usecases.CalculateUsageRetentionUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [UsageTelemetryRepository].
 *
 * Aggregates the four on-device tables (`usage_counter`, `usage_active_day`,
 * `usage_pipeline_day`, `onboarding_milestone`) into a live
 * [UsageTelemetrySummary] — including the weekly-retention figures, whose
 * arithmetic is delegated to the pure [CalculateUsageRetentionUseCase] — and
 * records terminal run outcomes / trigger firings / onboarding markers behind
 * the opt-in flag.
 * **No method here ever touches the network** — the whole class reads and writes
 * only the local (SQLCipher-encrypted) database, which is the privacy guarantee
 * the feature is built around (enforced structurally by
 * `UsageTelemetryNoNetworkKonsistTest`).
 *
 * **Best-effort contract.** Recording absorbs storage failures (logged, no-op)
 * via the shared [absorbingStoreFailure] wrapper, so it can never take down the
 * run or trigger it observes; `CancellationException` is always re-thrown. Reads
 * degrade to [UsageTelemetrySummary.EMPTY] on any failure — a malformed row, a
 * DAO/SQLCipher error — rather than surfacing the exception to the screen.
 *
 * @property dao The telemetry DAO.
 * @property privacySettings Source of the [PrivacySettings.usageTelemetryEnabled]
 *   opt-in flag that gates every write.
 * @property calculateRetention Pure domain calculator folding the activity set
 *   into the weekly-retention aggregate.
 * @property clockProvider Supplies the [Clock] (carrying the device zone) used to
 *   derive the device-local active day from an event's epoch-millis. A supplier
 *   (not a snapshot) so a runtime time-zone change is picked up live, mirroring
 *   [app.knotwork.android.data.prompt.DateVariableProvider].
 */
@Singleton
class UsageTelemetryRepositoryImpl internal constructor(
    private val dao: UsageTelemetryDao,
    private val privacySettings: PrivacySettings,
    private val calculateRetention: CalculateUsageRetentionUseCase,
    private val clockProvider: () -> Clock,
) : UsageTelemetryRepository {

    /**
     * Hilt-visible constructor wiring the production clock supplier (a fresh
     * system-default-zone [Clock] per call). Secondary because Hilt rejects a
     * default-valued parameter on an `@Inject` primary constructor.
     */
    @Inject
    constructor(
        dao: UsageTelemetryDao,
        privacySettings: PrivacySettings,
        calculateRetention: CalculateUsageRetentionUseCase,
    ) : this(
        dao = dao,
        privacySettings = privacySettings,
        calculateRetention = calculateRetention,
        clockProvider = { Clock.systemDefaultZone() },
    )

    /** Dispatcher carrying every DAO call. Swapped in unit tests. */
    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override val summary: Flow<UsageTelemetrySummary>
        // `today` is resolved once per collection and then drives both the SQL
        // window bound and the retention arithmetic, so the two can never
        // disagree about where the week starts. A collector that outlives
        // midnight keeps its anchor until it re-subscribes — which the screen
        // does on every return to it.
        get() = flow {
            val today = LocalDate.now(clockProvider())
            val windowStart = today.minusDays((UsageRetention.WINDOW_DAYS - 1).toLong()).toString()
            emitAll(
                combine(
                    dao.observeCounters(),
                    dao.observeActiveDays(),
                    dao.observeMilestones(),
                    dao.observePipelineDaysSince(windowStart),
                ) { counters, activeDays, milestones, pipelineDays ->
                    aggregate(counters, activeDays, journey(milestones), pipelineDays, today)
                },
            )
        }
            // Degrade to EMPTY on a DAO/SQLCipher read error rather than letting
            // the exception cancel the screen's collector (best-effort read).
            .catch { e ->
                Timber.tag(TAG).w(e, "Usage-telemetry read failed; emitting empty summary.")
                emit(UsageTelemetrySummary.EMPTY)
            }
            .flowOn(dispatcher)

    override suspend fun isEnabled(): Boolean =
        absorbingStoreFailure({ "Failed to read the usage-telemetry opt-in flag; treating as disabled" }) {
            privacySettings.usageTelemetryEnabled.first()
        } ?: false

    override suspend fun recordPipelineRunOutcome(pipelineId: String?, status: PipelineRunStatus, atMillis: Long) {
        if (!status.isTerminal) return
        if (!isEnabled()) return
        val pipelineKey = pipelineId ?: UsageTelemetryCategories.NULL_PIPELINE_KEY
        absorbingStoreFailure({ "Usage-telemetry recordPipelineRunOutcome failed; ignored" }) {
            withContext(dispatcher) { dao.recordRun(pipelineKey, status.name, localDay(atMillis)) }
        }
    }

    override suspend fun recordTriggerFired(kind: String, atMillis: Long) {
        if (!isEnabled()) return
        absorbingStoreFailure({ "Usage-telemetry recordTriggerFired failed; ignored" }) {
            withContext(dispatcher) { dao.recordTriggerFire(kind, localDay(atMillis)) }
        }
    }

    override suspend fun recordOnboardingMilestone(milestone: OnboardingMilestone, atMillis: Long, detail: String?) {
        if (!isEnabled()) return
        absorbingStoreFailure({ "Usage-telemetry recordOnboardingMilestone failed; ignored" }) {
            // INSERT OR IGNORE: the first occurrence of a marker wins, so a
            // second pass through onboarding never moves a measured journey.
            withContext(dispatcher) { dao.recordMilestone(milestone.name, atMillis, detail) }
        }
    }

    override suspend fun recordOnboardingFirstValue(pipelineId: String, atMillis: Long) {
        if (!isEnabled()) return
        absorbingStoreFailure({ "Usage-telemetry recordOnboardingFirstValue failed; ignored" }) {
            withContext(dispatcher) {
                // Read-then-write rather than a blind INSERT OR IGNORE: the
                // decision needs the scenario's pipeline id, which lives in
                // another marker's payload. A lost race between two runs
                // completing at once is harmless — the INSERT OR IGNORE below
                // keeps whichever landed first, which is the earlier first value.
                if (!journey(dao.getMilestones()).acceptsFirstValueFrom(pipelineId)) return@withContext
                dao.recordMilestone(OnboardingMilestone.FIRST_VALUE.name, atMillis, detail = null)
            }
        }
    }

    override suspend fun reset() {
        absorbingStoreFailure({ "Usage-telemetry reset failed; ignored" }) {
            withContext(dispatcher) { dao.clearAll() }
        }
    }

    /**
     * Folds the raw marker rows into the domain journey, dropping any row whose
     * key no longer maps to a known [OnboardingMilestone] (a marker retired by a
     * future release must not crash the screen of an install that recorded it).
     */
    private fun journey(milestones: List<OnboardingMilestoneEntity>): OnboardingJourney {
        val recorded = milestones.mapNotNull { row ->
            milestoneOrNull(row.milestoneKey)?.let { it to row.atMillis }
        }.toMap()
        val scenarioPipelineId = milestones
            .firstOrNull { it.milestoneKey == OnboardingMilestone.SCENARIO_CHOSEN.name }
            ?.detail
            ?.takeIf { it.isNotBlank() }
        return OnboardingJourney(milestones = recorded, scenarioPipelineId = scenarioPipelineId)
    }

    /** Resolves a stored marker key to its [OnboardingMilestone], or `null` if unrecognised. */
    private fun milestoneOrNull(key: String): OnboardingMilestone? =
        OnboardingMilestone.entries.firstOrNull { it.name == key }

    /** Folds the raw counter rows + activity set + markers into the domain summary. */
    private fun aggregate(
        counters: List<UsageCounterEntity>,
        activeDays: List<String>,
        onboarding: OnboardingJourney,
        pipelineDays: List<UsagePipelineDayEntity>,
        today: LocalDate,
    ): UsageTelemetrySummary {
        // A day that no longer parses (a row written by some future format) is
        // dropped rather than allowed to take the whole screen down with it.
        val parsedDays = activeDays.mapNotNull(::parseDayOrNull)
        val runsByPipeline = counters
            .filter { it.category == UsageTelemetryCategories.PIPELINE_RUN }
            .sortedByDescending { it.count }
            .map { row ->
                PipelineRunTally(
                    pipelineId = row.counterKey.ifEmpty { null },
                    runCount = row.count,
                )
            }
        val runsByOutcome = counters
            .filter { it.category == UsageTelemetryCategories.RUN_OUTCOME }
            .mapNotNull { row -> terminalStatusOrNull(row.counterKey)?.let { it to row.count } }
            .toMap()
        val triggerFiresByKind = counters
            .filter { it.category == UsageTelemetryCategories.TRIGGER_FIRE }
            .associate { it.counterKey to it.count }
        return UsageTelemetrySummary(
            runsByPipeline = runsByPipeline,
            runsByOutcome = runsByOutcome,
            triggerFiresByKind = triggerFiresByKind,
            activeDays = parsedDays.size,
            firstActiveDay = parsedDays.minOrNull()?.toString(),
            lastActiveDay = parsedDays.maxOrNull()?.toString(),
            onboarding = onboarding,
            retention = calculateRetention(
                activeDays = parsedDays,
                pipelineDays = pipelineDays.mapNotNull { row ->
                    parseDayOrNull(row.day)?.let { UsagePipelineDay(day = it, pipelineId = row.pipelineId) }
                },
                today = today,
            ),
        )
    }

    /** Parses a stored ISO `yyyy-MM-dd` day, or `null` when the row is unreadable. */
    private fun parseDayOrNull(day: String): LocalDate? = try {
        LocalDate.parse(day)
    } catch (e: DateTimeParseException) {
        Timber.tag(TAG).w(e, "Dropping an unparseable usage-telemetry day: %s", day)
        null
    }

    /** Resolves a stored status name to a terminal [PipelineRunStatus], or `null` if unrecognised. */
    private fun terminalStatusOrNull(name: String): PipelineRunStatus? =
        PipelineRunStatus.entries.firstOrNull { it.name == name && it.isTerminal }

    /** Device-local calendar day of [atMillis] as an ISO `yyyy-MM-dd` string. */
    private fun localDay(atMillis: Long): String {
        val zone = clockProvider().zone
        return Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    private companion object {
        const val TAG = "UsageTelemetry"
    }
}
