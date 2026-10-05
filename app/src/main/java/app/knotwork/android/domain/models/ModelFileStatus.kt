package app.knotwork.android.domain.models

/**
 * Where an installed model file stands with its checksum, as a check of a past
 * run needs to tell: a run can be repeated only on the file it ran on, and the
 * checksum is how that is known.
 */
sealed interface ModelFileStatus {

    /** No registered model names the file, or the file is gone from the device. */
    data object Missing : ModelFileStatus

    /** The file is there but has no current checksum yet — never hashed, or changed since. */
    data object HashPending : ModelFileStatus

    /**
     * The file is there and its checksum is current.
     *
     * @property sha256 Lowercase hex SHA-256 of the file.
     */
    data class Hashed(val sha256: String) : ModelFileStatus
}
