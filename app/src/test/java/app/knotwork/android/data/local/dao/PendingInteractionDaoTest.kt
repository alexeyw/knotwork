package app.knotwork.android.data.local.dao

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.models.PendingInteractionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PendingInteractionDao] against a real (in-memory) Room database, for the two
 * queries whose correctness lives in their SQL rather than in any Kotlin a mock
 * could stand in for: finding a parked approval by the request it parks, and
 * writing a decision only onto the record of the request it answers.
 *
 * Also runs `MIGRATION_61_62` on real SQLite: the mock-based migration test
 * pins the statements, this one pins that they do what they say to real rows.
 */
@RunWith(RobolectricTestRunner::class)
class PendingInteractionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PendingInteractionDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pendingInteractionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given a parked approval when looked up by its request then that record comes back`() = runTest {
        dao.upsert(approval(runId = "run-1", requestId = "request-1"))
        dao.upsert(approval(runId = "run-2", requestId = "request-2"))

        assertEquals("run-1", dao.getForRequest("request-1")?.runId)
        assertEquals("run-2", dao.getForRequest("request-2")?.runId)
        assertNull(dao.getForRequest("request-unknown"))
    }

    @Test
    fun `given a record replaced by a re-park when the first request's decision lands then nothing is written`() =
        runTest {
            // Read the record for request-1, then the same run re-parks with
            // request-2 (REPLACE) before the write: the answer given for
            // request-1 must not become request-2's.
            dao.upsert(approval(runId = "run-1", requestId = "request-1"))
            dao.upsert(approval(runId = "run-1", requestId = "request-2"))

            val written = dao.recordApprovalDecision("run-1", "request-1", "APPROVED")

            assertEquals(0, written)
            assertNull(dao.getForRun("run-1")?.decision)
        }

    @Test
    fun `given the record of the answered request when the decision lands then it is written once`() = runTest {
        dao.upsert(approval(runId = "run-1", requestId = "request-1"))

        assertEquals(1, dao.recordApprovalDecision("run-1", "request-1", "DENIED"))
        assertEquals(0, dao.recordApprovalDecision("run-1", "request-1", "APPROVED"))
        assertEquals("DENIED", dao.getForRun("run-1")?.decision)
    }

    @Test
    fun `given version 61 rows when MIGRATION_61_62 runs then approvals take their run id and others stay null`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(VERSION_61) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(PENDING_INTERACTIONS_V61)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO pending_interactions (runId, sessionId, kind, requestedAt) VALUES " +
                "('run-approval', 's1', 'APPROVAL', 1), ('run-question', 's1', 'CLARIFICATION', 2), " +
                "('run-ceiling', 's1', 'CEILING', 3)",
        )

        AppDatabase.MIGRATION_61_62.migrate(db)

        val requestIds = db.query("SELECT runId, requestId FROM pending_interactions").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
        assertEquals(
            mapOf("run-approval" to "run-approval", "run-question" to null, "run-ceiling" to null),
            requestIds,
        )
        helper.close()
    }

    private fun approval(runId: String, requestId: String) = PendingInteractionEntity(
        runId = runId,
        sessionId = "session-1",
        kind = "APPROVAL",
        toolName = "delete_file",
        toolArgs = "{}",
        risk = "DESTRUCTIVE",
        question = null,
        optionsJson = null,
        decision = null,
        answer = null,
        requestedAt = 0L,
        requestId = requestId,
    )

    private companion object {
        const val VERSION_61 = 61

        /** `pending_interactions` exactly as schema 61 created it (`app/schemas/…/61.json`). */
        const val PENDING_INTERACTIONS_V61 =
            "CREATE TABLE IF NOT EXISTS `pending_interactions` (`runId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `toolName` TEXT, `toolArgs` TEXT, `risk` TEXT, `question` TEXT, " +
                "`optionsJson` TEXT, `decision` TEXT, `answer` TEXT, `ceilingAxis` TEXT, `ceilingLimit` INTEGER, " +
                "`ceilingSpent` INTEGER, `requestedAt` INTEGER NOT NULL, PRIMARY KEY(`runId`))"
    }
}
