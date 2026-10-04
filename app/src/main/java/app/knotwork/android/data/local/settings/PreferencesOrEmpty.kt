package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import timber.log.Timber
import java.io.IOException

/**
 * The preferences of this DataStore, with a failure to read them turned into empty preferences.
 *
 * An [IOException] (a corrupt or unreadable file) is logged and emitted as [emptyPreferences], so
 * every setting read through it falls back to its default instead of failing its collector. Any
 * other failure propagates. Every settings section reads through this one function, so the policy
 * is written once.
 *
 * @return A flow of the stored preferences, or of empty preferences after a read error.
 */
internal fun DataStore<Preferences>.preferencesOrEmpty(): Flow<Preferences> = data
    .catch { exception ->
        if (exception is IOException) {
            Timber.e(exception, "Error reading preferences")
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }
