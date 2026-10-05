package app.knotwork.android.domain.services

/**
 * Domain seam for scheduling the background pass that hashes installed model
 * files.
 *
 * A model file is hashed once and the result kept in the registry (see
 * [app.knotwork.android.domain.models.ModelFileHash]); the pass runs off the hot
 * path because reading several gigabytes takes seconds. Keeping this an
 * interface lets the domain ask for the pass when a model is registered without
 * depending on WorkManager. The data-layer implementation enqueues a job that
 * runs [app.knotwork.android.domain.usecases.ComputeModelFileHashesUseCase].
 */
interface ModelFileHashScheduler {

    /**
     * Enqueues a hashing pass. Implementations chain it after a pass that is
     * already running, so a model registered mid-pass is never missed by a pass
     * that listed the files before it existed.
     */
    fun schedule()

    /**
     * Enqueues a pass only when some registered model file has no current hash —
     * a model installed before hashes existed, a file replaced on disk, or a
     * pass that was lost. Run at every start; a cheap check when nothing is
     * pending. Never throws for a registry that cannot be read: start-up must
     * reach the recovery screen of a database that cannot be opened.
     */
    suspend fun rearmIfPending()
}
