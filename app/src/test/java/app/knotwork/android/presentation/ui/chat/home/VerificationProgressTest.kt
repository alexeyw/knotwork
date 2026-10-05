package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.verification.NodeVerdict
import app.knotwork.android.domain.verification.NotVerifiableReason
import app.knotwork.android.domain.verification.PlannedVisit
import app.knotwork.android.domain.verification.VerificationEvent
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerificationSummary
import app.knotwork.android.domain.verification.VerifyMismatch
import app.knotwork.android.domain.verification.VisitKind
import app.knotwork.design.components.console.NotVerifiableUi
import app.knotwork.design.components.console.RunMismatchUi
import app.knotwork.design.components.console.VerdictUi
import app.knotwork.design.components.console.VerificationStageUi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VerificationProgress]: the check's sheet frame by frame — the first repeated
 * visit checking, calls advancing within a visit, a settled visit handing over to
 * the next, the time left shrinking, and each ending leaving the visits it never
 * reached as "not checked".
 */
class VerificationProgressTest {

    private fun call(ms: Long?) = mockk<RunTraceRecord.LocalModelCall> { every { durationMs } returns ms }

    private fun visit(label: String, kind: VisitKind, calls: Int = 0, ms: Long? = 10_000) = PlannedVisit(
        runId = "run",
        depth = 0,
        nodeId = label,
        nodeType = "LITE_RT",
        label = label,
        visit = 0,
        kind = kind,
        calls = List(calls) { call(ms) },
    )

    private val plan = VerificationPlan(
        rootRunId = "run",
        header = RunHeader(1, RunSampler(0.7, 40, 0.9), "app", "runtime", "device"),
        visits = listOf(
            visit("route", VisitKind.Repeat, calls = 1),
            visit("search", VisitKind.NotVerifiable(NotVerifiableReason.TOOL_NOT_EXECUTED)),
            visit("answer", VisitKind.Repeat, calls = 2),
        ),
        cloudCalls = 0,
        recordedModelMs = 30_000,
    )
    private val progress = VerificationProgress(plan, seed = "1", backend = LocalBackend.CPU.name, model = "Gemma")

    private fun checked(visit: Int, call: Int, of: Int, done: Int, matched: Boolean = true) =
        VerificationEvent.CallChecked(visit, call, of, matched, "rec", if (matched) "rec" else "rep", done, 3)

    @Test
    fun `given a check when it starts then the first repeated visit is checking and the rest wait`() {
        val ui = progress.start()

        assertEquals(VerificationStageUi.Running(done = 0, total = 3, left = "30 s"), ui.stage)
        assertEquals(
            listOf(VerdictUi.Checking(1, 1), VerdictUi.NotVerifiable(NotVerifiableUi.TOOL), VerdictUi.Waiting),
            ui.rows.map { it.verdict },
        )
    }

    @Test
    fun `given calls returning when folded then visits settle in place and the next one starts`() {
        var ui = progress.start()
        ui = progress.after(ui, checked(visit = 0, call = 1, of = 1, done = 1))
        ui = progress.after(ui, VerificationEvent.VisitSettled(0, NodeVerdict.Matched(1)))
        assertEquals(VerdictUi.Checking(1, 2), ui.rows[2].verdict)

        ui = progress.after(ui, checked(visit = 2, call = 1, of = 2, done = 2))
        assertEquals(VerdictUi.Checking(2, 2), ui.rows[2].verdict)
        assertEquals(VerificationStageUi.Running(done = 2, total = 3, left = "10 s"), ui.stage)

        ui = progress.after(ui, checked(visit = 2, call = 2, of = 2, done = 3, matched = false))
        ui = progress.after(ui, VerificationEvent.VisitSettled(2, NodeVerdict.Diverged(2, 2, "rec", "rep")))
        ui = progress.after(ui, VerificationEvent.Finished(VerificationSummary.SomeDiverged(1, 2, 2, 2)))

        assertEquals(
            listOf(
                VerdictUi.Matched(1),
                VerdictUi.NotVerifiable(NotVerifiableUi.TOOL),
                VerdictUi.Diverged(2, 2, "rec", "rep"),
            ),
            ui.rows.map { it.verdict },
        )
        assertEquals(VerificationStageUi.SomeDiverged(nodes = 1, firstNode = "answer", call = 2, of = 2), ui.stage)
    }

    @Test
    fun `given a cancel when folded then the visits not reached read not checked`() {
        var ui = progress.start()
        ui = progress.after(ui, checked(visit = 0, call = 1, of = 1, done = 1))
        ui = progress.after(ui, VerificationEvent.VisitSettled(0, NodeVerdict.Matched(1)))

        ui = progress.cancelled(ui)

        assertEquals(VerificationStageUi.Cancelled(done = 1, total = 3, allMatched = true), ui.stage)
        assertEquals(VerdictUi.Unchecked, ui.rows[2].verdict)
        assertEquals(ui, progress.cancelled(ui))
    }

    @Test
    fun `given a stop or a failed load when folded then the ending names it`() {
        val stopped = progress.after(
            progress.start(),
            VerificationEvent.Stopped(VerifyMismatch.Backend(LocalBackend.GPU, LocalBackend.CPU), done = 0, total = 3),
        )
        assertEquals(
            VerificationStageUi.Stopped(done = 0, total = 3, reason = RunMismatchUi.Backend("GPU", "CPU")),
            stopped.stage,
        )
        assertEquals(VerdictUi.Unchecked, stopped.rows[0].verdict)

        val failed = progress.after(
            progress.start(),
            VerificationEvent.Failed("Gemma", "no memory", done = 0, total = 3),
        )
        assertEquals(VerificationStageUi.Failed(model = "Gemma", reason = "no memory"), failed.stage)
    }

    @Test
    fun `given a call without a duration when started then there is no estimate`() {
        val unknown = VerificationProgress(
            plan.copy(visits = listOf(visit("route", VisitKind.Repeat, calls = 1, ms = null))),
            seed = "1",
            backend = "CPU",
            model = "Gemma",
        )

        assertEquals(VerificationStageUi.Running(done = 0, total = 1, left = null), unknown.start().stage)
    }
}
