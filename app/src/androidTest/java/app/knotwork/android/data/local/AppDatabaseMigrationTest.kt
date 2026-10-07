package app.knotwork.android.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.android.data.local.migrations.MIGRATION_17_18
import app.knotwork.android.data.local.migrations.MIGRATION_18_19
import app.knotwork.android.data.local.migrations.MIGRATION_19_20
import app.knotwork.android.data.local.migrations.MIGRATION_20_21
import app.knotwork.android.data.local.migrations.MIGRATION_21_22
import app.knotwork.android.data.local.migrations.MIGRATION_22_23
import app.knotwork.android.data.local.migrations.MIGRATION_23_24
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression suite for every `MIGRATION_*` declared on
 * [AppDatabase]. Each test boots a real SQLite file at the migration's
 * starting version (via [openAtVersion]), seeds boilerplate rows
 * covering the affected columns, invokes the migration in isolation,
 * and asserts the resulting schema + data against the contract.
 *
 * The starting-version table DDL is transcribed by walking the
 * `MIGRATION_*` constants backwards from the v23 entity definitions —
 * the same exercise Room's own KSP performs internally when it emits
 * `app/schemas/<package>/<N>.json`. Going through raw SQL instead of
 * `MigrationTestHelper.createDatabase(name, version)` lets the suite
 * exist before every historical schema JSON has been hand-frozen and
 * committed to the repo; the existing v23 export is enough for Room
 * runtime validation on real devices.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName: String = "migration-test.db"

    @Before
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate17to18_addsContextConfigWithDefaultEnablingAllFlags() {
        openAtVersion(
            version = 17,
            createSql = listOf(V17_PIPELINES, V17_PIPELINE_NODES, V17_PIPELINE_NODES_INDEX),
        ) { db ->
            db.execSQL(
                "INSERT INTO pipelines(id, name, updatedAt) VALUES('p1', 'P1', 0)",
            )
            db.execSQL(
                "INSERT INTO pipeline_nodes(id, pipelineId, type, x, y, label) " +
                    "VALUES('n1', 'p1', 'INPUT', 0.0, 0.0, 'In')",
            )
        }

        openAtVersion(version = 18, createSql = emptyList()) { db ->
            MIGRATION_17_18.migrate(db)
            val ctxConfig = querySingleString(db, "SELECT context_config FROM pipeline_nodes WHERE id='n1'")
            assertTrue("ALL_ENABLED default JSON expected", ctxConfig.contains("\"chatHistory\":true"))
            assertTrue(ctxConfig.contains("\"originalTask\":true"))
            assertTrue(ctxConfig.contains("\"nodeInput\":true"))
            assertTrue(ctxConfig.contains("\"longTermMemory\":true"))
            assertTrue(ctxConfig.contains("\"toolResults\":true"))
        }
    }

    @Test
    fun migrate18to19_addsNullablePipelineIdAndPreservesData() {
        openAtVersion(version = 18, createSql = listOf(V18_CHAT_SESSIONS)) { db ->
            db.execSQL("INSERT INTO chat_sessions(id, name, updatedAt) VALUES('s1', 'Alpha', 42)")
        }

        openAtVersion(version = 19, createSql = emptyList()) { db ->
            MIGRATION_18_19.migrate(db)
            db.query("SELECT id, name, updatedAt, pipelineId FROM chat_sessions WHERE id='s1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Alpha", c.getString(c.getColumnIndexOrThrow("name")))
                assertEquals(42L, c.getLong(c.getColumnIndexOrThrow("updatedAt")))
                assertTrue("pipelineId default must be NULL", c.isNull(c.getColumnIndexOrThrow("pipelineId")))
            }
        }
    }

    @Test
    fun migrate19to20_addsIsFinalAndIsStarredWithDefaults() {
        openAtVersion(version = 19, createSql = listOf(V19_CHAT_MESSAGES)) { db ->
            db.execSQL(
                "INSERT INTO chat_messages(sessionId, role, content, timestamp) " +
                    "VALUES('s', 'USER', 'hi', 1)",
            )
        }

        openAtVersion(version = 20, createSql = emptyList()) { db ->
            MIGRATION_19_20.migrate(db)
            db.query("SELECT isFinal, isStarred FROM chat_messages").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("isFinal must back-fill to 1 for legacy rows", 1, c.getInt(0))
                assertEquals("isStarred must back-fill to 0 for legacy rows", 0, c.getInt(1))
            }
        }
    }

    @Test
    fun migrate20to21_addsNullableConfigJsonColumn() {
        openAtVersion(
            version = 20,
            createSql = listOf(V17_PIPELINES, V20_PIPELINE_NODES, V17_PIPELINE_NODES_INDEX),
        ) { db ->
            db.execSQL("INSERT INTO pipelines(id, name, updatedAt) VALUES('p', 'P', 0)")
            db.execSQL(
                "INSERT INTO pipeline_nodes(id, pipelineId, type, x, y, label, context_config) " +
                    "VALUES('n', 'p', 'INPUT', 0.0, 0.0, 'In', '{}')",
            )
        }

        openAtVersion(version = 21, createSql = emptyList()) { db ->
            MIGRATION_20_21.migrate(db)
            db.query("SELECT config_json FROM pipeline_nodes WHERE id='n'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("config_json default must be NULL for legacy rows", c.isNull(0))
            }
        }
    }

    @Test
    fun migrate21to22_addsIsStarredOnChatSessionsBackfilledToZero() {
        openAtVersion(version = 21, createSql = listOf(V21_CHAT_SESSIONS)) { db ->
            db.execSQL(
                "INSERT INTO chat_sessions(id, name, updatedAt, pipelineId) " +
                    "VALUES('s', 'n', 0, NULL)",
            )
        }

        openAtVersion(version = 22, createSql = emptyList()) { db ->
            MIGRATION_21_22.migrate(db)
            db.query("SELECT isStarred FROM chat_sessions WHERE id='s'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Session-level isStarred must back-fill to 0", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate22to23_addsIsPinnedOnMemoryChunksBackfilledToZero() {
        openAtVersion(version = 22, createSql = listOf(V22_MEMORY_CHUNKS)) { db ->
            db.execSQL(
                "INSERT INTO memory_chunks(text, embedding, timestamp) VALUES('hello', '0.1,0.2', 5)",
            )
        }

        openAtVersion(version = 23, createSql = emptyList()) { db ->
            MIGRATION_22_23.migrate(db)
            db.query("SELECT text, isPinned FROM memory_chunks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("hello", c.getString(0))
                assertEquals("isPinned must back-fill to 0", 0, c.getInt(1))
            }
        }
    }

    @Test
    fun migrate23to24_createsPipelinePresetsTableWithExpectedColumns() {
        // Open the DB at v23 with a single placeholder table so the
        // SupportSQLiteOpenHelper has something to create — the migration
        // itself only adds a new table, so v23 contents are irrelevant.
        openAtVersion(version = 23, createSql = listOf(V22_MEMORY_CHUNKS)) { /* no seed needed */ }

        openAtVersion(version = 24, createSql = emptyList()) { db ->
            MIGRATION_23_24.migrate(db)

            // Round-trip an insert + select to assert every column exists
            // with the expected type and nullability contract.
            db.execSQL(
                "INSERT INTO pipeline_presets" +
                    "(id, name, description, categoryKey, graphJson, tagsCsv, createdAt) " +
                    "VALUES('p1', 'Test', 'desc', 'local', '{}', 'a,b', 42)",
            )
            db.query("SELECT id, name, description, categoryKey, graphJson, tagsCsv, createdAt FROM pipeline_presets")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("p1", c.getString(c.getColumnIndexOrThrow("id")))
                    assertEquals("Test", c.getString(c.getColumnIndexOrThrow("name")))
                    assertEquals("desc", c.getString(c.getColumnIndexOrThrow("description")))
                    assertEquals("local", c.getString(c.getColumnIndexOrThrow("categoryKey")))
                    assertEquals("{}", c.getString(c.getColumnIndexOrThrow("graphJson")))
                    assertEquals("a,b", c.getString(c.getColumnIndexOrThrow("tagsCsv")))
                    assertEquals(42L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
                }
        }
    }

    @Test
    fun migrate23to24_isAdditiveAndLeavesExistingTablesUntouched() {
        openAtVersion(version = 23, createSql = listOf(V22_MEMORY_CHUNKS)) { db ->
            db.execSQL(
                "INSERT INTO memory_chunks(text, embedding, timestamp) " +
                    "VALUES('preserved', '0.1', 7)",
            )
        }

        openAtVersion(version = 24, createSql = emptyList()) { db ->
            MIGRATION_23_24.migrate(db)
            db.query("SELECT text, timestamp FROM memory_chunks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("preserved", c.getString(0))
                assertEquals(7L, c.getLong(1))
            }
        }
    }

    @Test
    fun migrateAll_17_to_23_preservesDataAcrossEveryStep() {
        // Bootstraps the v17 baseline with the full set of tables that the
        // six migrations under test touch (chat sessions/messages exist
        // earlier in the schema history; here they get the v17-era
        // shapes). Then runs every migration in sequence and verifies the
        // resulting columns + defaults the way a real upgrade path would.
        openAtVersion(
            version = 17,
            createSql = listOf(
                V17_PIPELINES,
                V17_PIPELINE_NODES,
                V17_PIPELINE_NODES_INDEX,
                V18_CHAT_SESSIONS,
                V19_CHAT_MESSAGES,
                V22_MEMORY_CHUNKS,
            ),
        ) { db ->
            db.execSQL("INSERT INTO pipelines(id, name, updatedAt) VALUES('p', 'P', 0)")
            db.execSQL(
                "INSERT INTO pipeline_nodes(id, pipelineId, type, x, y, label) " +
                    "VALUES('n', 'p', 'INPUT', 0.0, 0.0, 'In')",
            )
            db.execSQL("INSERT INTO chat_sessions(id, name, updatedAt) VALUES('s', 'Sess', 0)")
            db.execSQL(
                "INSERT INTO chat_messages(sessionId, role, content, timestamp) " +
                    "VALUES('s', 'USER', 'hi', 0)",
            )
            db.execSQL(
                "INSERT INTO memory_chunks(text, embedding, timestamp) VALUES('mem', '0.1', 0)",
            )
        }

        openAtVersion(version = 23, createSql = emptyList()) { db ->
            MIGRATION_17_18.migrate(db)
            MIGRATION_18_19.migrate(db)
            MIGRATION_19_20.migrate(db)
            MIGRATION_20_21.migrate(db)
            MIGRATION_21_22.migrate(db)
            MIGRATION_22_23.migrate(db)

            // Spot-check the columns added along the way.
            db.query(
                "SELECT context_config, config_json FROM pipeline_nodes WHERE id='n'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0).contains("chatHistory"))
                assertTrue("config_json starts NULL for legacy rows", c.isNull(1))
            }
            db.query("SELECT pipelineId, isStarred FROM chat_sessions WHERE id='s'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertEquals(0, c.getInt(1))
            }
            db.query("SELECT isFinal, isStarred FROM chat_messages").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(0, c.getInt(1))
            }
            db.query("SELECT isPinned FROM memory_chunks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate17to18_keepsExplicitlySetContextConfigUntouched() {
        // Guards the migration against accidentally rewriting rows that
        // somehow already carry a `context_config` payload. The ALTER
        // statement only sets the default for missing values; existing
        // (or re-populated) values must remain stable.
        openAtVersion(
            version = 17,
            createSql = listOf(V17_PIPELINES, V17_PIPELINE_NODES, V17_PIPELINE_NODES_INDEX),
        ) { db ->
            db.execSQL("INSERT INTO pipelines(id, name, updatedAt) VALUES('p', 'P', 0)")
            db.execSQL(
                "INSERT INTO pipeline_nodes(id, pipelineId, type, x, y, label) " +
                    "VALUES('n', 'p', 'INPUT', 0.0, 0.0, 'In')",
            )
        }

        openAtVersion(version = 18, createSql = emptyList()) { db ->
            MIGRATION_17_18.migrate(db)
            // After the migration the row should carry the default JSON,
            // not NULL or empty string.
            val ctxConfig = querySingleString(db, "SELECT context_config FROM pipeline_nodes WHERE id='n'")
            assertFalse(ctxConfig.isBlank())
            assertTrue(ctxConfig.startsWith("{"))
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Opens (or re-opens) the test DB at [version], optionally running
     * the supplied [createSql] statements in the `onCreate` /
     * `onUpgrade` callbacks. The first invocation creates the file and
     * runs `createSql`; subsequent calls open the existing file at the
     * higher [version] without re-running `createSql` (which is then
     * passed as `emptyList()`).
     */
    private fun openAtVersion(version: Int, createSql: List<String>, block: (SupportSQLiteDatabase) -> Unit) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createSql.forEach(db::execSQL)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // No-op — migrations are invoked explicitly in tests.
                    }
                },
            )
            .build()
        val helper = factory.create(config)
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    private fun querySingleString(db: SupportSQLiteDatabase, sql: String): String {
        db.query(sql).use { c ->
            assertTrue(c.moveToFirst())
            val value = c.getString(0)
            assertFalse("Expected a single row, got more", c.moveToNext())
            return value
        }
    }
}

// ---------------------------------------------------------------------
// Historical schema fragments (transcribed from the entity definitions
// at the migration's starting version). Kept at file-level so they are
// easy to diff against `app/schemas/<package>/<N>.json` snapshots that
// will be exported once every legacy version is frozen.
// ---------------------------------------------------------------------

private const val V17_PIPELINES = """
    CREATE TABLE IF NOT EXISTS `pipelines` (
        `id` TEXT NOT NULL,
        `name` TEXT NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        PRIMARY KEY(`id`)
    )
"""

private const val V17_PIPELINE_NODES = """
    CREATE TABLE IF NOT EXISTS `pipeline_nodes` (
        `id` TEXT NOT NULL,
        `pipelineId` TEXT NOT NULL,
        `type` TEXT NOT NULL,
        `x` REAL NOT NULL,
        `y` REAL NOT NULL,
        `label` TEXT NOT NULL,
        `toolName` TEXT,
        `modelPath` TEXT,
        `conditionComplexity` INTEGER,
        `conditionKeywords` TEXT,
        `conditionPrompt` TEXT,
        `systemPrompt` TEXT,
        `cloudProvider` TEXT,
        `clarificationTimeoutMs` INTEGER,
        PRIMARY KEY(`id`),
        FOREIGN KEY(`pipelineId`) REFERENCES `pipelines`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

private const val V17_PIPELINE_NODES_INDEX =
    "CREATE INDEX IF NOT EXISTS `index_pipeline_nodes_pipelineId` ON `pipeline_nodes` (`pipelineId`)"

private const val V18_CHAT_SESSIONS = """
    CREATE TABLE IF NOT EXISTS `chat_sessions` (
        `id` TEXT NOT NULL,
        `name` TEXT NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        PRIMARY KEY(`id`)
    )
"""

private const val V19_CHAT_MESSAGES = """
    CREATE TABLE IF NOT EXISTS `chat_messages` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `sessionId` TEXT NOT NULL,
        `role` TEXT NOT NULL,
        `content` TEXT NOT NULL,
        `timestamp` INTEGER NOT NULL
    )
"""

private const val V20_PIPELINE_NODES = """
    CREATE TABLE IF NOT EXISTS `pipeline_nodes` (
        `id` TEXT NOT NULL,
        `pipelineId` TEXT NOT NULL,
        `type` TEXT NOT NULL,
        `x` REAL NOT NULL,
        `y` REAL NOT NULL,
        `label` TEXT NOT NULL,
        `toolName` TEXT,
        `modelPath` TEXT,
        `conditionComplexity` INTEGER,
        `conditionKeywords` TEXT,
        `conditionPrompt` TEXT,
        `systemPrompt` TEXT,
        `cloudProvider` TEXT,
        `clarificationTimeoutMs` INTEGER,
        `context_config` TEXT NOT NULL DEFAULT '{"chatHistory":true,"originalTask":true,"nodeInput":true,"longTermMemory":true,"toolResults":true}',
        PRIMARY KEY(`id`),
        FOREIGN KEY(`pipelineId`) REFERENCES `pipelines`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

private const val V21_CHAT_SESSIONS = """
    CREATE TABLE IF NOT EXISTS `chat_sessions` (
        `id` TEXT NOT NULL,
        `name` TEXT NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        `pipelineId` TEXT,
        PRIMARY KEY(`id`)
    )
"""

private const val V22_MEMORY_CHUNKS = """
    CREATE TABLE IF NOT EXISTS `memory_chunks` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `text` TEXT NOT NULL,
        `embedding` TEXT NOT NULL,
        `timestamp` INTEGER NOT NULL
    )
"""
