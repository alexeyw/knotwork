package app.knotwork.android.data.local.dao

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.models.PipelineRunEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The count behind the scheduling tool's hourly ceiling, against a real
 * (in-memory) Room database — its correctness is the SQL itself.
 *
 * A pipeline step that runs another pipeline records the nested run as a row of
 * its own, carrying the parent's origin. The ceiling limits how often scheduled
 * **work** fires; a nested run is part of the scheduled run that started it, so
 * it must not use up the allowance.
 */
@RunWith(RobolectricTestRunner::class)
class PipelineRunDaoRateCountTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PipelineRunDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pipelineRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given a scheduled run with nested runs when counted then it counts once`() = runTest {
        dao.insertRun(run("root", origin = "SCHEDULER", startedAt = 1_000L))
        // The child's row is written when its step starts, with the parent's origin.
        dao.insertRun(run("child-1", origin = "SCHEDULER", startedAt = 1_100L, parentRunId = "root"))
        dao.insertRun(run("child-2", origin = "SCHEDULER", startedAt = 1_200L, parentRunId = "child-1"))

        assertEquals(1, dao.countRootRunsByOriginSince("SCHEDULER", sinceEpochMs = 0L))
    }

    @Test
    fun `given runs of several origins and ages when counted then only recent roots of the origin count`() = runTest {
        dao.insertRun(run("old", origin = "SCHEDULER", startedAt = 500L))
        dao.insertRun(run("recent", origin = "SCHEDULER", startedAt = 1_000L))
        dao.insertRun(run("at-bound", origin = "SCHEDULER", startedAt = 900L))
        dao.insertRun(run("chat", origin = "CHAT", startedAt = 1_000L))
        dao.insertRun(run("chat-child", origin = "CHAT", startedAt = 1_100L, parentRunId = "chat"))

        assertEquals(2, dao.countRootRunsByOriginSince("SCHEDULER", sinceEpochMs = 900L))
        assertEquals(1, dao.countRootRunsByOriginSince("CHAT", sinceEpochMs = 900L))
        assertEquals(0, dao.countRootRunsByOriginSince("TRIGGER", sinceEpochMs = 0L))
    }

    private fun run(id: String, origin: String, startedAt: Long, parentRunId: String? = null) = PipelineRunEntity(
        id = id,
        sessionId = "s1",
        pipelineId = "p1",
        origin = origin,
        status = "COMPLETED",
        currentNodeId = null,
        startedAt = startedAt,
        finishedAt = startedAt + 10L,
        errorMessage = null,
        graphContentHash = null,
        parentRunId = parentRunId,
    )
}
