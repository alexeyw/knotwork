package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeIds
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.verification.VerificationFixtures.ROOT
import app.knotwork.android.domain.verification.VerificationFixtures.console
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import app.knotwork.android.domain.verification.VerificationFixtures.nodeIo
import app.knotwork.android.domain.verification.VerificationFixtures.run
import app.knotwork.android.domain.verification.VerificationFixtures.tree
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RecordedRunTree] and [ReadRecordedRunTreeUseCase]: one reading of a run tree,
 * so the check, the digest and the export place each sub-pipeline run under the
 * same `PIPELINE` visit — the one that failed and left no record included.
 */
class RecordedRunTreeTest {

    private val first = RunTreeIds.child(ROOT, "pipe", 0)
    private val second = RunTreeIds.child(ROOT, "pipe", 1)
    private val failed = RunTreeIds.child(ROOT, "broken", 0)

    @Test
    fun `given recorded and failed PIPELINE visits when ordered then children follow their visits`() {
        val orphan = run(id = "elsewhere", parentRunId = ROOT).copy(startedAt = 9L)
        val tree = tree(
            run(),
            run(id = failed, parentRunId = ROOT),
            run(id = second, parentRunId = ROOT),
            orphan,
            run(id = first, parentRunId = ROOT),
            traces = mapOf(
                ROOT to listOf(nodeIo(0, "pipe", "PIPELINE"), nodeIo(1, "pipe", "PIPELINE", visit = 1)),
            ),
        )

        assertEquals(listOf(ROOT, first, second, failed, "elsewhere"), tree.inTreeOrder().map { it.id })
        assertEquals(listOf("broken" to 0), tree.unrecordedPipelineVisits(ROOT))
        assertEquals(second, tree.childAt(ROOT, "pipe", 1)?.id)
        assertNull(tree.childAt(ROOT, "pipe", 2))
    }

    @Test
    fun `given a visit recorded only by its model call when asked then it is not unrecorded`() {
        // A PIPELINE visit is recorded by its NodeIo; any record of the visit counts.
        val tree = tree(
            run(),
            run(id = first, parentRunId = ROOT),
            traces = mapOf(ROOT to listOf(localCall(0, "pipe", "PIPELINE"))),
        )

        assertEquals(emptyList<Pair<String, Int>>(), tree.unrecordedPipelineVisits(ROOT))
    }

    @Test
    fun `given each record kind when keyed then only node visits have a key`() {
        assertEquals("a" to 2, nodeIo(0, "a", "LITE_RT", visit = 2).visitKey())
        assertEquals("b" to 0, localCall(0, "b").visitKey())
        assertEquals("c" to 0, VerificationFixtures.cloudCall(0, "c").visitKey())
        assertNull(nodeIo(0, "a", "LITE_RT", visit = null).visitKey())
        assertNull(console(0, "line").visitKey())
    }

    @Test
    fun `given stored runs when read then every trace is in seq order and a missing root is null`() = runTest {
        val child = run(id = first, parentRunId = ROOT)
        val runs: PipelineRunRepository = mockk {
            coEvery { getRun(ROOT) } returns run()
            coEvery { getRun("missing") } returns null
            coEvery { getDescendantRuns(ROOT) } returns listOf(child)
        }
        val traces: RunTraceRepository = mockk {
            coEvery { getTraceForRun(ROOT) } returns listOf(console(2, "late"), nodeIo(1, "pipe", "PIPELINE"))
            coEvery { getTraceForRun(first) } returns listOf(localCall(0, "inner", runId = first))
        }
        val read = ReadRecordedRunTreeUseCase(runs, traces)

        val tree = read(ROOT)!!

        assertEquals(listOf(1L, 2L), tree.traceOf(ROOT).map(RunTraceRecord::seq))
        assertEquals(setOf(ROOT, first), tree.runs.keys)
        assertEquals(3, tree.records.size)
        assertNull(read("missing"))
    }
}
