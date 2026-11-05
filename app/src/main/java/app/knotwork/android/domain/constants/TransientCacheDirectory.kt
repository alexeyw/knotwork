package app.knotwork.android.domain.constants

/**
 * Every directory the app creates under its cache to pass a file to another app,
 * or to take one from it — and the one retention rule all of them share.
 *
 * Each directory has an owner that deletes its entry when the handoff ends (an
 * ingested capture, a transcribed clip, a deleted workspace file). The owner is
 * not enough on its own: a process death between the write and the cleanup, a
 * result that never comes back, or a share nobody follows up leaves the file
 * behind, and the OS evicts cache only under storage pressure — on a device that
 * never runs low, never. So the daily maintenance pass (`TransientCacheSweeper`)
 * removes anything older than [RETENTION_MILLIS] from every entry here.
 *
 * **The registry is the list.** `TransientCacheDirectoryGuardTest` fails when
 * code builds a `File` under the cache directory without naming an entry here,
 * or when `res/xml/file_paths.xml` exposes a cache path that is not one — so a
 * new handoff directory cannot be added without being swept.
 *
 * @property dirName The directory's name under the cache directory; for the
 *   four the `FileProvider` serves, also its `<cache-path>` `path`.
 */
enum class TransientCacheDirectory(val dirName: String) {
    /**
     * Camera captures. The camera app writes the full-resolution original here —
     * the only copy that still carries its EXIF, GPS included — and the ingest
     * deletes it once the downscaled attachment exists.
     */
    CAMERA_CAPTURE("images"),

    /**
     * Copies of workspace files staged for the share sheet, one slot per file and
     * share. Deleting the workspace file deletes its copies.
     */
    WORKSPACE_SHARE("shared"),

    /** Journal exports staged for the share sheet; each export replaces the last. */
    JOURNAL_EXPORT("journal"),

    /**
     * Chat exports staged for the share sheet; each export replaces the last. Its
     * own directory, so sharing a chat never deletes a journal a receiving app may
     * still be reading, and the other way round.
     */
    CHAT_EXPORT("chat"),

    /** Voice-input clips, recorded or picked, waiting to be transcribed. */
    VOICE_CLIP("audio"),

    /**
     * The database snapshot a backup export streams into its archive — SQLCipher-encrypted under the
     * archive's own key, deleted as soon as the export ends. Not shared with any app; listed so a
     * process death mid-export cannot leave it behind for good.
     */
    BACKUP_SNAPSHOT("backup"),
    ;

    /** The retention rule shared by every directory in the registry. */
    companion object {
        /**
         * How long an entry may stay once written: one hour. Long enough for an app
         * that received a share to read it at its own pace, and far longer than any
         * capture or clip waits for its ingest. An estimate, not a measurement.
         */
        const val RETENTION_MILLIS: Long = 60L * 60L * 1_000L
    }
}
