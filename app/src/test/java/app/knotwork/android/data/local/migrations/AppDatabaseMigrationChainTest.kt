package app.knotwork.android.data.local.migrations

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards [ALL_MIGRATIONS], the one list of schema migrations the database builder registers.
 *
 * **Why.** A migration reaches the app only through this list. A step written but not listed,
 * or a schema version bumped without a step, builds and passes every test that opens a fresh
 * database — and then fails the upgrade on a device that holds data. The instrumented
 * `AppDatabaseMigrationHelperTest` runs the list end to end, but only on the emulator matrix;
 * this test answers on the JVM, in `check`.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationChainTest {

    @Test
    fun `given the registered list when walked then every step starts where the previous one ended`() {
        // When
        val gaps = ALL_MIGRATIONS.zipWithNext()
            .filter { (previous, next) -> previous.endVersion != next.startVersion }
            .map { (previous, next) ->
                "${previous.startVersion}->${previous.endVersion} then ${next.startVersion}->${next.endVersion}"
            }

        // Then
        assertTrue("ALL_MIGRATIONS has gaps or is out of order: $gaps", gaps.isEmpty())
    }

    @Test
    fun `given the registered list when read then it starts at the oldest version the app upgrades from`() {
        // Then
        assertEquals(OLDEST_UPGRADABLE_VERSION, ALL_MIGRATIONS.first().startVersion)
    }

    @Test
    fun `given the database Room builds when opened then the registered list ends at its version`() {
        // Given
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // When
        val version = try {
            database.openHelper.writableDatabase.version
        } finally {
            database.close()
        }

        // Then
        assertEquals(
            "the schema version and the last registered migration disagree: add the step's file and " +
                "append it to ALL_MIGRATIONS",
            version,
            ALL_MIGRATIONS.last().endVersion,
        )
    }

    private companion object {
        /** The first version a released database can be upgraded from: the start of the explicit chain. */
        const val OLDEST_UPGRADABLE_VERSION = 9
    }
}
