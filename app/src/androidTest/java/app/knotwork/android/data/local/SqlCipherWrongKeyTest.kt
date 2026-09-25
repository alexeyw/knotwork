package app.knotwork.android.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.domain.models.DbPassphraseUnavailableException
import io.mockk.every
import io.mockk.mockk
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recovery screen's "wrong key" route, against the real SQLCipher library.
 *
 * [DeferredPassphraseOpenHelperFactory] recognises a database keyed with another
 * passphrase by the text of SQLCipher's error (`file is not a database`) and turns it
 * into [DbPassphraseUnavailableException] with reason `KEY_MISMATCH`, which sends the
 * user to Retry / Erase data. The unit test feeds that text in by hand, so a SQLCipher
 * release that words the failure differently — or fails later, on the first read, instead
 * of at open — would leave every JVM test green while the app showed a generic error.
 * SQLCipher 4.19 changed its handling of initialisation errors and permanent error states;
 * this runs the native library on a device, where nothing else does.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherWrongKeyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun givenDatabaseKeyedWithAnotherPassphrase_whenOpened_thenKeyMismatch() {
        helperKeyedWith(KEY_A).use { it.writableDatabase.execSQL("INSERT INTO t VALUES (1)") }

        val error = assertThrows(DbPassphraseUnavailableException::class.java) {
            helperKeyedWith(KEY_B).use { it.writableDatabase }
        }

        assertEquals(DbPassphraseUnavailableException.Reason.KEY_MISMATCH, error.reason)
    }

    @Test
    fun givenDatabaseKeyedWithTheSamePassphrase_whenReopened_thenRowsRead() {
        helperKeyedWith(KEY_A).use { it.writableDatabase.execSQL("INSERT INTO t VALUES (1)") }

        val rows = helperKeyedWith(KEY_A).use { helper ->
            helper.readableDatabase.query("SELECT COUNT(*) FROM t").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

        assertEquals(1, rows)
    }

    /** A helper built the way the app builds it, with [key] as the stored passphrase. */
    private fun helperKeyedWith(key: ByteArray): SupportSQLiteOpenHelper {
        val passphrases = mockk<EncryptedDbPassphraseProvider> {
            every { getOrCreatePassphrase() } answers { key.copyOf() }
        }
        val factory = DeferredPassphraseOpenHelperFactory(passphrases) { passphrase ->
            SupportOpenHelperFactory(passphrase)
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE t (x INTEGER)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        return factory.create(configuration)
    }

    private companion object {
        const val DB_NAME = "sqlcipher-wrong-key-test.db"
        val KEY_A = ByteArray(32) { it.toByte() }
        val KEY_B = ByteArray(32) { (it + 1).toByte() }
    }
}
