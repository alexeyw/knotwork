package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.local.models.ModelCallEntity
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What makes a run checkable, through the repositories and a real (in-memory)
 * Room database: the run header, the hashed node records and the model calls
 * come back exactly as written, in one `seq` order, and go with their run.
 */
@RunWith(RobolectricTestRunner::class)
class RunRecordPersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var runs: PipelineRunRepositoryImpl
    private lateinit var trace: RunTraceRepositoryImpl

    @Before
    fun setup() = runTest {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runs = PipelineRunRepositoryImpl(
            database.pipelineRunDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        trace = RunTraceRepositoryImpl(database.traceStepDao())
        database.chatDao().insertSession(ChatSessionEntity(id = SESSION, name = "chat", updatedAt = 0L))
        runs.createRun(
            PipelineRun(
                id = RUN,
                sessionId = SESSION,
                pipelineId = "p",
                origin = RunOrigin.CHAT,
                status = PipelineRunStatus.RUNNING,
                currentNodeId = null,
                startedAt = 0L,
                finishedAt = null,
                errorMessage = null,
                graphContentHash = "h",
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given a run header when stored then the run reads it back`() = runTest {
        runs.setHeader(RUN, HEADER)

        assertEquals(HEADER, runs.getRun(RUN)?.header)
    }

    @Test
    fun `given a run without a header when read then it has none`() = runTest {
        assertNull(runs.getRun(RUN)?.header)
    }

    @Test
    fun `given node records and model calls when flushed then the trace merges them in seq order`() = runTest {
        trace.append(console(seq = 0))
        trace.append(LOCAL_CALL.copy(seq = 1))
        trace.append(CLOUD_CALL.copy(seq = 2))
        trace.append(NODE_IO.copy(seq = 3))
        trace.flush()

        val read = trace.getTraceForRun(RUN)

        assertEquals(listOf(0L, 1L, 2L, 3L), read.map { it.seq })
        assertEquals(LOCAL_CALL.copy(seq = 1), read[1])
        assertEquals(CLOUD_CALL.copy(seq = 2), read[2])
        assertEquals(NODE_IO.copy(seq = 3), read[3])
    }

    @Test
    fun `given a run with model calls when the run is deleted then its calls go with it`() = runTest {
        trace.append(LOCAL_CALL)
        trace.flush()

        database.chatDao().deleteSessionCompletely(SESSION)

        assertTrue(database.traceStepDao().getModelCallsForRun(RUN).isEmpty())
    }

    @Test
    fun `given an on-device row missing a column every such call writes when read then it is skipped`() = runTest {
        val row = ModelCallEntity(
            runId = RUN,
            sessionId = SESSION,
            seq = 0L,
            timestamp = 0L,
            depth = 0,
            nodeId = "n",
            nodeType = "LITE_RT",
            visit = 0,
            callIndex = 0,
            engine = ModelCallEntity.ENGINE_LOCAL,
            seed = 1,
            temperature = 0.7,
            topK = 40,
            topP = 0.9,
            prompt = "p",
            output = null,
            promptSha256 = "a",
            outputSha256 = "b",
        )

        // Written behind the repository's back, as a corrupted or foreign row would be.
        database.traceStepDao().insertModelCalls(listOf(row, row.copy(seq = 1L, engine = "QUANTUM")))

        assertTrue(trace.getTraceForRun(RUN).isEmpty())
    }

    private fun console(seq: Long) = RunTraceRecord.ConsoleEntry(
        runId = RUN,
        sessionId = SESSION,
        seq = seq,
        timestamp = 1L,
        type = ConsoleEventType.NodeExecution,
        message = "▶ LITE_RT",
    )

    private companion object {
        const val RUN = "run-1"
        const val SESSION = "session-1"

        val HEADER = RunHeader(
            seed = 98765,
            sampler = RunSampler(temperature = 0.7f.toDouble(), topK = 40, topP = 0.9f.toDouble()),
            appVersion = "0.12.0 (17)",
            runtimeVersion = "LiteRT-LM 0.17.1",
            device = "Samsung SM-S938B · Android 16",
        )

        val LOCAL_CALL = RunTraceRecord.LocalModelCall(
            runId = RUN,
            sessionId = SESSION,
            seq = 0L,
            timestamp = 2L,
            nodeId = "llm",
            nodeType = "LITE_RT",
            visit = 1,
            call = 0,
            depth = 0,
            sampling = LocalSampling(HEADER.sampler, seed = 123),
            modelPath = "/m/gemma.litertlm",
            modelSha256 = "abc",
            backend = LocalBackend.GPU,
            contextWindow = 4096,
            hadImage = false,
            prompt = "the prompt",
            output = "the output",
            promptSha256 = "p-sha",
            outputSha256 = "o-sha",
            durationMs = 1_234L,
        )

        val CLOUD_CALL = RunTraceRecord.CloudModelCall(
            runId = RUN,
            sessionId = SESSION,
            seq = 0L,
            timestamp = 3L,
            nodeId = "cloud",
            nodeType = "CLOUD",
            visit = 0,
            call = 0,
            depth = 0,
            provider = "anthropic",
            model = null,
        )

        val NODE_IO = RunTraceRecord.NodeIo(
            runId = RUN,
            sessionId = SESSION,
            seq = 0L,
            timestamp = 4L,
            nodeId = "llm",
            nodeType = "LITE_RT",
            inputText = "in",
            outputText = "out",
            durationMs = 5L,
            tokenCount = 7,
            visit = 1,
            inputSha256 = "in-sha",
            outputSha256 = "out-sha",
        )
    }
}
