package app.knotwork.android.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frozen-schema migration regression suite built on Room's
 * [MigrationTestHelper]. Unlike the hand-transcribed-DDL
 * [AppDatabaseMigrationTest] (which covers pre-baseline versions that were
 * never exported), this suite drives every migration whose **starting**
 * schema JSON is committed under `app/schemas/` — i.e. the
 * baseline-and-up range `23 → 31`.
 *
 * For each step it:
 * 1. Materialises the database at the older version straight from the
 *    frozen `<old>.json` snapshot ([MigrationTestHelper.createDatabase]).
 * 2. Seeds representative rows into the tables the migration touches.
 * 3. Runs the migration under test and **validates the resulting schema**
 *    against the frozen `<new>.json` snapshot
 *    ([MigrationTestHelper.runMigrationsAndValidate] with
 *    `validateDroppedTables = true`).
 * 4. Asserts that pre-existing data survived and that newly added columns
 *    carry their documented defaults.
 *
 * Because every migration is invoked through the same algorithm Room uses
 * on a real device, a green run here is direct evidence that an in-place
 * upgrade across the baseline range preserves user data — the guarantee
 * that replaced the former `fallbackToDestructiveMigration(true)`.
 *
 * The helper uses the default [androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory]
 * (plaintext); migrations operate on the schema regardless of the
 * SQLCipher cipher used by the production [app.knotwork.android.di.AppModule] builder, so the
 * SQL logic is exercised faithfully.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationHelperTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate23to24_addsPipelinePresetsTableAndPreservesMemory() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            seedMemoryChunkV23(db)
        }

        helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24).use { db ->
            assertMemoryChunkPreserved(db)
            // The new table must accept a full row round-trip.
            db.execSQL(
                "INSERT INTO pipeline_presets" +
                    "(id, name, description, categoryKey, graphJson, tagsCsv, createdAt) " +
                    "VALUES('p1', 'Test', 'desc', 'local', '{}', 'a,b', 42)",
            )
            assertEquals("p1", querySingleString(db, "SELECT id FROM pipeline_presets"))
        }
    }

    @Test
    fun migrate24to25_addsPromptPresetsTableAndPreservesMemory() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            seedMemoryChunkV23(db)
        }

        helper.runMigrationsAndValidate(TEST_DB, 25, true, AppDatabase.MIGRATION_24_25).use { db ->
            assertMemoryChunkPreserved(db)
            db.execSQL(
                "INSERT INTO prompt_presets" +
                    "(id, name, description, nodeTypeKey, systemPrompt, tagsCsv, createdAt) " +
                    "VALUES('q1', 'Test', 'desc', 'LITE_RT', 'You are helpful', '', 7)",
            )
            assertEquals("q1", querySingleString(db, "SELECT id FROM prompt_presets"))
        }
    }

    @Test
    fun migrate25to26_addsSourceColumnBackfilledToUnknown() {
        helper.createDatabase(TEST_DB, 25).use { db ->
            seedMemoryChunkV23(db)
        }

        helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26).use { db ->
            assertMemoryChunkPreserved(db)
            assertEquals(
                "source must back-fill to the Unknown encoding",
                "{\"type\":\"unknown\"}",
                querySingleString(db, "SELECT source FROM memory_chunks"),
            )
        }
    }

    @Test
    fun migrate26to27_addsTagsUseCountAndLastUsedWithDefaults() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            // v26 added the `source` column, so seed it explicitly.
            db.execSQL(
                "INSERT INTO memory_chunks(text, embedding, timestamp, isPinned, source) " +
                    "VALUES('$CHUNK_TEXT', '$CHUNK_EMBEDDING', $CHUNK_TS, 0, '{\"type\":\"manual\"}')",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 27, true, AppDatabase.MIGRATION_26_27).use { db ->
            assertMemoryChunkPreserved(db)
            db.query("SELECT tagsCsv, useCount, lastUsedAt FROM memory_chunks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("tagsCsv must back-fill to empty", "", c.getString(0))
                assertEquals("useCount must back-fill to 0", 0, c.getInt(1))
                assertTrue("lastUsedAt must back-fill to NULL", c.isNull(2))
            }
        }
    }

    @Test
    fun migrate27to28_addsNeedsReembeddingBackfilledToZero() {
        helper.createDatabase(TEST_DB, 27).use { db ->
            // v27 added tagsCsv/useCount/lastUsedAt on top of v26's source.
            db.execSQL(
                "INSERT INTO memory_chunks" +
                    "(text, embedding, timestamp, isPinned, source, tagsCsv, useCount, lastUsedAt) " +
                    "VALUES('$CHUNK_TEXT', '$CHUNK_EMBEDDING', $CHUNK_TS, 0, '{\"type\":\"manual\"}', 'x', 3, 99)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 28, true, AppDatabase.MIGRATION_27_28).use { db ->
            assertMemoryChunkPreserved(db)
            db.query("SELECT needsReembedding FROM memory_chunks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("needsReembedding must back-fill to 0", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate28to29_convertsEmbeddingStringsToLittleEndianBlobs() {
        helper.createDatabase(TEST_DB, 28).use { db ->
            // Two representative legacy rows: one healthy comma-encoded
            // embedding and one malformed string (the kind a hand-edited or
            // partially corrupted DB can carry).
            db.execSQL(
                "INSERT INTO memory_chunks" +
                    "(id, text, embedding, timestamp, isPinned, source, tagsCsv, useCount, lastUsedAt, " +
                    "needsReembedding) " +
                    "VALUES(1, '$CHUNK_TEXT', '$CHUNK_EMBEDDING', $CHUNK_TS, 1, '{\"type\":\"manual\"}', " +
                    "'tag-a,tag-b', 3, 99, 0)",
            )
            db.execSQL(
                "INSERT INTO memory_chunks" +
                    "(id, text, embedding, timestamp, isPinned, source, tagsCsv, useCount, lastUsedAt, " +
                    "needsReembedding) " +
                    "VALUES(2, 'corrupt but repairable', 'not,a,float', $CHUNK_TS, 0, '{\"type\":\"unknown\"}', " +
                    "'', 0, NULL, 1)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 29, true, AppDatabase.MIGRATION_28_29).use { db ->
            db.query(
                "SELECT text, embedding, timestamp, isPinned, source, tagsCsv, useCount, lastUsedAt, " +
                    "needsReembedding FROM memory_chunks WHERE id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(CHUNK_TEXT, c.getString(0))
                // The blob must be the exact little-endian encoding of the
                // legacy comma-separated values.
                assertArrayEquals(
                    EmbeddingBlobCodec.encode(floatArrayOf(0.1f, 0.2f, 0.3f)),
                    c.getBlob(1),
                )
                assertEquals(CHUNK_TS, c.getLong(2))
                assertEquals(1, c.getInt(3))
                assertEquals("{\"type\":\"manual\"}", c.getString(4))
                assertEquals("tag-a,tag-b", c.getString(5))
                assertEquals(3, c.getInt(6))
                assertEquals(99L, c.getLong(7))
                assertEquals(0, c.getInt(8))
            }
            // The malformed row survives with the zero-length "no usable
            // embedding" marker — its text stays repairable by the re-embed
            // path instead of being deleted.
            db.query("SELECT text, embedding, needsReembedding FROM memory_chunks WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("corrupt but repairable", c.getString(0))
                assertEquals(0, c.getBlob(1).size)
                assertEquals(1, c.getInt(2))
            }
        }
    }

    @Test
    fun migrate30to31_extendsTraceStepsPreservingLegacyRows() {
        helper.createDatabase(TEST_DB, 30).use { db ->
            // The legacy FK requires the parent session row to exist.
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, isStarred) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 0)",
            )
            db.execSQL(
                "INSERT INTO trace_steps(sessionId, nodeName, outputText, timestamp, durationMs, tokenCount) " +
                    "VALUES('$SESSION_ID', 'LITE_RT', 'legacy output', $CHUNK_TS, 120, 9)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 31, true, AppDatabase.MIGRATION_30_31).use { db ->
            // The legacy row survives the table recreation with NULL run
            // attribution and the documented NODE_IO defaults.
            db.query(
                "SELECT sessionId, nodeName, outputText, timestamp, durationMs, tokenCount, " +
                    "runId, seq, recordKind, consoleEventType, nodeId, inputText FROM trace_steps",
            ).use { c ->
                assertTrue("legacy trace row must survive", c.moveToFirst())
                assertEquals(SESSION_ID, c.getString(0))
                assertEquals("LITE_RT", c.getString(1))
                assertEquals("legacy output", c.getString(2))
                assertEquals(CHUNK_TS, c.getLong(3))
                assertEquals(120L, c.getLong(4))
                assertEquals(9, c.getInt(5))
                assertTrue("legacy rows carry no run attribution", c.isNull(6))
                assertEquals(0L, c.getLong(7))
                assertEquals("NODE_IO", c.getString(8))
                assertTrue(c.isNull(9))
                assertTrue(c.isNull(10))
                assertTrue(c.isNull(11))
            }
            // A new-style console-event row round-trips against the new schema.
            db.execSQL(
                "INSERT INTO pipeline_runs" +
                    "(id, sessionId, pipelineId, origin, status, startedAt) " +
                    "VALUES('run-1', '$SESSION_ID', 'p1', 'CHAT', 'COMPLETED', $CHUNK_TS)",
            )
            db.execSQL(
                "INSERT INTO trace_steps" +
                    "(sessionId, nodeName, outputText, timestamp, durationMs, runId, seq, " +
                    "recordKind, consoleEventType) " +
                    "VALUES('$SESSION_ID', '', '▶ LITE_RT', $CHUNK_TS, 0, 'run-1', 1, " +
                    "'CONSOLE_EVENT', 'NODE_EXECUTION')",
            )
            assertEquals(
                "run-1",
                querySingleString(db, "SELECT runId FROM trace_steps WHERE recordKind = 'CONSOLE_EVENT'"),
            )
        }
    }

    @Test
    fun migrate31to32_addsCheckpointColumnsPreservingRows() {
        helper.createDatabase(TEST_DB, 31).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, isStarred) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 0)",
            )
            db.execSQL(
                "INSERT INTO pipeline_runs(id, sessionId, pipelineId, origin, status, startedAt) " +
                    "VALUES('run-1', '$SESSION_ID', 'p1', 'CHAT', 'INTERRUPTED', $CHUNK_TS)",
            )
            db.execSQL(
                "INSERT INTO trace_steps(sessionId, nodeName, outputText, timestamp, durationMs, " +
                    "runId, seq, recordKind, nodeId, inputText) " +
                    "VALUES('$SESSION_ID', 'LITE_RT', 'out', $CHUNK_TS, 5, 'run-1', 0, 'NODE_IO', 'n1', 'in')",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 32, true, AppDatabase.MIGRATION_31_32).use { db ->
            // Pre-existing rows survive with the new columns defaulting to
            // NULL — the documented "recorded before checkpoint support"
            // semantics on both tables.
            db.query("SELECT conditionResult, routingKey, resolvedToolName, outputText FROM trace_steps").use { c ->
                assertTrue("pre-existing trace row must survive", c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
                assertEquals("out", c.getString(3))
            }
            db.query("SELECT userPrompt, status FROM pipeline_runs WHERE id = 'run-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("legacy runs carry no recorded prompt", c.isNull(0))
                assertEquals("INTERRUPTED", c.getString(1))
            }
            // New-style values round-trip against the validated schema.
            db.execSQL(
                "UPDATE trace_steps SET conditionResult = 1, routingKey = 'Pass', " +
                    "resolvedToolName = 'calendar.create'",
            )
            db.execSQL("UPDATE pipeline_runs SET userPrompt = 'original prompt' WHERE id = 'run-1'")
            db.query("SELECT conditionResult, routingKey, resolvedToolName FROM trace_steps").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("Pass", c.getString(1))
                assertEquals("calendar.create", c.getString(2))
            }
            assertEquals(
                "original prompt",
                querySingleString(db, "SELECT userPrompt FROM pipeline_runs WHERE id = 'run-1'"),
            )
        }
    }

    @Test
    fun migrate32to33_createsPendingInteractionsTableWithExpectedColumns() {
        helper.createDatabase(TEST_DB, 32).use { db ->
            db.execSQL(
                "INSERT INTO pipeline_runs(id, sessionId, origin, status, startedAt) " +
                    "VALUES('run-1', '$SESSION_ID', 'CHAT', 'WAITING_APPROVAL', $CHUNK_TS)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 33, true, AppDatabase.MIGRATION_32_33).use { db ->
            // Pre-existing run rows survive untouched.
            db.query("SELECT status FROM pipeline_runs WHERE id = 'run-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("WAITING_APPROVAL", c.getString(0))
            }
            // The new table accepts a full parked-approval row and reads it back.
            db.execSQL(
                "INSERT INTO pending_interactions(runId, sessionId, kind, toolName, toolArgs, " +
                    "risk, decision, requestedAt) " +
                    "VALUES('run-1', '$SESSION_ID', 'APPROVAL', 'send_email', '{}', " +
                    "'SENSITIVE', NULL, $CHUNK_TS)",
            )
            db.query(
                "SELECT kind, toolName, risk, decision, answer FROM pending_interactions WHERE runId = 'run-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("APPROVAL", c.getString(0))
                assertEquals("send_email", c.getString(1))
                assertEquals("SENSITIVE", c.getString(2))
                assertTrue("decision starts unanswered", c.isNull(3))
                assertTrue("answer starts unanswered", c.isNull(4))
            }
        }
    }

    @Test
    fun migrate37to38_createsChatHistorySummariesTableWithExpectedColumns() {
        helper.createDatabase(TEST_DB, 37).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, isStarred) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 38, true, AppDatabase.MIGRATION_37_38).use { db ->
            // Pre-existing session row survives untouched.
            assertEquals("Chat", querySingleString(db, "SELECT name FROM chat_sessions WHERE id = '$SESSION_ID'"))
            // The new table accepts a full summary row and reads it back.
            db.execSQL(
                "INSERT INTO chat_history_summaries(sessionId, summary, coveredMessageCount, updatedAt) " +
                    "VALUES('$SESSION_ID', 'older context', 12, $CHUNK_TS)",
            )
            db.query(
                "SELECT summary, coveredMessageCount, updatedAt FROM chat_history_summaries " +
                    "WHERE sessionId = '$SESSION_ID'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("older context", c.getString(0))
                assertEquals(12, c.getInt(1))
                assertEquals(CHUNK_TS, c.getLong(2))
            }
        }
    }

    @Test
    fun migrate37to38_cascadeDeletesSummaryWithItsSession() {
        helper.createDatabase(TEST_DB, 37).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, isStarred) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 38, true, AppDatabase.MIGRATION_37_38).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO chat_history_summaries(sessionId, summary, coveredMessageCount, updatedAt) " +
                    "VALUES('$SESSION_ID', 'older context', 5, $CHUNK_TS)",
            )
            db.execSQL("DELETE FROM chat_sessions WHERE id = '$SESSION_ID'")
            db.query("SELECT COUNT(*) FROM chat_history_summaries").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("summary must cascade-delete with its session", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrateFullChain23to33_preservesDataAndValidatesFinalSchema() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            seedMemoryChunkV23(db)
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            33,
            true,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27,
            AppDatabase.MIGRATION_27_28,
            AppDatabase.MIGRATION_28_29,
            AppDatabase.MIGRATION_29_30,
            AppDatabase.MIGRATION_30_31,
            AppDatabase.MIGRATION_31_32,
            AppDatabase.MIGRATION_32_33,
        ).use { db ->
            // The original v23 row must survive every step with the new
            // columns filled by their documented defaults and the embedding
            // re-encoded into its binary form.
            db.query(
                "SELECT text, source, tagsCsv, useCount, lastUsedAt, needsReembedding, embedding " +
                    "FROM memory_chunks",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(CHUNK_TEXT, c.getString(0))
                assertEquals("{\"type\":\"unknown\"}", c.getString(1))
                assertEquals("", c.getString(2))
                assertEquals(0, c.getInt(3))
                assertTrue(c.isNull(4))
                assertEquals(0, c.getInt(5))
                assertArrayEquals(
                    EmbeddingBlobCodec.encode(floatArrayOf(0.1f, 0.2f, 0.3f)),
                    c.getBlob(6),
                )
            }
        }
    }

    @Test
    fun migrate50to51_createsTriggerEvaluationsTableWithExpectedColumns() {
        // The migration only adds a new table; the v50 contents are irrelevant,
        // so seed a single trigger row just to have a populated starting DB.
        helper.createDatabase(TEST_DB, 50).use { db ->
            db.execSQL(
                "INSERT INTO triggers(id, name, pipelineId, prompt, conditionJson, enabled, armed, createdAt) " +
                    "VALUES('$TRIGGER_ID', 'Nightly', 'pipe-1', 'go', '{}', 1, 1, $CHUNK_TS)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 51, true, AppDatabase.MIGRATION_50_51).use { db ->
            // Pre-existing trigger rows survive untouched.
            db.query("SELECT name FROM triggers WHERE id = '$TRIGGER_ID'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Nightly", c.getString(0))
            }
            // The new journal table round-trips a full fired-then-settled row.
            db.execSQL(
                "INSERT INTO trigger_evaluations(id, triggerId, evaluatedAt, source, verdictKind, " +
                    "skipReason, runId, outcomeKind, outcomeError) " +
                    "VALUES('ev-1', '$TRIGGER_ID', $CHUNK_TS, 'POLL', 'FIRED', NULL, 'run-1', 'FAILURE', 'boom')",
            )
            db.query(
                "SELECT triggerId, source, verdictKind, skipReason, runId, outcomeKind, outcomeError " +
                    "FROM trigger_evaluations WHERE id = 'ev-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(TRIGGER_ID, c.getString(0))
                assertEquals("POLL", c.getString(1))
                assertEquals("FIRED", c.getString(2))
                assertTrue("skipReason is null for a fired verdict", c.isNull(3))
                assertEquals("run-1", c.getString(4))
                assertEquals("FAILURE", c.getString(5))
                assertEquals("boom", c.getString(6))
            }
        }
    }

    @Test
    fun migrate53to54_addsIsArchivedBackfilledToZeroPreservingSessions() {
        helper.createDatabase(TEST_DB, 53).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, pipelineId, isStarred) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 'pipe-1', 1)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 54, true, AppDatabase.MIGRATION_53_54).use { db ->
            db.query(
                "SELECT name, pipelineId, isStarred, isArchived FROM chat_sessions WHERE id = '$SESSION_ID'",
            ).use { c ->
                assertTrue("seeded session must survive the migration", c.moveToFirst())
                assertEquals("Chat", c.getString(0))
                assertEquals("pipe-1", c.getString(1))
                assertEquals("existing flags must be untouched", 1, c.getInt(2))
                // The whole point of the DEFAULT 0: an upgraded install shows
                // exactly the same thread list it showed before.
                assertEquals("pre-existing sessions must upgrade to not-archived", 0, c.getInt(3))
            }

            // The new column is writable and readable through the same row.
            db.execSQL("UPDATE chat_sessions SET isArchived = 1 WHERE id = '$SESSION_ID'")
            db.query("SELECT isArchived FROM chat_sessions WHERE id = '$SESSION_ID'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Inserts a single representative chunk using the v23-era column set
     * (`text`, `embedding`, `timestamp`, `isPinned`). Valid for any
     * starting version `23 ≤ v ≤ 25`, where these columns are still the
     * only non-defaulted ones on `memory_chunks`.
     */
    private fun seedMemoryChunkV23(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO memory_chunks(text, embedding, timestamp, isPinned) " +
                "VALUES('$CHUNK_TEXT', '$CHUNK_EMBEDDING', $CHUNK_TS, 0)",
        )
    }

    /** Asserts the seeded chunk's identity columns survived a migration unchanged. */
    private fun assertMemoryChunkPreserved(db: SupportSQLiteDatabase) {
        db.query("SELECT text, embedding, timestamp FROM memory_chunks").use { c ->
            assertTrue("seeded chunk must survive the migration", c.moveToFirst())
            assertEquals(CHUNK_TEXT, c.getString(0))
            assertEquals(CHUNK_EMBEDDING, c.getString(1))
            assertEquals(CHUNK_TS, c.getLong(2))
        }
    }

    private fun querySingleString(db: SupportSQLiteDatabase, sql: String): String {
        db.query(sql).use { c ->
            assertTrue(c.moveToFirst())
            return c.getString(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-helper-test.db"
        const val CHUNK_TEXT = "remember me"
        const val CHUNK_EMBEDDING = "0.1,0.2,0.3"
        const val CHUNK_TS = 1_700_000_000_000L
        const val SESSION_ID = "session-mig"
        const val TRIGGER_ID = "trigger-mig"
    }

    @Test
    fun migrate54to55_addsNullableArchivedAtPreservingSessions() {
        helper.createDatabase(TEST_DB, 54).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, pipelineId, isStarred, isArchived) " +
                    "VALUES('$SESSION_ID', 'Chat', $CHUNK_TS, 'pipe-1', 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 55, true, AppDatabase.MIGRATION_54_55).use { db ->
            db.query(
                "SELECT name, isArchived, archivedAt FROM chat_sessions WHERE id = '$SESSION_ID'",
            ).use { c ->
                assertTrue("seeded session must survive the migration", c.moveToFirst())
                assertEquals("Chat", c.getString(0))
                assertEquals("existing archive flag must be untouched", 1, c.getInt(1))
                // Nothing invents an archive instant for a row archived before
                // the column existed; the archive query COALESCEs to updatedAt.
                assertTrue("archivedAt must upgrade to NULL", c.isNull(2))
            }

            db.execSQL("UPDATE chat_sessions SET archivedAt = $CHUNK_TS WHERE id = '$SESSION_ID'")
            db.query("SELECT archivedAt FROM chat_sessions WHERE id = '$SESSION_ID'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(CHUNK_TS, c.getLong(0))
            }
        }
    }

    @Test
    fun migrate55to56_addsHitlColumnsDefaultingToNoGatePreservingEvaluations() {
        helper.createDatabase(TEST_DB, 55).use { db ->
            db.execSQL(
                "INSERT INTO trigger_evaluations(id, triggerId, evaluatedAt, source, verdictKind, " +
                    "skipReason, runId, outcomeKind, outcomeError) " +
                    "VALUES('eval-1', '$TRIGGER_ID', $CHUNK_TS, 'POLL', 'FIRED', NULL, 'run-1', " +
                    "'SUCCESS', NULL)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 56, true, AppDatabase.MIGRATION_55_56).use { db ->
            db.query(
                "SELECT verdictKind, outcomeKind, hitlGateCount, hitlLastKind, hitlLastResolution, " +
                    "hitlParked FROM trigger_evaluations WHERE id = 'eval-1'",
            ).use { c ->
                assertTrue("seeded evaluation must survive the migration", c.moveToFirst())
                assertEquals("FIRED", c.getString(0))
                assertEquals("SUCCESS", c.getString(1))
                // A pre-v56 row means "nothing recorded gates back then", which
                // reads back as a run that never asked — not as a gate of an
                // unknown kind.
                assertEquals("gate count must upgrade to none", 0, c.getInt(2))
                assertTrue("kind must upgrade to NULL", c.isNull(3))
                assertTrue("resolution must upgrade to NULL", c.isNull(4))
                assertEquals("parked flag must upgrade to false", 0, c.getInt(5))
            }

            db.execSQL(
                "UPDATE trigger_evaluations SET hitlGateCount = 1, hitlLastKind = 'APPROVAL', " +
                    "hitlLastResolution = 'APPROVED', hitlParked = 1 WHERE id = 'eval-1'",
            )
            db.query(
                "SELECT hitlGateCount, hitlLastKind, hitlLastResolution, hitlParked " +
                    "FROM trigger_evaluations WHERE id = 'eval-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("APPROVAL", c.getString(1))
                assertEquals("APPROVED", c.getString(2))
                assertEquals(1, c.getInt(3))
            }
        }
    }

    @Test
    fun migrate56to57_addsPipelineDaySetPreservingExistingUsageCounters() {
        helper.createDatabase(TEST_DB, 56).use { db ->
            db.execSQL("INSERT INTO usage_counter(category, counterKey, count) VALUES('pipeline_run', 'pipe-1', 4)")
            db.execSQL("INSERT INTO usage_active_day(day) VALUES('2026-06-25')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 57, true, AppDatabase.MIGRATION_56_57).use { db ->
            db.query("SELECT count FROM usage_counter WHERE counterKey = 'pipe-1'").use { c ->
                assertTrue("seeded counter must survive the migration", c.moveToFirst())
                assertEquals(4, c.getInt(0))
            }
            // The historical counters carry no dates, so the new set starts
            // empty rather than being back-filled from invented days.
            db.query("SELECT COUNT(*) FROM usage_pipeline_day").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("upgraded install must start with no per-day activity", 0, c.getInt(0))
            }

            db.execSQL("INSERT INTO usage_pipeline_day(day, pipelineId) VALUES('2026-06-25', 'pipe-1')")
            // The composite primary key is what makes the table a set: a second
            // run of the same pipeline that day must not add a row.
            db.execSQL("INSERT OR IGNORE INTO usage_pipeline_day(day, pipelineId) VALUES('2026-06-25', 'pipe-1')")
            db.query("SELECT COUNT(*) FROM usage_pipeline_day").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate57to58_addsExternalAutomationJournalPreservingExistingRows() {
        helper.createDatabase(TEST_DB, 57).use { db ->
            db.execSQL("INSERT INTO usage_active_day(day) VALUES('2026-08-18')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 58, true, AppDatabase.MIGRATION_57_58).use { db ->
            db.query("SELECT COUNT(*) FROM usage_active_day").use { c ->
                assertTrue("seeded row must survive the migration", c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            // Purely additive: an upgraded install starts with an empty journal
            // rather than a back-filled one.
            db.query("SELECT COUNT(*) FROM external_automation_requests").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("upgraded install must start with no request history", 0, c.getInt(0))
            }

            // A refusal: no target, no callback address, no run — every one of
            // those columns has to be genuinely nullable, because the commonest
            // row in this table is a call that said almost nothing.
            db.execSQL(
                "INSERT INTO external_automation_requests(" +
                    "id, requestId, receivedAt, action, targetKind, targetValue, declaredReturnPackage, " +
                    "returnAction, attestedSenderPackage, statusKind, statusReason, runId, repeatCount) " +
                    "VALUES('j-1', 'req-1', 100, 'app.knotwork.android.action.RUN_PIPELINE', NULL, NULL, NULL, " +
                    "'app.knotwork.android.action.RUN_RESULT', NULL, 'Rejected', 'CONTRACT_DISABLED', NULL, 3)",
            )
            db.query("SELECT statusReason, repeatCount FROM external_automation_requests WHERE id = 'j-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("CONTRACT_DISABLED", c.getString(0))
                assertEquals(3, c.getInt(1))
            }
        }
    }

    /**
     * v58 -> v59: `pipeline_runs` gains the two spend counters and the typed
     * termination reason behind the autonomous-run ceilings.
     *
     * The counters must default to zero for a row that predates them — that is
     * what lets an in-flight run picked up across an upgrade simply start
     * counting here rather than being refused. The `DEFAULT 0` matching the
     * entity's `@ColumnInfo(defaultValue = "0")` is validated by
     * `runMigrationsAndValidate` itself: drop it on either side and the
     * `TableInfo` comparison fails.
     */
    @Test
    fun migrate58to59_addsSpendCountersAndTerminationReasonDefaultingToZero() {
        helper.createDatabase(TEST_DB, 58).use { db ->
            db.execSQL(
                "INSERT INTO pipeline_runs(" +
                    "id, sessionId, pipelineId, origin, status, currentNodeId, startedAt, finishedAt, " +
                    "errorMessage, graphContentHash, userPrompt, parentRunId, hadImage) " +
                    "VALUES('run-old', 'sess-1', 'pipe-1', 'TRIGGER', 'RUNNING', 'node-1', 100, NULL, " +
                    "NULL, 'hash-1', 'prompt', NULL, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 59, true, AppDatabase.MIGRATION_58_59).use { db ->
            db.query(
                "SELECT stepsSpent, tokensSpent, terminationReason FROM pipeline_runs WHERE id = 'run-old'",
            ).use { c ->
                assertTrue("the pre-existing run must survive the migration", c.moveToFirst())
                assertEquals("a row that predates the counters has spent nothing", 0, c.getInt(0))
                assertEquals(0, c.getInt(1))
                // "Why did this stop" was only ever recorded as prose, and a
                // migration must not try to parse prose back into an enum.
                assertTrue("historical rows carry no typed reason", c.isNull(2))
            }

            db.execSQL(
                "UPDATE pipeline_runs SET stepsSpent = 12, tokensSpent = 4200, " +
                    "terminationReason = 'TOKEN_CEILING' WHERE id = 'run-old'",
            )
            db.query(
                "SELECT stepsSpent, tokensSpent, terminationReason FROM pipeline_runs WHERE id = 'run-old'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(12, c.getInt(0))
                assertEquals(4200, c.getInt(1))
                assertEquals("TOKEN_CEILING", c.getString(2))
            }
        }
    }

    /**
     * v62 → v63 adds `chat_messages.imported`. Every row that existed before was written
     * on this device, so it must come out `0` — and the schema must match the exported
     * `63.json`, which `runMigrationsAndValidate` checks.
     */
    @Test
    fun migrate62to63_marksExistingMessagesAsWrittenOnThisDevice() {
        helper.createDatabase(TEST_DB, 62).use { db ->
            db.execSQL(
                "INSERT INTO chat_messages(sessionId, role, content, timestamp) " +
                    "VALUES('sess-1', 'USER', 'hello', 100)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 63, true, AppDatabase.MIGRATION_62_63).use { db ->
            db.query("SELECT imported FROM chat_messages WHERE content = 'hello'").use { c ->
                assertTrue("the pre-existing message must survive the migration", c.moveToFirst())
                assertEquals("a pre-existing message was written on this device", 0, c.getInt(0))
            }
        }
    }
}
