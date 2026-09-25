package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.data.local.crypto.AeadCipher
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import app.knotwork.android.data.local.crypto.SecureValueUnreadableException
import app.knotwork.android.domain.models.DbPassphraseUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stable passphrase used to encrypt the application's Room database via SQLCipher.
 *
 * The passphrase is a random 32-byte value generated on first access and persisted inside a
 * [KeystoreBackedPrefsStore] — values encrypted with AES-GCM under a dedicated, non-exportable
 * Android Keystore key. All subsequent calls return the same value, so the database can be
 * reopened across process restarts without user interaction.
 *
 * **Loss-protection invariant.** A new passphrase is generated **only** when no database file
 * exists yet (fresh install or post-wipe). Once the encrypted database has been created, the
 * stored passphrase is the only key that can ever open it — so any failure to read it back
 * (entry missing, entry malformed, authenticated decryption failing) throws
 * [DbPassphraseUnavailableException] instead of regenerating. Android Keystore failures are
 * frequently transient (backup/restore, OS update, TEE hiccup); throwing keeps the encrypted
 * database intact so a later retry can still open it, whereas silent regeneration would make
 * it permanently unreadable and destroy all user data. While no database file exists the
 * self-heal applies: an undecryptable store is destroyed and recreated, because there is
 * nothing a fresh secret could orphan.
 *
 * This is deliberately the **opposite** recovery semantics of [ApiKeyManager]: API keys can be
 * re-entered by the user at any time, so that store treats unreadable values as absent. The
 * database passphrase cannot be re-derived from anything, so this provider never does while a
 * database exists.
 *
 * **Legacy store.** Earlier releases kept the passphrase in `EncryptedSharedPreferences`
 * (deprecated upstream and removed from this project without a data migration, as permitted by
 * the pre-release storage policy). A leftover legacy file is deleted only when a *fresh*
 * passphrase is generated or on the user-confirmed wipe — never on a failure path, so
 * downgrading the APK remains a manual escape hatch for opening a pre-migration database.
 *
 * Byte arrays are used (instead of strings) because SQLCipher's `SupportOpenHelperFactory`
 * consumes a `byte[]`; the returned array is always a fresh copy so the caller can let the
 * library retain it (sqlcipher-android keeps the key to open pooled connections) without
 * aliasing the stored value.
 *
 * All public entry points are synchronized on a single lock: passphrase generation must never
 * race the user-confirmed wipe ([resetStoredPassphrase]), otherwise a concurrently generated
 * key could be deleted right after a fresh database was created with it.
 *
 * @property context The application context used to back the underlying preferences file.
 * @property cipher The AEAD boundary used to protect the stored passphrase.
 */
