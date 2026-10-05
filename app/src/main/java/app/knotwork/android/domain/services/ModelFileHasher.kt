package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.ModelFileHash

/**
 * Computes the SHA-256 of a model file on disk.
 *
 * A domain seam over file I/O, so the background hashing pass
 * ([app.knotwork.android.domain.usecases.ComputeModelFileHashesUseCase]) decides
 * *which* files to hash without touching the file system itself.
 */
interface ModelFileHasher {

    /**
     * Reads the whole file at [path] and returns its SHA-256 with the stamp of
     * the file that was read.
     *
     * Suspends for as long as the read takes (seconds for a multi-gigabyte
     * model) and stops promptly when cancelled.
     *
     * @param path Absolute path of the model file.
     * @return The hash with the file's size and last-modified time, or `null`
     *   when the file is missing, unreadable, or changed while it was being read
     *   — a hash of a moving file describes no file at all.
     */
    suspend fun hash(path: String): ModelFileHash?
}
