package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.services.TransientCacheSweeper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TransientCacheSweeper] over `Context.cacheDir`: walks every
 * [TransientCacheDirectory] entry — the registry, not a list of its own — and
 * applies [TransientCacheFiles.pruneOlderThan] with the shared retention.
 *
 * @property context Application context, used solely to locate the cache directory.
 * @property clock Supplies "now"; replaced in tests.
 */
@Singleton
class TransientCacheSweeperImpl internal constructor(private val context: Context, private val clock: () -> Long) :
    TransientCacheSweeper {

    /**
     * The constructor Hilt uses: the wall clock.
     *
     * @param context Application context, used solely to locate the cache directory.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, System::currentTimeMillis)

    /** Dispatcher for the directory walks; swapped in unit tests. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override suspend fun sweepExpired(): Int = withContext(dispatcher) {
        val cutoff = clock() - TransientCacheDirectory.RETENTION_MILLIS
        TransientCacheDirectory.entries.sumOf { directory ->
            TransientCacheFiles.pruneOlderThan(File(context.cacheDir, directory.dirName), cutoff)
        }
    }
}