@Singleton
class EncryptedDbPassphraseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    cipher: AeadCipher,
) {

    private val lock = Any()

    private val store = KeystoreBackedPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        keyAlias = KEY_ALIAS,
        cipher = cipher,
    )

    /**
     * Returns the persisted database passphrase, generating and storing a new one **only**
     * when no encrypted database file exists yet.
     *
     * The returned [ByteArray] is a freshly allocated copy: handing it to SQLCipher's factory
     * never aliases the stored value.
     *
     * @return A 32-byte random passphrase.
     * @throws DbPassphraseUnavailableException When the database file already exists but the
     *   stored passphrase cannot be read back (see class KDoc for the invariant rationale).
     */
    fun getOrCreatePassphrase(): ByteArray = synchronized(lock) {
        val existingHex = readStoredHexOrNull()
        if (existingHex != null) {
            val decoded = decodeHexOrNull(existingHex)
            if (decoded != null && decoded.size == PASSPHRASE_BYTE_LENGTH) {
                return decoded
            }
        }

        if (databaseExists()) {
            // The encrypted DB is on disk but its key is gone or unreadable. Regenerating
            // would orphan the database forever — surface the condition instead.
            val reason = if (existingHex == null) {
                DbPassphraseUnavailableException.Reason.PASSPHRASE_MISSING
            } else {
                DbPassphraseUnavailableException.Reason.PASSPHRASE_MALFORMED
            }
            Timber.e("DB passphrase unavailable (%s) while database file exists; refusing to regenerate.", reason)
            throw DbPassphraseUnavailableException(reason)
        }

        if (existingHex != null) {
            Timber.w("Stored DB passphrase is malformed but no database exists yet; regenerating.")
        }
        val generated = ByteArray(PASSPHRASE_BYTE_LENGTH).also { SecureRandom().nextBytes(it) }
        // synchronous = true forces a synchronous commit: a freshly generated passphrase must
        // hit disk before it is ever used to open the DB, otherwise a process crash between
        // creating the encrypted DB and flushing the prefs would leave the DB permanently
        // unreadable.
        store.putString(PASSPHRASE_KEY, encodeHex(generated), synchronous = true)
        // A fresh passphrase begins a fresh data lifetime — the legacy ESP file (if any) no
        // longer guards anything this install can use.
        deleteLegacyStoreFile()
        return generated.copyOf()
    }

    /**
     * Destroys the passphrase store (entries, backing file, and Keystore key) along with any
     * legacy `EncryptedSharedPreferences` leftovers. Called **only** from the explicit
     * user-confirmed full data wipe
     * ([app.knotwork.android.domain.services.DatabaseResetService]) — the database file must be
     * deleted in the same operation, otherwise the loss-protection invariant in
     * [getOrCreatePassphrase] will refuse the next generation attempt.
     */
    fun resetStoredPassphrase(): Unit = synchronized(lock) {
        store.destroy()
        deleteLegacyStoreFile()
    }

    /**
     * Reads the stored hex entry, applying the recovery policy for undecryptable values:
     * with a database on disk the failure is surfaced (a retry after a transient Keystore
     * hiccup may still succeed — the stored blob is left untouched); with no database the
     * store is destroyed and `null` is returned so a fresh passphrase gets generated.
     */
    private fun readStoredHexOrNull(): String? = try {
        store.getString(PASSPHRASE_KEY)
    } catch (e: SecureValueUnreadableException) {
        if (databaseExists()) {
            Timber.e(e, "Failed to decrypt the stored DB passphrase while a database exists.")
            throw DbPassphraseUnavailableException(
                reason = DbPassphraseUnavailableException.Reason.DECRYPTION_FAILED,
                cause = e,
            )
        }
        Timber.w(e, "DB passphrase store unreadable but no database exists; recreating the store.")
        store.destroy()
        null
    }

    private fun databaseExists(): Boolean = context.getDatabasePath(AppDatabase.DATABASE_NAME).exists()

    /**
     * Removes the legacy `EncryptedSharedPreferences` file from pre-Keystore-wrapper releases.
     * Reached only from the fresh-generation and explicit-wipe paths; failure paths keep the
     * file so a downgraded APK can still open a pre-migration database.
     */
    private fun deleteLegacyStoreFile() {
        try {
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        } catch (e: Exception) {
            Timber.w(e, "deleteSharedPreferences failed; falling back to direct file removal.")
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val file = File(dir, "$LEGACY_PREFS_NAME.xml")
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun encodeHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and BYTE_MASK))
        }
        return sb.toString()
    }

    private fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], HEX_RADIX)
            val lo = Character.digit(hex[i + 1], HEX_RADIX)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl BITS_PER_NIBBLE) or lo).toByte()
            i += 2
        }
        return out
    }

    private companion object {
        /** Name of the [KeystoreBackedPrefsStore] preferences file holding the passphrase. */
        const val PREFS_NAME = "secure_db_passphrase_v2"

        /** Android Keystore alias of the AEAD key dedicated to the passphrase store. */
        const val KEY_ALIAS = "knotwork.db_passphrase"

        /** Preferences entry the hex-encoded passphrase is stored under. */
        const val PASSPHRASE_KEY = "db_passphrase_hex"

        /** Name of the legacy `EncryptedSharedPreferences` file from earlier releases. */
        const val LEGACY_PREFS_NAME = "secure_db_passphrase"

        /** Length, in bytes, of the random SQLCipher passphrase. */
        const val PASSPHRASE_BYTE_LENGTH: Int = 32

        /** Bit mask used to widen a signed `Byte` to an unsigned 0..255 `Int`. */
        const val BYTE_MASK: Int = 0xff

        /** Radix passed to [Character.digit] when decoding hexadecimal characters. */
        const val HEX_RADIX: Int = 16

        /** Number of bits in one hexadecimal digit; used when packing two nibbles into a byte. */
        const val BITS_PER_NIBBLE: Int = 4
    }
}
