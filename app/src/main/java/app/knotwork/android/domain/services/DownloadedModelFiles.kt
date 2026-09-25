package app.knotwork.android.domain.services

/**
 * The model files that are on disk in the downloads directory, whatever the registry
 * says about them.
 *
 * The registry of installed models is a database table; the files live outside the
 * database. Anything that empties the table but not the directory — the recovery
 * screen's *Erase data*, which keeps downloaded models on purpose — leaves gigabytes
 * the app no longer lists. This port is what lets the app find them again.
 */
interface DownloadedModelFiles {

    /**
     * Lists the model files in the downloads directory: regular files whose name ends
     * with one of [EXTENSIONS]. Partial downloads and anything else in the directory
     * are left out.
     *
     * @return The files found, in no particular order; empty when the directory is unavailable.
     */
    suspend fun list(): List<DownloadedModelFile>

    /** What counts as a model file. */
    companion object {
        /**
         * Extensions of the model files the app downloads — the formats the custom-URL
         * download accepts (`.litertlm`, `.task`, `.gguf`), matched case-insensitively.
         */
        val EXTENSIONS: List<String> = listOf(".litertlm", ".task", ".gguf")
    }
}

/**
 * One model file on disk.
 *
 * @property name The file's name, as the registry records it.
 * @property path The file's absolute path, in the form the downloader records.
 * @property sizeBytes The file's length.
 */
data class DownloadedModelFile(val name: String, val path: String, val sizeBytes: Long)
