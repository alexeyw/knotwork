package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import app.knotwork.android.domain.verification.VerificationFixtures.HEADER
import app.knotwork.android.domain.verification.VerificationFixtures.MODEL_PATH
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VerifyRunUseCase]: each recorded call is sent again from its own prompt with
 * its own sampler and seed, on the model it ran on, and its answer compared by
 * SHA-256. A model that changes underneath stops the check rather than letting
 * it compare on the wrong file.
 */
class VerifyRunUseCaseTest {

    /** What the engine answers, by prompt; by default the recorded answer — a backend that repeats. */
    private val answers = mutableMapOf<String, String>()
    private var path: String? = MODEL_PATH
    private var backend: LocalBackend? = LocalBackend.CPU
    private val engine: LlmInferenceEngine = mockk {
        every { currentModelPath } answers { path }
        every { activeBackend } answers { backend }
        every { activeContextLength } returns 4096
        every { generateResponseStream(any(), any(), any()) } answers { flowOf(answers.getValue(firstArg())) }
    }
    private val load: LoadModelUseCase = mockk {
        coEvery { this@mockk.invoke(any(), any(), any()) } returns
            Result.Success(Unit)
    }
    private val models: LocalModelRepository = mockk { coEvery { findByPath(any()) } returns null }
    private val verify = VerifyRunUseCase(load, engine, models)

    private fun repeating(vararg calls: RunTraceRecord.LocalModelCall): List<RunTraceRecord.LocalModelCall> =
        calls.toList().onEach { answers[it.prompt] = it.output }

    private fun visit(nodeId: String, calls: List<RunTraceRecord.LocalModelCall>) = PlannedVisit(
        runId = ROOT,
        depth = 0,
        nodeId = nodeId,
        nodeType = "LITE_RT",
        label = nodeId,
        visit = 0,
        kind = VisitKind.Repeat,
        calls = calls,
    )

    private fun planOf(vararg visits: PlannedVisit) =
        VerificationPlan(ROOT, HEADER, visits.toList(), cloudCalls = 0, recordedModelMs = null)

    @Test
    fun `given a backend that repeats when verified then every call and visit matches`() = runTest {
        val plan = planOf(
            visit("a", repeating(localCall(0, "a", call = 0), localCall(1, "a", call = 1))),
            PlannedVisit(ROOT, 0, "t", "TOOL", "t", 0, VisitKind.NotVerifiable(NotVerifiableReason.TOOL_NOT_EXECUTED)),
            visit("b", repeating(localCall(2, "b"))),
        )

        val events = verify(plan).toList()

        assertEquals(listOf(1, 2, 3), events.filterIsInstance<VerificationEvent.CallChecked>().map { it.done })
        assertTrue(events.filterIsInstance<VerificationEvent.CallChecked>().all { it.matched })
        assertEquals(
            listOf(
                VerificationEvent.VisitSettled(0, NodeVerdict.Matched(2)),
                VerificationEvent.VisitSettled(2, NodeVerdict.Matched(1)),
            ),
            events.filterIsInstance<VerificationEvent.VisitSettled>(),
        )
        assertEquals(
            VerificationEvent.Finished(VerificationSummary.AllMatched(calls = 3, notVerifiableVisits = 1)),
            events.last(),
        )
    }

    @Test
    fun `given a call when repeated then it is sent with its own prompt, sampler and seed`() = runTest {
        val call = repeating(localCall(0, "a")).single()

        verify(planOf(visit("a", listOf(call)))).toList()

        verify { engine.generateResponseStream(call.prompt, null, call.sampling) }
        verify(exactly = 0) { engine.generateResponseStream(any(), any(), LocalSampling(HEADER.sampler, HEADER.seed)) }
    }

    @Test
    fun `given an answer that differs when verified then the visit diverges at that call and later visits still run`() =
        runTest {
            val calls = repeating(localCall(0, "a", call = 0), localCall(1, "a", call = 1))
            answers[calls[1].prompt] = "something else"
            val later = repeating(localCall(2, "b"))

            val events = verify(planOf(visit("a", calls), visit("b", later))).toList()

            val settled = events.filterIsInstance<VerificationEvent.VisitSettled>()
            val diverged = settled.first().verdict as NodeVerdict.Diverged
            assertEquals(2, diverged.call)
            assertEquals(2, diverged.of)
            assertEquals(calls[1].outputSha256, diverged.recordedSha256)
            assertEquals(NodeVerdict.Matched(1), settled[1].verdict)
            assertEquals(
                VerificationEvent.Finished(VerificationSummary.SomeDiverged(1, firstVisitIndex = 0, call = 2, of = 2)),
                events.last(),
            )
        }

    @Test
    fun `given the model cannot load when verified then the check fails and compares nothing`() = runTest {
        coEvery { load(any(), any(), any()) } returns Result.Error(object : AppError.System {}, "file not found")

        val events = verify(planOf(visit("a", repeating(localCall(0, "a"))))).toList()

        assertEquals(listOf(VerificationEvent.Failed("gemma.litertlm", "file not found", done = 0, total = 1)), events)
    }

    @Test
    fun `given the engine fell back to another backend when verified then the check stops before the call`() = runTest {
        backend = LocalBackend.GPU

        val events = verify(planOf(visit("a", repeating(localCall(0, "a"))))).toList()

        assertEquals(
            listOf(VerificationEvent.Stopped(VerifyMismatch.Backend(LocalBackend.CPU, LocalBackend.GPU), 0, 1)),
            events,
        )
        verify(exactly = 0) { engine.generateResponseStream(any(), any(), any()) }
    }

    @Test
    fun `given another run loads another model during a call when verified then the check stops`() = runTest {
        val call = repeating(localCall(0, "a")).single()
        every { engine.generateResponseStream(any(), any(), any()) } returns flow {
            path = "/m/other.litertlm"
            emit(call.output)
        }

        val events = verify(planOf(visit("a", listOf(call)))).toList()

        assertEquals(listOf(VerificationEvent.Stopped(VerifyMismatch.ModelChanged("gemma.litertlm"), 0, 1)), events)
    }

    @Test
    fun `given a generation that fails when verified then the check reports it`() = runTest {
        every { engine.generateResponseStream(any(), any(), any()) } returns flow { error("native failure") }

        val events = verify(planOf(visit("a", repeating(localCall(0, "a"))))).toList()

        assertEquals(listOf(VerificationEvent.Failed("gemma.litertlm", "native failure", 0, 1)), events)
    }

    @Test
    fun `given a plan with nothing to repeat when verified then nothing could be verified`() = runTest {
        val plan = planOf(
            PlannedVisit(ROOT, 0, "n", "LITE_RT", "n", 0, VisitKind.NotVerifiable(NotVerifiableReason.NPU)),
        )

        assertEquals(
            listOf(VerificationEvent.Finished(VerificationSummary.NothingVerifiable)),
            verify(plan).toList(),
        )
    }
}
