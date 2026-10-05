package app.knotwork.android.domain.models

/**
 * The SHA-256 of an installed model file, with the stamp of the file it was
 * computed from.
 *
 * A model file is several gigabytes, so its hash is computed once — when the
 * model is registered, and in the background for a model installed before
 * hashes existed — and kept in the registry, never per run. The stamp is what
 * keeps that cached value honest: a file replaced on disk after hashing (a
 * re-download, a copy over it) no longer matches its stamp, and a stale hash
 * reads as "not known yet" instead of vouching for bytes it was never computed
 * over.
 *
 * @property sha256 Lowercase hex SHA-256 of the whole file.
 * @property fileSizeBytes The file's length when it was hashed.
 * @property fileModifiedAtMs The file's last-modified time when it was hashed,
 *   in epoch milliseconds.
 */
data class ModelFileHash(val sha256: String, val fileSizeBytes: Long, val fileModifiedAtMs: Long) {

    /**
     * Whether this hash still describes a file that now has the given stamp.
     *
     * @param sizeBytes The file's current length.
     * @param modifiedAtMs The file's current last-modified time, in epoch milliseconds.
     * @return `true` when both match the stamp taken at hashing time.
     */
    fun describes(sizeBytes: Long, modifiedAtMs: Long): Boolean =
        fileSizeBytes == sizeBytes && fileModifiedAtMs == modifiedAtMs
}
