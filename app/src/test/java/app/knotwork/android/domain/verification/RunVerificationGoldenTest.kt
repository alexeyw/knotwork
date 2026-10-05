package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.golden.GoldenModel
import app.knotwork.android.domain.engine.golden.GoldenScenarios
import app.knotwork.android.domain.engine.golden.GoldenTraceHarness
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.ModelFileStatus
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run check end to end, on a run the real engine made: the golden harness runs
 * `showcase_full_agent` / `task-every-subpipeline` — a router, a decomposition, four
 * sub-pipelines, two of them running a tool — and the check then plans and repeats
 * it from the recorded trace alone.
 *
 * The harness's tool catalogue is switched to refuse every call before the check
 * starts, so a check that ran a tool — the one thing it must never do — fails this
 * test with a violation instead of acting. The engine the check repeats on answers
 * every recorded prompt and seed with the recorded answer: a backend that repeats.
 */
class RunVerificationGoldenTest {

    private val scenario = GoldenScenarios.all.single {
        it.source.fileStem == "showcase_full_agent" && it.name == "task-every-subpipeline"
    }

    @Test
    fun `given a finished run with tools and sub-pipelines when checked then every call matches and no tool runs`() =
        runTest {
            val harness = finishedRun()
            val calls = recordedCalls(harness)

            val plan = plan(harness)
            val events = VerifyRunUseCase(loader(), repeatingEngine(calls), models(calls)).invoke(plan).toList()

            assertEquals(calls.count { it.nodeType != "TOOL" }, plan.calls)
            assertTrue(
                "the run has sub-pipelines",
                plan.visits.any {
                    it.kind == VisitKind.SubPipeline && it.depth == 0
                },
            )
            assertTrue("the run has visits one level down", plan.visits.any { it.depth == 1 })
            assertEquals(
                2,
                plan.visits.count { it.kind == VisitKind.NotVerifiable(NotVerifiableReason.TOOL_NOT_EXECUTED) },
            )
            val finished = events.last() as VerificationEvent.Finished
            assertEquals(
                VerificationSummary.AllMatched(plan.calls, plan.visits.count { it.kind is VisitKind.NotVerifiable }),
                finished.summary,
            )
        }

    @Test
    fun `given a backend that answers one prompt differently when checked then that visit diverges`() = runTest {
        val harness = finishedRun()
        val calls = recordedCalls(harness)
        val plan = plan(harness)
        val changed = plan.visits.last { it.kind == VisitKind.Repeat }.calls.single()

        val events = VerifyRunUseCase(loader(), repeatingEngine(calls, differentFor = changed), models(calls))
            .invoke(plan).toList()

        val summary = (events.last() as VerificationEvent.Finished).summary as VerificationSummary.SomeDiverged
        assertEquals(1, summary.divergedVisits)
        assertEquals(plan.visits.indexOfLast { it.kind == VisitKind.Repeat }, summary.firstVisitIndex)
    }

    /** Runs the scenario to its end and closes the tool catalogue behind it. */
    private suspend fun TestScope.finishedRun(): GoldenTraceHarness =
        GoldenTraceHarness(scenario).also { it.run(this) }.also { it.refuseToolCalls() }

    private suspend fun recordedCalls(harness: GoldenTraceHarness): List<RunTraceRecord.LocalModelCall> {
        val root = harness.runRecords.getRun(ROOT_RUN)!!
        val runs = listOf(root) + harness.runRecords.getDescendantRuns(root.id)
        return runs.flatMap { harness.runTrace.getTraceForRun(it.id) }.filterIsInstance<RunTraceRecord.LocalModelCall>()
    }

    private suspend fun plan(harness: GoldenTraceHarness): VerificationPlan {
        val calls = recordedCalls(harness)
        val availability = PlanRunVerificationUseCase(
            ReadRecordedRunTreeUseCase(harness.runRecords, harness.runTrace),
            models(calls),
            mockk<PipelineRepository> { coEvery { getPipelineById(any()) } returns null },
            mockk<GenerationSettings> {
                every { localModelBackend } returns flowOf("CPU")
                every { maxContextLength } returns flowOf(GoldenModel.CONTEXT_WINDOW)
            },
        ).invoke(ROOT_RUN)
        return (availability as VerifyAvailability.Available).plan
    }

    private fun models(calls: List<RunTraceRecord.LocalModelCall>): LocalModelRepository = mockk {
        coEvery { findByPath(GoldenModel.MODEL_PATH) } returns
            LocalModel(name = GoldenModel.MODEL_NAME, path = GoldenModel.MODEL_PATH, size = 1, isActive = true)
        coEvery { fileStatus(GoldenModel.MODEL_PATH) } returns ModelFileStatus.Hashed(calls.first().modelSha256!!)
    }

    private fun loader(): LoadModelUseCase = mockk {
        coEvery { this@mockk.invoke(any(), any(), any()) } returns
            Result.Success(Unit)
    }

    /**
     * An engine that repeats: each recorded prompt and seed gives the recorded answer —
     * except [differentFor], which gets another one.
     */
    private fun repeatingEngine(
        calls: List<RunTraceRecord.LocalModelCall>,
        differentFor: RunTraceRecord.LocalModelCall? = null,
    ): LlmInferenceEngine {
        val answers = calls.associate { (it.prompt to it.sampling) to it.output }
        return mockk {
            every { currentModelPath } returns GoldenModel.MODEL_PATH
            every { activeBackend } returns LocalBackend.CPU
            every { activeContextLength } returns GoldenModel.CONTEXT_WINDOW
            every { generateResponseStream(any(), any(), any()) } answers {
                val key = firstArg<String>() to thirdArg<LocalSampling>()
                val recorded = answers.getValue(key)
                flowOf(
                    if (differentFor != null &&
                        key == (differentFor.prompt to differentFor.sampling)
                    ) {
                        "$recorded!"
                    } else {
                        recorded
                    },
                )
            }
        }
    }

    private companion object {
        /** The harness's root run id. */
        const val ROOT_RUN = "golden"
    }
}
