package app.knotwork.android.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.domain.repositories.ShareAdmissionRepository
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ShareAdmissionRepository] kept in the app's preferences store as a short list of
 * admission times.
 *
 * **One `edit` is the whole admission.** DataStore runs each `edit` transform as a
 * single transaction, serialized against every other one on the same store and
 * finished only once the result is on disk. So the prune, the count and the new
 * entry below cannot interleave with a concurrent share — which is exactly the
 * property a burst of share starts would otherwise defeat.
 *
 * **What is stored, and for how long.** Epoch-millis of recent admissions,
 * comma-separated — never more than the ceiling can count, never the content of a
 * share or anything about its sender. Each admission rewrites the list from
 * scratch, dropping every time older than the window; nothing prunes it between
 * shares, so the times of the last shares stay on disk until the next one.
 *
 * **A time ahead of now still counts, up to one window ahead.** Concurrent shares
 * read the clock before they queue for the store, so the one recorded first may
 * carry a later time than the one being decided — dropping "future" times would let
 * exactly a burst through, which is the case the ceiling exists for (a test of fifty
 * concurrent shares caught that shape). A time more than a window ahead can only be
 * a clock that was set back, and is dropped, so such a change can hold sharing back
 * for at most two windows rather than for the length of the jump. A value that does
 * not parse counts as an empty ledger rather than failing every share forever.
 *
 * A dedicated key in the existing store rather than a table: a table is a
 * migration, for what is at most a few dozen numbers of no value past the hour.
 *
 * @property dataStore The app's single preferences store.
 */
@Singleton
class ShareAdmissionRepositoryImpl @Inject constructor(private val dataStore: DataStore<Preferences>) :
    ShareAdmissionRepository {

    override suspend fun admitWithinCeiling(nowMillis: Long, windowStartEpochMs: Long, limitPerWindow: Int): Boolean {
        // A caller contract violation, not a storage failure — never absorbed: a
        // non-positive limit would refuse every share while reading like a ceiling.
        require(limitPerWindow > 0) { "limitPerWindow must be positive, was $limitPerWindow" }
        var admitted = false
        return try {
            dataStore.edit { preferences ->
                val latestPlausible = nowMillis + (nowMillis - windowStartEpochMs)
                val recent = decode(preferences[PreferencesKeys.SHARE_ADMISSION_TIMES])
                    .filter { it in windowStartEpochMs..latestPlausible }
                admitted = recent.size < limitPerWindow
                val kept = if (admitted) recent + nowMillis else recent
                preferences[PreferencesKeys.SHARE_ADMISSION_TIMES] = kept.joinToString(SEPARATOR)
            }
            admitted
        } catch (e: IOException) {
            // Fail closed: an entry point any installed app can reach is not admitted
            // on the strength of a ledger that could not record it. Type only — the
            // message may quote the store's path.
            Timber.w("Share admission ledger unavailable (%s); refusing the share", e.javaClass.simpleName)
            false
        }
    }

    /**
     * Reads the stored admission times.
     *
     * @param stored The stored list, or `null` when nothing was ever admitted.
     * @return The times that parse; an unparseable entry is skipped.
     */
    private fun decode(stored: String?): List<Long> =
        stored?.split(SEPARATOR)?.mapNotNull { it.toLongOrNull() }.orEmpty()

    /** Preference keys owned by this ledger. */
    private object PreferencesKeys {
        /** Comma-separated epoch-millis of the admissions still inside the window. */
        val SHARE_ADMISSION_TIMES = stringPreferencesKey("share_admission_times")
    }

    private companion object {
        /** Separator of the stored admission times. */
        const val SEPARATOR = ","
    }
}
