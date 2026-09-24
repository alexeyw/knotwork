package app.knotwork.android.domain.services

import app.knotwork.android.domain.constants.TransientCacheDirectory

/**
 * The backstop for every handoff directory in [TransientCacheDirectory]: removes
 * whatever has outlived [TransientCacheDirectory.RETENTION_MILLIS].
 *
 * Each directory's owner cleans up as the handoff ends; this pass catches what
 * the owner never got to — a capture whose result never came back, a clip the
 * process died before transcribing, a share copy nobody deleted — which the OS
 * would otherwise keep until the device ran low on storage.
 */
interface TransientCacheSweeper {
    /**
     * Removes expired entries from every registered directory.
     *
     * @return How many top-level entries (files or share slots) were removed.
     */
    suspend fun sweepExpired(): Int

    /**
     * Removes every entry from every registered directory, whatever its age, for the
     * user-confirmed recovery wipe (*Erase data*): share copies of workspace files,
     * staged journal exports, camera captures and voice clips go with the data they
     * were made from.
     *
     * @return `true` when every registered directory is empty afterwards.
     */
    suspend fun sweepAll(): Boolean
}
