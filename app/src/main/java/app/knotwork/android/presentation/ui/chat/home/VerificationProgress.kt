package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.verification.NodeVerdict
import app.knotwork.android.domain.verification.VerificationEvent
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerificationSummary
import app.knotwork.android.domain.verification.VisitKind
import app.knotwork.design.components.console.VerdictRowUi
import app.knotwork.design.components.console.VerdictUi
import app.knotwork.design.components.console.VerificationStageUi
import app.knotwork.design.components.console.VerificationUi

/**
 * Folds the check's events into what its sheet shows: rows fill in place as calls
 * are repeated, nothing reorders, and an interrupted check leaves the visits it
 * never reached as "not checked".
 *
 * Pure: every step returns a new [VerificationUi], so a test can replay a sequence
 * of events and read each frame.
 *
 * @param plan What the check repeats.
 * @param seed The run's seed, grouped for reading.
 * @param backend The backend the check runs on.
 * @param model The model it runs.
 */
internal class VerificationProgress(
    private val plan: VerificationPlan,
    private val seed: String,
    private val backend: String,
    private val model: String,
) {

    /** Each repeated call's recorded duration in check order, or `null` when one has none. */
    private val durations: List<Long>? = plan.visits.filter { it.kind == VisitKind.Repeat }
        .flatMap { visit -> visit.calls.map { it.durationMs } }
        .takeIf { all -> all.none { it == null } }
        ?.map { it ?: 0L }

    /**
     * The sheet before the first call returns: the first repeated visit is being
     * checked, the rest wait.
     *
     * @return The first frame.
     */
    fun start(): VerificationUi {
        val rows = initialVerdictRows(plan)
        return VerificationUi(
            seed = seed,
            backend = backend,
            model = model,
            stage = VerificationStageUi.Running(done = 0, total = plan.calls, left = left(0)),
            rows = checkingNext(rows),
        )
    }

    /**
     * The frame after [event].
     *
     * @param ui The current frame.
     * @param event What the check reported.
     * @return The next frame.
     */
    fun after(ui: VerificationUi, event: VerificationEvent): VerificationUi = when (event) {
        is VerificationEvent.CallChecked -> {
            val rows = ui.rows.toMutableList()
            if (event.call < event.of) {
                rows[event.visitIndex] = rows[event.visitIndex].copy(
                    verdict = VerdictUi.Checking(call = event.call + 1, of = event.of),
                )
            }
            ui.copy(
                stage = VerificationStageUi.Running(done = event.done, total = event.total, left = left(event.done)),
                rows = rows,
            )
        }
        is VerificationEvent.VisitSettled -> {
            val rows = ui.rows.toMutableList()
            rows[event.visitIndex] = rows[event.visitIndex].copy(verdict = event.verdict.toUi())
            ui.copy(rows = checkingNext(rows))
        }
        is VerificationEvent.Finished -> ui.copy(stage = event.summary.toStage())
        is VerificationEvent.Stopped -> ui.copy(
            stage = VerificationStageUi.Stopped(done = event.done, total = event.total, reason = event.mismatch.toUi()),
            rows = unchecked(ui.rows),
        )
        is VerificationEvent.Failed -> ui.copy(
            stage = VerificationStageUi.Failed(model = event.modelName, reason = event.reason),
            rows = unchecked(ui.rows),
        )
    }

    /**
     * The frame after the user stops the check.
     *
     * @param ui The current frame.
     * @return The cancelled frame; a check that already ended is left as it is.
     */
    fun cancelled(ui: VerificationUi): VerificationUi {
        val running = ui.stage as? VerificationStageUi.Running ?: return ui
        return ui.copy(
            stage = VerificationStageUi.Cancelled(
                done = running.done,
                total = running.total,
                allMatched = ui.rows.none { it.verdict is VerdictUi.Diverged },
            ),
            rows = unchecked(ui.rows),
        )
    }

    /** About how long the calls after the first [done] take, or `null` when unknown. */
    private fun left(done: Int): String? = durations?.drop(done)?.sum()?.takeIf { it > 0 }?.let(RunDisplay::duration)

    /** Marks the first waiting visit as being checked, at its first call. */
    private fun checkingNext(rows: List<VerdictRowUi>) = rows.toMutableList()
        .also { list ->
            val next = list.indexOfFirst { it.verdict == VerdictUi.Waiting }
            if (next >= 0) {
                list[next] = list[next].copy(verdict = VerdictUi.Checking(call = 1, of = plan.visits[next].calls.size))
            }
        }

    /** Turns every visit not reached into "not checked". */
    private fun unchecked(rows: List<VerdictRowUi>) = rows.map { row ->
        if (row.verdict == VerdictUi.Waiting || row.verdict is VerdictUi.Checking) {
            row.copy(verdict = VerdictUi.Unchecked)
        } else {
            row
        }
    }

    /** A visit's verdict, in the catalog's terms. */
    private fun NodeVerdict.toUi(): VerdictUi = when (this) {
        is NodeVerdict.Matched -> VerdictUi.Matched(calls)
        is NodeVerdict.Diverged -> VerdictUi.Diverged(call, of, recordedSha256, replayedSha256)
    }

    /** The summary's stage. */
    private fun VerificationSummary.toStage(): VerificationStageUi = when (this) {
        is VerificationSummary.AllMatched -> VerificationStageUi.AllMatched(calls, notVerifiableVisits)
        is VerificationSummary.SomeDiverged -> VerificationStageUi.SomeDiverged(
            nodes = divergedVisits,
            firstNode = plan.visits[firstVisitIndex].label,
            call = call,
            of = of,
        )
        VerificationSummary.NothingVerifiable -> VerificationStageUi.NothingVerifiable
    }
}
