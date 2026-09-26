package app.knotwork.android.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import app.knotwork.android.domain.models.NodeContextConfig
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the [AppDatabase.MIGRATION_17_18] script.
 *
 * The migration must:
 * 1. Be registered for versions 17 → 18.
 * 2. Add a single `context_config` column to `pipeline_nodes`.
 * 3. Populate that column with a JSON default that — when read back through
 *    [Converters.toNodeContextConfig] — yields [NodeContextConfig.ALL_ENABLED].
 *    This is the contract that keeps pipelines saved before it functionally
 *    identical after the upgrade.
 */
class AppDatabaseMigrationTest {

    @Test
    fun `MIGRATION_17_18 targets versions 17 to 18`() {
        val migration = AppDatabase.MIGRATION_17_18

        assertEquals(17, migration.startVersion)
        assertEquals(18, migration.endVersion)
    }

    @Test
    fun `MIGRATION_17_18 adds context_config column with JSON default`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_17_18.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER TABLE on pipeline_nodes, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE") && sql.contains("PIPELINE_NODES"),
        )
        assertTrue(
            "Expected new context_config column, got: ${sqlSlot.captured}",
            sql.contains("CONTEXT_CONFIG"),
        )
        assertTrue(
            "Column must be NOT NULL so existing rows keep working: ${sqlSlot.captured}",
            sql.contains("NOT NULL"),
        )
        assertTrue(
            "Migration must declare a DEFAULT value: ${sqlSlot.captured}",
            sql.contains("DEFAULT"),
        )
    }

    @Test
    fun `MIGRATION_21_22 targets versions 21 to 22`() {
        val migration = AppDatabase.MIGRATION_21_22

        assertEquals(21, migration.startVersion)
        assertEquals(22, migration.endVersion)
    }

    @Test
    fun `MIGRATION_21_22 adds isStarred column to chat_sessions with default 0`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_21_22.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER TABLE on chat_sessions, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE") && sql.contains("CHAT_SESSIONS"),
        )
        assertTrue(
            "Expected the new isStarred column, got: ${sqlSlot.captured}",
            sql.contains("ISSTARRED"),
        )
        assertTrue(
            "Column must be NOT NULL so existing rows keep working: ${sqlSlot.captured}",
            sql.contains("NOT NULL"),
        )
        assertTrue(
            "Migration must default to 0 (favorited only after the user opts in): ${sqlSlot.captured}",
            sql.contains("DEFAULT 0"),
        )
    }

    @Test
    fun `MIGRATION_17_18 default JSON parses to ALL_ENABLED`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_17_18.migrate(db)
        verify { db.execSQL(capture(sqlSlot)) }

        // Pull the literal between the first pair of single quotes — that is
        // the JSON value SQLite will write into every existing row.
        val raw = sqlSlot.captured
        val firstQuote = raw.indexOf('\'')
        val lastQuote = raw.lastIndexOf('\'')
        assertTrue("Default literal not found in SQL: $raw", firstQuote in 0 until lastQuote)
        val defaultJson = raw.substring(firstQuote + 1, lastQuote)

        val parsed = Converters().toNodeContextConfig(defaultJson)
        assertEquals(NodeContextConfig.ALL_ENABLED, parsed)
    }

    @Test
    fun `MIGRATION_29_30 targets versions 29 to 30`() {
        val migration = AppDatabase.MIGRATION_29_30

        assertEquals(29, migration.startVersion)
        assertEquals(30, migration.endVersion)
    }

    @Test
    fun `MIGRATION_29_30 creates pipeline_runs table with both indices`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_29_30.migrate(db)

        verify(exactly = 3) { db.execSQL(capture(statements)) }

        val createTable = statements.first().uppercase()
        assertTrue(
            "Expected CREATE TABLE pipeline_runs, got: ${statements.first()}",
            createTable.contains("CREATE TABLE") && createTable.contains("PIPELINE_RUNS"),
        )
        // Columns that anchor the run lifecycle and the checkpoint contract.
        listOf(
            "ID", "SESSIONID", "PIPELINEID", "ORIGIN", "STATUS",
            "CURRENTNODEID", "STARTEDAT", "FINISHEDAT", "ERRORMESSAGE", "GRAPHCONTENTHASH",
        ).forEach { column ->
            assertTrue("Missing column $column in: ${statements.first()}", createTable.contains(column))
        }
        assertTrue(
            "Primary key must be the run id: ${statements.first()}",
            createTable.contains("PRIMARY KEY(`ID`)"),
        )
        assertTrue(
            "sessionId must deliberately carry no FK (run may precede its session row): " +
                statements.first(),
            !createTable.contains("FOREIGN KEY"),
        )

        val indexSql = statements.drop(1).joinToString(" ").uppercase()
        assertTrue(
            "Missing sessionId index: $indexSql",
            indexSql.contains("INDEX_PIPELINE_RUNS_SESSIONID"),
        )
        assertTrue(
            "Missing status index (orphan sweep / reattach queries): $indexSql",
            indexSql.contains("INDEX_PIPELINE_RUNS_STATUS"),
        )
    }

    @Test
    fun `MIGRATION_30_31 targets versions 30 to 31`() {
        val migration = AppDatabase.MIGRATION_30_31

        assertEquals(30, migration.startVersion)
        assertEquals(31, migration.endVersion)
    }

    @Test
    fun `MIGRATION_30_31 recreates trace_steps with run attribution and preserves legacy rows`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_30_31.migrate(db)

        // CREATE new + INSERT SELECT + DROP + RENAME + 2 index recreations.
        verify(exactly = 6) { db.execSQL(capture(statements)) }

        val createTable = statements[0].uppercase()
        assertTrue(
            "Expected CREATE TABLE trace_steps_new, got: ${statements[0]}",
            createTable.contains("CREATE TABLE") && createTable.contains("TRACE_STEPS_NEW"),
        )
        // Legacy columns plus the new run-trace columns.
        listOf(
            "SESSIONID", "NODENAME", "OUTPUTTEXT", "TIMESTAMP", "DURATIONMS", "TOKENCOUNT",
            "RUNID", "SEQ", "RECORDKIND", "CONSOLEEVENTTYPE", "NODEID", "INPUTTEXT",
        ).forEach { column ->
            assertTrue("Missing column $column in: ${statements[0]}", createTable.contains(column))
        }
        // SQLite cannot ALTER-in a foreign key — the recreate must carry both
        // the legacy chat_sessions FK and the new pipeline_runs CASCADE FK.
        assertTrue(
            "Missing chat_sessions FK: ${statements[0]}",
            createTable.contains("REFERENCES `CHAT_SESSIONS`"),
        )
        assertTrue(
            "Missing pipeline_runs CASCADE FK (retention deletes traces with their run): ${statements[0]}",
            createTable.contains("REFERENCES `PIPELINE_RUNS`") && createTable.contains("ON DELETE CASCADE"),
        )

        val insert = statements[1].uppercase()
        assertTrue(
            "Legacy rows must be copied, not dropped: ${statements[1]}",
            insert.contains("INSERT INTO `TRACE_STEPS_NEW`") && insert.contains("FROM `TRACE_STEPS`"),
        )
        assertTrue(
            "Legacy rows keep NULL runId and NODE_IO kind: ${statements[1]}",
            insert.contains("NULL") && insert.contains("'NODE_IO'"),
        )

        assertTrue("Old table must be dropped: ${statements[2]}", statements[2].uppercase().contains("DROP TABLE"))
        assertTrue("New table must be renamed: ${statements[3]}", statements[3].uppercase().contains("RENAME TO"))

        val indexSql = statements.drop(4).joinToString(" ").uppercase()
        assertTrue(
            "Missing recreated sessionId index: $indexSql",
            indexSql.contains("INDEX_TRACE_STEPS_SESSIONID"),
        )
        assertTrue(
            "Missing runId index (replay + retention queries): $indexSql",
            indexSql.contains("INDEX_TRACE_STEPS_RUNID"),
        )
    }

    @Test
    fun `MIGRATION_34_35 targets versions 34 to 35`() {
        val migration = AppDatabase.MIGRATION_34_35

        assertEquals(34, migration.startVersion)
        assertEquals(35, migration.endVersion)
    }

    @Test
    fun `MIGRATION_34_35 adds parentRunId self-FK plus index and trace depth column`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_34_35.migrate(db)

        // ALTER pipeline_runs + CREATE INDEX + ALTER trace_steps.
        verify(exactly = 3) { db.execSQL(capture(statements)) }

        val addParent = statements[0].uppercase()
        assertTrue(
            "Expected ALTER pipeline_runs ADD parentRunId, got: ${statements[0]}",
            addParent.contains("ALTER TABLE") &&
                addParent.contains("PIPELINE_RUNS") &&
                addParent.contains("PARENTRUNID"),
        )
        assertTrue(
            "parentRunId must be a self-referential CASCADE FK so retention cascades the sub-tree: ${statements[0]}",
            addParent.contains("REFERENCES `PIPELINE_RUNS`") && addParent.contains("ON DELETE CASCADE"),
        )

        val index = statements[1].uppercase()
        assertTrue(
            "Missing parentRunId index (child-run tree lookups): ${statements[1]}",
            index.contains("INDEX_PIPELINE_RUNS_PARENTRUNID"),
        )

        val depth = statements[2].uppercase()
        assertTrue(
            "Expected ALTER trace_steps ADD depth, got: ${statements[2]}",
            depth.contains("ALTER TABLE") && depth.contains("TRACE_STEPS") && depth.contains("DEPTH"),
        )
        assertTrue(
            "depth must be NOT NULL DEFAULT 0 so legacy rows render at the top level: ${statements[2]}",
            depth.contains("NOT NULL") && depth.contains("DEFAULT 0"),
        )
    }

    @Test
    fun `MIGRATION_38_39 targets versions 38 to 39`() {
        val migration = AppDatabase.MIGRATION_38_39

        assertEquals(38, migration.startVersion)
        assertEquals(39, migration.endVersion)
    }

    @Test
    fun `MIGRATION_38_39 adds four nullable attachment columns to chat_messages`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_38_39.migrate(db)

        verify(exactly = 4) { db.execSQL(capture(statements)) }
        val joined = statements.joinToString(" | ").uppercase()
        listOf("ATTACHMENTPATH", "ATTACHMENTMIMETYPE", "ATTACHMENTWIDTH", "ATTACHMENTHEIGHT").forEach { column ->
            assertTrue(
                "Expected ALTER chat_messages ADD $column, got: $joined",
                joined.contains("ALTER TABLE `CHAT_MESSAGES` ADD COLUMN `$column`"),
            )
        }
        // Additive nullable columns — never NOT NULL, so existing rows keep NULL (no attachment).
        assertTrue(
            "Attachment columns must be nullable (no NOT NULL): $joined",
            !joined.contains("NOT NULL"),
        )
    }

    @Test
    fun `MIGRATION_39_40 targets versions 39 to 40`() {
        val migration = AppDatabase.MIGRATION_39_40

        assertEquals(39, migration.startVersion)
        assertEquals(40, migration.endVersion)
    }

    @Test
    fun `MIGRATION_39_40 adds supportsVision column to local_models with default 0`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_39_40.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(statements)) }
        val sql = statements.single().uppercase()
        assertTrue(
            "Expected ALTER local_models ADD supportsVision, got: $sql",
            sql.contains("ALTER TABLE `LOCAL_MODELS` ADD COLUMN `SUPPORTSVISION`"),
        )
        // Backfilled to text-only (0) for every existing row.
        assertTrue("supportsVision must be NOT NULL DEFAULT 0: $sql", sql.contains("NOT NULL DEFAULT 0"))
    }

    @Test
    fun `MIGRATION_40_41 targets versions 40 to 41`() {
        val migration = AppDatabase.MIGRATION_40_41

        assertEquals(40, migration.startVersion)
        assertEquals(41, migration.endVersion)
    }

    @Test
    fun `MIGRATION_40_41 adds supportsAudio column to local_models with default 0`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_40_41.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(statements)) }
        val sql = statements.single().uppercase()
        assertTrue(
            "Expected ALTER local_models ADD supportsAudio, got: $sql",
            sql.contains("ALTER TABLE `LOCAL_MODELS` ADD COLUMN `SUPPORTSAUDIO`"),
        )
        // Backfilled to audio-incapable (0) for every existing row.
        assertTrue("supportsAudio must be NOT NULL DEFAULT 0: $sql", sql.contains("NOT NULL DEFAULT 0"))
    }

    @Test
    fun `MIGRATION_41_42 targets versions 41 to 42`() {
        val migration = AppDatabase.MIGRATION_41_42

        assertEquals(41, migration.startVersion)
        assertEquals(42, migration.endVersion)
    }

    @Test
    fun `MIGRATION_41_42 creates model_performance_samples table with its index`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_41_42.migrate(db)

        // CREATE TABLE + CREATE INDEX.
        verify(exactly = 2) { db.execSQL(capture(statements)) }

        val createTable = statements.first().uppercase()
        assertTrue(
            "Expected CREATE TABLE model_performance_samples, got: ${statements.first()}",
            createTable.contains("CREATE TABLE") && createTable.contains("MODEL_PERFORMANCE_SAMPLES"),
        )
        listOf(
            "ID", "MODELPATH", "TTFTMS", "DECODETOKENSPERSEC", "TOTALMS",
            "TOKENCOUNT", "PEAKNATIVEHEAPBYTES", "ISBENCHMARK", "CREATEDAT",
        ).forEach { column ->
            assertTrue("Missing column $column in: ${statements.first()}", createTable.contains(column))
        }
        // Samples are keyed by path, not a foreign key onto local_models.
        assertTrue(
            "Samples must not carry a foreign key (keyed by model path): ${statements.first()}",
            !createTable.contains("FOREIGN KEY"),
        )

        val index = statements[1].uppercase()
        assertTrue(
            "Missing (modelPath, id) index for the rolling-window query: ${statements[1]}",
            index.contains("INDEX_MODEL_PERFORMANCE_SAMPLES_MODELPATH_ID"),
        )
    }

    @Test
    fun `MIGRATION_42_43 targets versions 42 to 43`() {
        val migration = AppDatabase.MIGRATION_42_43

        assertEquals(42, migration.startVersion)
        assertEquals(43, migration.endVersion)
    }

    @Test
    fun `MIGRATION_42_43 indexes chat_messages sessionId with Room's generated name`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_42_43.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected CREATE INDEX on chat_messages, got: ${sqlSlot.captured}",
            sql.contains("CREATE INDEX") && sql.contains("CHAT_MESSAGES") && sql.contains("SESSIONID"),
        )
        // Name must match Room's generated index_<table>_<column> or schema
        // validation on the next open would reject the migrated DB.
        assertTrue(
            "Index name must match Room's generated name: ${sqlSlot.captured}",
            sql.contains("INDEX_CHAT_MESSAGES_SESSIONID"),
        )
    }

    @Test
    fun `MIGRATION_43_44 targets versions 43 to 44`() {
        val migration = AppDatabase.MIGRATION_43_44

        assertEquals(43, migration.startVersion)
        assertEquals(44, migration.endVersion)
    }

    @Test
    fun `MIGRATION_43_44 adds a nullable modelName column to chat_messages`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_43_44.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER chat_messages ADD modelName, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE `CHAT_MESSAGES` ADD COLUMN `MODELNAME`"),
        )
        // Additive + nullable so legacy rows keep NULL (no recorded model).
        assertTrue("modelName must be nullable (no NOT NULL): ${sqlSlot.captured}", !sql.contains("NOT NULL"))
    }

    @Test
    fun `MIGRATION_44_45 targets versions 44 to 45`() {
        val migration = AppDatabase.MIGRATION_44_45

        assertEquals(44, migration.startVersion)
        assertEquals(45, migration.endVersion)
    }

    @Test
    fun `MIGRATION_44_45 creates triggers table with enabled index and no pipeline FK`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_44_45.migrate(db)

        // CREATE TABLE + CREATE INDEX.
        verify(exactly = 2) { db.execSQL(capture(statements)) }

        val createTable = statements.first().uppercase()
        assertTrue(
            "Expected CREATE TABLE triggers, got: ${statements.first()}",
            createTable.contains("CREATE TABLE") && createTable.contains("TRIGGERS"),
        )
        listOf("ID", "NAME", "PIPELINEID", "PROMPT", "CONDITIONJSON", "ENABLED", "ARMED", "CREATEDAT", "LASTFIREDAT")
            .forEach { column ->
                assertTrue("Missing column $column in: ${statements.first()}", createTable.contains(column))
            }
        // armed defaults to 1 (a fresh trigger is ready to fire on the first edge).
        assertTrue(
            "armed must default to 1: ${statements.first()}",
            createTable.contains("`ARMED` INTEGER NOT NULL DEFAULT 1"),
        )
        assertTrue(
            "Primary key must be the trigger id: ${statements.first()}",
            createTable.contains("PRIMARY KEY(`ID`)"),
        )
        // A trigger may outlive its bound pipeline (the fire path auto-disables
        // it), so the column carries no foreign key.
        assertTrue(
            "triggers must not carry a pipeline foreign key: ${statements.first()}",
            !createTable.contains("FOREIGN KEY"),
        )

        val index = statements[1].uppercase()
        assertTrue(
            "Index name must match Room's generated name for the active-trigger query: ${statements[1]}",
            index.contains("INDEX_TRIGGERS_ENABLED"),
        )
    }

    @Test
    fun `MIGRATION_45_46 targets versions 45 to 46`() {
        val migration = AppDatabase.MIGRATION_45_46

        assertEquals(45, migration.startVersion)
        assertEquals(46, migration.endVersion)
    }

    @Test
    fun `MIGRATION_45_46 adds a nullable sessionId column to triggers`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_45_46.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER triggers ADD sessionId, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE `TRIGGERS` ADD COLUMN `SESSIONID`"),
        )
        // Additive + nullable so existing trigger rows keep NULL and lazily bind
        // a session on their next fire.
        assertTrue("sessionId must be nullable (no NOT NULL): ${sqlSlot.captured}", !sql.contains("NOT NULL"))
    }

    @Test
    fun `MIGRATION_46_47 targets versions 46 to 47`() {
        val migration = AppDatabase.MIGRATION_46_47

        assertEquals(46, migration.startVersion)
        assertEquals(47, migration.endVersion)
    }

    @Test
    fun `MIGRATION_46_47 creates the local usage-telemetry tables`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_46_47.migrate(db)

        // CREATE TABLE usage_counter + CREATE TABLE usage_active_day.
        verify(exactly = 2) { db.execSQL(capture(statements)) }

        val counterTable = statements.first().uppercase()
        assertTrue(
            "Expected CREATE TABLE usage_counter, got: ${statements.first()}",
            counterTable.contains("CREATE TABLE") && counterTable.contains("USAGE_COUNTER"),
        )
        listOf("CATEGORY", "COUNTERKEY", "COUNT").forEach { column ->
            assertTrue("Missing column $column in: ${statements.first()}", counterTable.contains(column))
        }
        assertTrue(
            "usage_counter must be keyed on (category, counterKey): ${statements.first()}",
            counterTable.contains("PRIMARY KEY(`CATEGORY`, `COUNTERKEY`)"),
        )

        val dayTable = statements[1].uppercase()
        assertTrue(
            "Expected CREATE TABLE usage_active_day, got: ${statements[1]}",
            dayTable.contains("CREATE TABLE") && dayTable.contains("USAGE_ACTIVE_DAY"),
        )
        assertTrue(
            "usage_active_day must be keyed on the day string: ${statements[1]}",
            dayTable.contains("PRIMARY KEY(`DAY`)"),
        )
    }

    @Test
    fun `MIGRATION_47_48 targets versions 47 to 48`() {
        val migration = AppDatabase.MIGRATION_47_48

        assertEquals(47, migration.startVersion)
        assertEquals(48, migration.endVersion)
    }

    @Test
    fun `MIGRATION_47_48 adds a nullable conditionHasImage column to pipeline_nodes`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_47_48.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER pipeline_nodes ADD conditionHasImage, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE `PIPELINE_NODES` ADD COLUMN `CONDITIONHASIMAGE`"),
        )
        // Additive + nullable (an already-shipped dev migration must not be tightened to
        // NOT NULL afterwards — that would crash Room's validation on devices already at v48).
        assertTrue("conditionHasImage must be nullable (no NOT NULL): ${sqlSlot.captured}", !sql.contains("NOT NULL"))
    }

    @Test
    fun `MIGRATION_48_49 targets versions 48 to 49`() {
        val migration = AppDatabase.MIGRATION_48_49

        assertEquals(48, migration.startVersion)
        assertEquals(49, migration.endVersion)
    }

    @Test
    fun `MIGRATION_48_49 adds a non-null hadImage column to pipeline_runs`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_48_49.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER pipeline_runs ADD hadImage, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE `PIPELINE_RUNS` ADD COLUMN `HADIMAGE`"),
        )
        // Additive + NOT NULL DEFAULT 0 so existing runs get false (no image).
        assertTrue("hadImage must be NOT NULL DEFAULT 0: ${sqlSlot.captured}", sql.contains("NOT NULL DEFAULT 0"))
    }

    @Test
    fun `MIGRATION_49_50 targets versions 49 to 50`() {
        val migration = AppDatabase.MIGRATION_49_50

        assertEquals(49, migration.startVersion)
        assertEquals(50, migration.endVersion)
    }

    @Test
    fun `MIGRATION_49_50 adds a non-null samplePrompts column to pipelines defaulting to empty array`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_49_50.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER pipelines ADD samplePrompts, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE `PIPELINES` ADD COLUMN `SAMPLEPROMPTS`"),
        )
        // Additive + NOT NULL so existing rows keep working; the '[]' default
        // reads back through Converters.toSamplePrompts as an empty list.
        assertTrue(
            "samplePrompts must be NOT NULL DEFAULT '[]': ${sqlSlot.captured}",
            sql.contains("NOT NULL DEFAULT '[]'"),
        )
        assertEquals(
            "The '[]' default must decode to an empty prompt list",
            emptyList<Any>(),
            Converters().toSamplePrompts("[]"),
        )
    }

    @Test
    fun `MIGRATION_50_51 targets versions 50 to 51`() {
        val migration = AppDatabase.MIGRATION_50_51

        assertEquals(50, migration.startVersion)
        assertEquals(51, migration.endVersion)
    }

    @Test
    fun `MIGRATION_50_51 creates the trigger_evaluations table with both indexes`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlots = mutableListOf<String>()

        AppDatabase.MIGRATION_50_51.migrate(db)

        // One CREATE TABLE plus the two index creations.
        verify(exactly = 3) { db.execSQL(capture(sqlSlots)) }
        val joined = sqlSlots.joinToString("\n").uppercase()
        assertTrue(
            "Expected CREATE TABLE trigger_evaluations, got: $sqlSlots",
            joined.contains("CREATE TABLE IF NOT EXISTS `TRIGGER_EVALUATIONS`"),
        )
        // Core columns and the primary key.
        listOf("`ID`", "`TRIGGERID`", "`EVALUATEDAT`", "`SOURCE`", "`VERDICTKIND`", "PRIMARY KEY(`ID`)").forEach {
            assertTrue("Expected column/PK $it in: $sqlSlots", joined.contains(it))
        }
        // The "by trigger, by time" composite index and the run-outcome index.
        assertTrue(
            "Expected (triggerId, evaluatedAt) index: $sqlSlots",
            joined.contains("INDEX_TRIGGER_EVALUATIONS_TRIGGERID_EVALUATEDAT"),
        )
        assertTrue(
            "Expected runId index: $sqlSlots",
            joined.contains("INDEX_TRIGGER_EVALUATIONS_RUNID"),
        )
    }

    @Test
    fun `MIGRATION_51_52 targets versions 51 to 52`() {
        val migration = AppDatabase.MIGRATION_51_52

        assertEquals(51, migration.startVersion)
        assertEquals(52, migration.endVersion)
    }

    @Test
    fun `MIGRATION_51_52 creates the onboarding_milestone table keyed by the marker`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_51_52.migrate(db)

        // Purely additive: one CREATE TABLE, no existing table touched.
        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected CREATE TABLE onboarding_milestone, got: ${sqlSlot.captured}",
            sql.contains("CREATE TABLE IF NOT EXISTS `ONBOARDING_MILESTONE`"),
        )
        // The marker key is the primary key — that is what backs the write-once
        // (INSERT OR IGNORE) semantics of the journey markers.
        assertTrue(
            "Marker key must be the primary key: ${sqlSlot.captured}",
            sql.contains("PRIMARY KEY(`MILESTONEKEY`)"),
        )
        assertTrue("Expected atMillis column: ${sqlSlot.captured}", sql.contains("`ATMILLIS` INTEGER NOT NULL"))
        // The payload is nullable: only SCENARIO_CHOSEN carries one.
        assertTrue("Expected nullable detail column: ${sqlSlot.captured}", sql.contains("`DETAIL` TEXT"))
    }

    @Test
    fun `MIGRATION_52_53 targets versions 52 to 53`() {
        val migration = AppDatabase.MIGRATION_52_53

        assertEquals(52, migration.startVersion)
        assertEquals(53, migration.endVersion)
    }

    @Test
    fun `MIGRATION_52_53 adds a nullable memoryRetrievalQuery column to pipelines`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_52_53.migrate(db)

        // Purely additive: one ALTER TABLE, no data rewrite.
        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER TABLE on pipelines, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE") && sql.contains("`PIPELINES`"),
        )
        assertTrue(
            "Expected new memoryRetrievalQuery column, got: ${sqlSlot.captured}",
            sql.contains("`MEMORYRETRIEVALQUERY` TEXT"),
        )
        // Nullable on purpose: "never declared" must stay distinguishable from
        // "declared blank", and existing pipelines upgrade to "declares nothing".
        assertTrue(
            "Column must stay nullable so existing rows mean 'not declared': ${sqlSlot.captured}",
            !sql.contains("NOT NULL"),
        )
    }

    @Test
    fun `MIGRATION_53_54 targets versions 53 to 54`() {
        val migration = AppDatabase.MIGRATION_53_54

        assertEquals(53, migration.startVersion)
        assertEquals(54, migration.endVersion)
    }

    @Test
    fun `MIGRATION_53_54 adds isArchived column to chat_sessions with default 0`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_53_54.migrate(db)

        // Purely additive: one ALTER TABLE, no data rewrite.
        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER TABLE on chat_sessions, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE") && sql.contains("`CHAT_SESSIONS`"),
        )
        assertTrue(
            "Expected new isArchived column, got: ${sqlSlot.captured}",
            sql.contains("`ISARCHIVED` INTEGER"),
        )
        // NOT NULL is safe here because the column is introduced on a NEW
        // schema version with a DEFAULT — every pre-existing session upgrades
        // to "not archived" and keeps appearing in the thread list.
        assertTrue(
            "Column must be NOT NULL so the flag is never ambiguous: ${sqlSlot.captured}",
            sql.contains("NOT NULL"),
        )
        assertTrue(
            "Existing rows must default to not-archived: ${sqlSlot.captured}",
            sql.contains("DEFAULT 0"),
        )
    }

    @Test
    fun `MIGRATION_54_55 targets versions 54 to 55`() {
        val migration = AppDatabase.MIGRATION_54_55

        assertEquals(54, migration.startVersion)
        assertEquals(55, migration.endVersion)
    }

    @Test
    fun `MIGRATION_54_55 adds nullable archivedAt column to chat_sessions`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_54_55.migrate(db)

        // Purely additive: one ALTER TABLE, no data rewrite.
        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected ALTER TABLE on chat_sessions, got: ${sqlSlot.captured}",
            sql.contains("ALTER TABLE") && sql.contains("`CHAT_SESSIONS`"),
        )
        assertTrue(
            "Expected new archivedAt column, got: ${sqlSlot.captured}",
            sql.contains("`ARCHIVEDAT` INTEGER"),
        )
        // Nullable on purpose: `null` means "not archived", so the flag and the
        // instant cannot drift into a never-archived row claiming the epoch.
        assertTrue(
            "Column must stay nullable so 'not archived' has no fake instant: ${sqlSlot.captured}",
            !sql.contains("NOT NULL"),
        )
    }

    @Test
    fun `MIGRATION_55_56 targets versions 55 to 56`() {
        val migration = AppDatabase.MIGRATION_55_56

        assertEquals(55, migration.startVersion)
        assertEquals(56, migration.endVersion)
    }

    @Test
    fun `MIGRATION_55_56 adds the four HITL columns to trigger_evaluations`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = mutableListOf<String>()

        AppDatabase.MIGRATION_55_56.migrate(db)

        // Purely additive: four ALTER TABLEs, no data rewrite.
        verify(exactly = 4) { db.execSQL(capture(sqlSlot)) }
        val statements = sqlSlot.map { it.uppercase() }
        assertTrue(
            "Every statement must be an ALTER TABLE on trigger_evaluations: $sqlSlot",
            statements.all { it.contains("ALTER TABLE") && it.contains("`TRIGGER_EVALUATIONS`") },
        )
        // The counter and the parked flag are NOT NULL with a default, so every
        // pre-v56 row reads back as "this run never asked" — which is what those
        // rows mean, nothing having recorded gates before.
        assertTrue(
            "Expected counted gates defaulting to none: $sqlSlot",
            statements.any { it.contains("`HITLGATECOUNT` INTEGER NOT NULL DEFAULT 0") },
        )
        assertTrue(
            "Expected parked flag defaulting to false: $sqlSlot",
            statements.any { it.contains("`HITLPARKED` INTEGER NOT NULL DEFAULT 0") },
        )
        // The two discriminators stay nullable: "no gate" must not be forced to
        // invent a kind or a resolution for itself.
        assertTrue(
            "Expected nullable kind column: $sqlSlot",
            statements.any { it.contains("`HITLLASTKIND` TEXT") && !it.contains("NOT NULL") },
        )
        assertTrue(
            "Expected nullable resolution column: $sqlSlot",
            statements.any { it.contains("`HITLLASTRESOLUTION` TEXT") && !it.contains("NOT NULL") },
        )
    }

    @Test
    fun `MIGRATION_56_57 targets versions 56 to 57`() {
        val migration = AppDatabase.MIGRATION_56_57

        assertEquals(56, migration.startVersion)
        assertEquals(57, migration.endVersion)
    }

    @Test
    fun `MIGRATION_56_57 creates the usage_pipeline_day set keyed by day and pipeline`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()

        AppDatabase.MIGRATION_56_57.migrate(db)

        // Purely additive: one CREATE TABLE, no existing table touched and no
        // back-fill (the historical counters carry no dates to back-fill from).
        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured.uppercase()
        assertTrue(
            "Expected CREATE TABLE usage_pipeline_day, got: ${sqlSlot.captured}",
            sql.contains("CREATE TABLE IF NOT EXISTS `USAGE_PIPELINE_DAY`"),
        )
        // Both columns form the primary key: that is what makes the table a set
        // of (day, pipeline) pairs rather than a second run counter.
        assertTrue(
            "Day and pipeline must form the composite primary key: ${sqlSlot.captured}",
            sql.contains("PRIMARY KEY(`DAY`, `PIPELINEID`)"),
        )
        assertTrue("Expected non-null day column: ${sqlSlot.captured}", sql.contains("`DAY` TEXT NOT NULL"))
        assertTrue(
            "Expected non-null pipelineId column: ${sqlSlot.captured}",
            sql.contains("`PIPELINEID` TEXT NOT NULL"),
        )
    }

    @Test
    fun `MIGRATION_57_58 targets versions 57 to 58`() {
        val migration = AppDatabase.MIGRATION_57_58

        assertEquals(57, migration.startVersion)
        assertEquals(58, migration.endVersion)
    }

    @Test
    fun `MIGRATION_57_58 creates the external-automation request journal with both indices`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        // A list, not a slot: `slot` keeps only the last capture, so an exact-count
        // assertion would silently inspect one statement and pass.
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_57_58.migrate(db)

        // Purely additive: one CREATE TABLE plus its two indices, no existing table
        // touched and no back-fill.
        verify(exactly = 3) { db.execSQL(capture(statements)) }
        val create = statements.first().uppercase()
        assertTrue(
            "Expected CREATE TABLE external_automation_requests, got: ${statements.first()}",
            create.contains("CREATE TABLE IF NOT EXISTS `EXTERNAL_AUTOMATION_REQUESTS`"),
        )
        assertTrue("Expected id as primary key: ${statements.first()}", create.contains("PRIMARY KEY(`ID`)"))
        // No foreign key on runId: the record must outlive the run it describes, and
        // most rows describe requests that never produced one.
        assertFalse("The journal must carry no foreign key: ${statements.first()}", create.contains("FOREIGN KEY"))
        listOf(
            "`REQUESTID` TEXT NOT NULL",
            "`RECEIVEDAT` INTEGER NOT NULL",
            "`ACTION` TEXT NOT NULL",
            "`RETURNACTION` TEXT NOT NULL",
            "`STATUSKIND` TEXT NOT NULL",
            "`REPEATCOUNT` INTEGER NOT NULL",
        ).forEach { column ->
            assertTrue("Expected column $column in: ${statements.first()}", create.contains(column))
        }
        // Nullable by design — a refusal may carry no target, no callback address
        // and no run.
        listOf("`TARGETKIND` TEXT,", "`TARGETVALUE` TEXT,", "`RUNID` TEXT,").forEach { column ->
            assertTrue("Expected nullable column $column in: ${statements.first()}", create.contains(column))
        }
        // Index names must be spelled exactly as Room generates them, or the
        // exported-schema validation rejects the migrated database.
        assertTrue(
            "Expected the receivedAt index: ${statements[1]}",
            statements[1].contains("`index_external_automation_requests_receivedAt`"),
        )
        assertTrue(
            "Expected the runId index: ${statements[2]}",
            statements[2].contains("`index_external_automation_requests_runId`"),
        )
    }

    @Test
    fun `MIGRATION_59_60 targets versions 59 to 60`() {
        val migration = AppDatabase.MIGRATION_59_60

        assertEquals(59, migration.startVersion)
        assertEquals(60, migration.endVersion)
    }

    @Test
    fun `MIGRATION_59_60 adds five nullable per-node columns and back-fills nothing`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_59_60.migrate(db)

        verify(exactly = 5) { db.execSQL(capture(statements)) }
        listOf(
            "`fallbackClass` TEXT",
            "`quickReplies` TEXT",
            "`alwaysConfirm` INTEGER",
            "`maxSubtasks` INTEGER",
            "`stopOnError` INTEGER",
        ).forEachIndexed { index, column ->
            val sql = statements[index]
            assertTrue("Expected an ALTER on pipeline_nodes, got: $sql", sql.contains("ALTER TABLE `pipeline_nodes`"))
            assertTrue("Expected column $column in: $sql", sql.contains(column))
            // Every column has to be nullable with no default. `null` is what
            // makes each of the five behave exactly as it did before this
            // migration, which is the whole reason an existing pipeline can
            // cross it without changing how it runs. A NOT NULL DEFAULT would
            // silently declare a decision for every node ever saved.
            assertFalse("Column $column must be nullable: $sql", sql.uppercase().contains("NOT NULL"))
            assertFalse("Column $column must carry no default: $sql", sql.uppercase().contains("DEFAULT"))
        }
    }

    @Test
    fun `MIGRATION_60_61 targets versions 60 to 61`() {
        val migration = AppDatabase.MIGRATION_60_61

        assertEquals(60, migration.startVersion)
        assertEquals(61, migration.endVersion)
    }

    @Test
    fun `MIGRATION_60_61 adds the grant counters NOT NULL and the pause description nullable`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_60_61.migrate(db)

        verify(exactly = 5) { db.execSQL(capture(statements)) }

        // The two grant counters must be NOT NULL DEFAULT 0, matching the
        // `@ColumnInfo(defaultValue = "0")` on the entity *exactly* — Room's
        // TableInfo comparison sees both sides and rejects the schema at open
        // if they disagree. Semantically: every existing run has been granted
        // nothing, and that is a number, not an absence.
        listOf("`stepCeilingExtensions`", "`tokenCeilingExtensions`").forEachIndexed { index, column ->
            val sql = statements[index]
            assertTrue("Expected an ALTER on pipeline_runs, got: $sql", sql.contains("ALTER TABLE `pipeline_runs`"))
            assertTrue("Expected column $column in: $sql", sql.contains(column))
            assertTrue("$column must be NOT NULL: $sql", sql.uppercase().contains("NOT NULL"))
            assertTrue("$column must default to 0: $sql", sql.contains("DEFAULT 0"))
        }

        // The pause description is the opposite case: the other two kinds of
        // park have no ceiling to describe, and every row written before this
        // migration is one of them. A NOT NULL column would have to invent an
        // axis and two numbers for each of them.
        listOf("`ceilingAxis` TEXT", "`ceilingLimit` INTEGER", "`ceilingSpent` INTEGER")
            .forEachIndexed { index, column ->
                val sql = statements[index + 2]
                assertTrue(
                    "Expected an ALTER on pending_interactions, got: $sql",
                    sql.contains("ALTER TABLE `pending_interactions`"),
                )
                assertTrue("Expected column $column in: $sql", sql.contains(column))
                assertFalse("Column $column must be nullable: $sql", sql.uppercase().contains("NOT NULL"))
            }
    }

    @Test
    fun `MIGRATION_61_62 targets versions 61 to 62`() {
        val migration = AppDatabase.MIGRATION_61_62

        assertEquals(61, migration.startVersion)
        assertEquals(62, migration.endVersion)
    }

    @Test
    fun `MIGRATION_61_62 adds a nullable request id and back-fills approval rows with their run id`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statements = mutableListOf<String>()

        AppDatabase.MIGRATION_61_62.migrate(db)

        verify(exactly = 2) { db.execSQL(capture(statements)) }
        val (alter, backfill) = statements
        // Nullable with no default, matching the entity: clarifications and
        // ceiling pauses have no approval request to name.
        assertTrue(alter, alter.contains("ALTER TABLE `pending_interactions` ADD COLUMN `requestId` TEXT"))
        assertFalse(alter, alter.uppercase().contains("NOT NULL"))
        assertFalse(alter, alter.uppercase().contains("DEFAULT"))
        // The back-fill is what keeps a request that was waiting across the
        // update answerable: its notification names only the run.
        assertTrue(backfill, backfill.contains("UPDATE `pending_interactions` SET `requestId` = `runId`"))
        assertTrue(backfill, backfill.contains("WHERE `kind` = 'APPROVAL'"))
    }

    @Test
    fun `MIGRATION_62_63 targets versions 62 to 63`() {
        val migration = AppDatabase.MIGRATION_62_63

        assertEquals(62, migration.startVersion)
        assertEquals(63, migration.endVersion)
    }

    @Test
    fun `MIGRATION_62_63 adds the imported flag NOT NULL defaulting to written-on-this-device`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statement = slot<String>()

        AppDatabase.MIGRATION_62_63.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(statement)) }
        // NOT NULL with DEFAULT 0 must match the entity's `@ColumnInfo(defaultValue = "0")`
        // or Room's schema validation rejects the migrated database.
        assertEquals(
            "ALTER TABLE `chat_messages` ADD COLUMN `imported` INTEGER NOT NULL DEFAULT 0",
            statement.captured,
        )
    }

    @Test
    fun `MIGRATION_63_64 targets versions 63 to 64`() {
        val migration = AppDatabase.MIGRATION_63_64

        assertEquals(63, migration.startVersion)
        assertEquals(64, migration.endVersion)
    }

    @Test
    fun `MIGRATION_63_64 creates the background prompts table exactly as the entity declares it`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statement = slot<String>()

        AppDatabase.MIGRATION_63_64.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(statement)) }
        // Must match the exported v64 schema's createSql, or Room's validation
        // rejects the migrated database.
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `background_prompts` " +
                "(`id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            statement.captured,
        )
    }

    @Test
    fun `MIGRATION_64_65 targets versions 64 to 65`() {
        val migration = AppDatabase.MIGRATION_64_65

        assertEquals(64, migration.startVersion)
        assertEquals(65, migration.endVersion)
    }

    @Test
    fun `MIGRATION_64_65 adds the relayed flag NOT NULL defaulting to not relayed`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val statement = slot<String>()

        AppDatabase.MIGRATION_64_65.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(statement)) }
        // NOT NULL with DEFAULT 0 must match the entity's `@ColumnInfo(defaultValue = "0")`
        // or Room's schema validation rejects the migrated database.
        assertEquals(
            "ALTER TABLE `chat_messages` ADD COLUMN `relayed` INTEGER NOT NULL DEFAULT 0",
            statement.captured,
        )
    }
}
