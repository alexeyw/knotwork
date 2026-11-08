package app.knotwork.android.data.services

import app.knotwork.android.data.network.ResumableFileDownloader
import app.knotwork.android.domain.models.DownloadedModelCheck
import app.knotwork.android.domain.models.ModelFileHash
import app.knotwork.android.domain.services.ModelFileHasher
import app.knotwork.android.domain.usecases.RegisterDownloadedModelUseCase
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Turns a finished download into an installed model — or refuses to.
 *
 * The one place a downloaded file becomes a model: it hashes the complete part file, compares the
 * hash with the one the source published ([DownloadedModelCheck.of]), and only then moves the file
 * to its final name and registers it. A file whose hash differs is deleted and never registered;
 * that is the whole point of reading the published hash. A file the source published no hash for
 * is installed without a source hash of its own; whether the model reads as checked is then decided
 * by comparing the hash a checked download recorded earlier, if any, with the file now on disk
 * (`LocalModel.matchesPublishedHash`).
 *
 * The file is hashed **before** the rename, so a file that fails its check never appears under a
 * model's name — not even for the moment between the rename and the check, where a dying process
 * would have left it for the start-up pass to register.
 *
 * The hash is the one the background pass would have computed after registration
 * ([ModelFileHasher], the same streaming hasher); handing it to the registration means the pass
 * finds the file already hashed and reads nothing. The download costs one read of the file either
 * way — earlier, not twice.
 *
 * @property hasher Reads the part file and returns its SHA-256 with its stamp.
 * @property registerDownloadedModel Records the installed file in the model registry.
 */
class DownloadedModelInstaller @Inject constructor(
    private val hasher: ModelFileHasher,
    private val registerDownloadedModel: RegisterDownloadedModelUseCase,
) {

    /** What became of a downloaded file. */
    sealed interface Result {

        /**
         * The file is at its final name and registered as a model.
         *
         * @property path Absolute path of the installed file.
         * @property verified `true` when its hash matched the one its source published, `false`
         *   when the source published none.
         */
        data class Installed(val path: String, val verified: Boolean) : Result

        /**
         * The file's hash differs from the one its source published. The file was deleted and
         * nothing was registered.
         *
         * @property publishedSha256 The hash the source published.
         * @property actualSha256 The hash of the bytes that arrived.
         */
        data class Rejected(val publishedSha256: String, val actualSha256: String) : Result

        /**
         * The file was not installed and is still on disk under its partial name — it could not
         * be read for its check, or could not be moved to its final name. From Hugging Face,
         * another attempt finds the part complete and only repeats the check; from a host that
         * names no length, it downloads the file again.
         *
         * @property cause Which step stopped it — the download worker words it for the
         *   failure output, in the interface language.
         */
        data class NotInstalled(val cause: Cause) : Result

        /** The step that kept a file from being installed. */
        enum class Cause {

            /**
             * The source published a hash and the file could not be read to compare with it. Only a
             * Hugging Face download has a published hash, and its next attempt finds the part
             * complete — so it reads the file again rather than downloading it again.
             */
            UNREADABLE,

            /**
             * The file passed (or had nothing to pass) its check and could not be renamed to its
             * final name. From a host that names no length, the next attempt downloads it again.
             */
            NOT_MOVED,
        }
    }

    /**
     * Checks [downloaded] against the hash its source published and installs it when it passes.
     *
     * Suspends for the read of the whole file (seconds for a multi-gigabyte model) and stops
     * between chunks when cancelled, leaving the part file for the next attempt.
     *
     * @param downloaded The complete part file and the hash its source published.
     * @param fileName The model's file name, the registry's identity for it.
     * @param sourceUrl The address the file was downloaded from.
     * @return Whether the file was installed, rejected, or left for another attempt.
     */
    suspend fun install(
        downloaded: ResumableFileDownloader.Outcome.Downloaded,
        fileName: String,
        sourceUrl: String,
    ): Result {
        val part = File(downloaded.partPath)
        return when (val check = DownloadedModelCheck.of(downloaded.publishedSha256, hasher.hash(part.path))) {
            is DownloadedModelCheck.Mismatch -> {
                // Not the file the source offers: a corrupt transfer, two revisions spliced by a
                // resume, or bytes changed on the way. Keeping the part would make the next attempt
                // resume from it and arrive at the same wrong file.
                if (!part.delete()) Timber.w("A model file that failed its checksum could not be deleted")
                Timber.w("Downloaded model file does not match the SHA-256 its source published; deleted")
                Result.Rejected(publishedSha256 = check.publishedSha256, actualSha256 = check.actualSha256)
            }
            DownloadedModelCheck.Unreadable -> Result.NotInstalled(Result.Cause.UNREADABLE)
            is DownloadedModelCheck.Verified ->
                place(part, File(downloaded.targetPath), fileName, sourceUrl, check.hash, verified = true)
            is DownloadedModelCheck.Unverified ->
                place(part, File(downloaded.targetPath), fileName, sourceUrl, check.hash, verified = false)
        }
    }

    /**
     * Moves a file that passed (or had nothing to pass) its check to its final name and registers it.
     *
     * The hash goes with the registration only while it still describes the file at its final
     * name: a rename keeps the file's length and modification time, so it does, unless something
     * else wrote the file in between — then the background pass hashes it again.
     *
     * @param hash The file's hash, or `null` when it could not be read (an unchecked file only).
     * @param verified `true` when [hash] matched the hash the source published — it is then
     *   recorded as the source's hash.
     */
    private suspend fun place(
        part: File,
        target: File,
        fileName: String,
        sourceUrl: String,
        hash: ModelFileHash?,
        verified: Boolean,
    ): Result {
        if (!part.renameTo(target)) return Result.NotInstalled(Result.Cause.NOT_MOVED)
        registerDownloadedModel(
            fileName = fileName,
            path = target.absolutePath,
            sizeBytes = target.length(),
            sourceUrl = sourceUrl,
            sourceSha256 = hash?.sha256?.takeIf { verified },
            fileHash = hash?.takeIf { it.describesFile(target) },
        )
        return Result.Installed(path = target.absolutePath, verified = verified)
    }

    /** Whether this hash was computed over [file] as it is on disk now. */
    private fun ModelFileHash.describesFile(file: File): Boolean =
        file.isFile && describes(sizeBytes = file.length(), modifiedAtMs = file.lastModified())
}
