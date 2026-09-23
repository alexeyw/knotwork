package app.knotwork.android.data.network

import android.content.Context
import app.knotwork.android.data.local.PathContainment
import app.knotwork.android.domain.constants.ModelDiscoveryConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import okio.buffer
import okio.sink
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Streams a model file to external storage, able to **pick up where a previous
 * attempt stopped**.
 *
 * A model bundle is several gigabytes; on a phone that means the transfer will
 * routinely be interrupted (network drop, the system stopping the worker, the
 * user cancelling). Restarting such a download from zero throws away minutes of
 * the user's time and, on a metered connection, their money — so bytes already
 * on disk are kept and requested back with an HTTP `Range` header.
 *
 * Two invariants make that safe:
 *
 *  - **Bytes land in a `.part` file, never at the final name.** The finished
 *    file appears only via a rename, so an interrupted transfer can never be
 *    mistaken for an installed model (which is exactly what it used to look
 *    like).
 *  - **The part file is keyed by the source URL.** Resuming across a *different*
 *    URL that happens to use the same file name would splice two files together
 *    into a bundle that looks complete and is unusable; a URL that does not
 *    match simply starts fresh.
 *
 * @property context Application context, used to resolve the models directory.
 * @property client Shared OkHttp client.
 */
class ResumableFileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /** Terminal result of one download attempt. */
    sealed interface Outcome {

        /**
         * The file was downloaded in full and moved to its final name.
         *
         * @property path Absolute path of the finished file.
         */
        data class Success(val path: String) : Outcome

        /**
         * The attempt did not finish.
         *
         * @property message Human-readable failure text.
         * @property httpCode The HTTP status when the server answered with one,
         *   or `null` for transport/IO failures. Callers use the distinction to
         *   decide whether retrying can possibly help: a `404` or `401` will
         *   fail again, a dropped connection will not.
         * @property bytesTransferred Bytes this attempt added to the partial
         *   file. An attempt that moved bytes is evidence the transfer is alive
         *   and resuming is working, which callers weigh against their retry
         *   budget — a flaky connection should not exhaust it while the file is
         *   visibly growing.
         */
        data class Failure(val message: String, val httpCode: Int? = null, val bytesTransferred: Long = 0L) : Outcome
    }

    /**
     * Downloads [url] into [fileName], resuming a matching partial file.
     *
     * @param fileName Desired local file name (may be attacker-influenced — see
     *   [resolveSafeTarget]).
     * @param authToken The saved Hugging Face token, or `null`. It is attached only
     *   to an `https` request whose host is Hugging Face itself (see
     *   [mayCarryHuggingFaceToken]) — whatever the caller decided, never to a URL the
     *   user pasted, a mirror, or the CDN the Hub redirects to.
     * @param onProgress Invoked with 0..100 whenever the whole-percent figure
     *   changes. Not called when the server reports no content length.
     * @return [Outcome.Success] with the final path, or [Outcome.Failure]. Any
     *   partial bytes are left on disk for the next attempt.
     */
    suspend fun download(
        url: String,
        fileName: String,
        authToken: String?,
        onProgress: suspend (Int) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val target = resolveSafeTarget(fileName)
            ?: return@withContext Outcome.Failure("Invalid model file name: $fileName")
        val part = File(target.parentFile, "${target.name}.${urlKey(url)}$PART_SUFFIX")
        discardStaleParts(target, part)
        val transferred = TransferCounter()

        try {
            attempt(url, target, part, authToken, onProgress, transferred, allowResume = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Model download failed after %d byte(s)", transferred.bytes)
            Outcome.Failure(e.message ?: "Unknown download error", bytesTransferred = transferred.bytes)
        }
    }

    /**
     * Performs one request/stream cycle.
     *
     * @param allowResume `false` on the single retry taken when the server
     *   rejects our range (`416`) — the partial file is then unusable, and
     *   retrying with resume enabled would loop.
     */
    private suspend fun attempt(
        url: String,
        target: File,
        part: File,
        authToken: String?,
        onProgress: suspend (Int) -> Unit,
        transferred: TransferCounter,
        allowResume: Boolean,
    ): Outcome {
        val alreadyOnDisk = if (allowResume && part.exists()) part.length() else 0L
        if (!allowResume) part.delete()

        val httpUrl = url.toHttpUrl()
        val request = Request.Builder()
            .url(httpUrl)
            .apply {
                if (!authToken.isNullOrBlank() && mayCarryHuggingFaceToken(httpUrl)) {
                    addHeader("Authorization", "Bearer $authToken")
                }
                if (alreadyOnDisk > 0) addHeader("Range", "bytes=$alreadyOnDisk-")
            }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_RANGE_NOT_SATISFIABLE && allowResume -> {
                    // The partial file is at least as long as the resource the
                    // server is offering — it is stale or corrupt, not a prefix.
                    Timber.w("Server rejected the resume range; restarting the download from zero.")
                    return attempt(url, target, part, authToken, onProgress, transferred, allowResume = false)
                }

                !response.isSuccessful -> return Outcome.Failure(
                    message = "Server returned code: ${response.code}",
                    httpCode = response.code,
                    bytesTransferred = transferred.bytes,
                )
            }

            // A 200 to a ranged request means the server ignored the range and
            // is sending the whole file again — the bytes on disk must go, or
            // the file would end up with a duplicated prefix.
            val resumed = response.code == HTTP_PARTIAL_CONTENT && alreadyOnDisk > 0
            val startOffset = if (resumed) alreadyOnDisk else 0L
            if (!resumed && part.exists()) part.delete()

            val body = response.body
            val totalBytes = body.contentLength().takeIf { it > 0 }?.plus(startOffset) ?: UNKNOWN_LENGTH
            streamToPart(
                source = body.source(),
                part = part,
                append = resumed,
                startOffset = startOffset,
                totalBytes = totalBytes,
                transferred = transferred,
                onProgress = onProgress,
            )
        }

        if (!part.renameTo(target)) {
            return Outcome.Failure("Could not move the downloaded file into place.")
        }
        return Outcome.Success(target.absolutePath)
    }

    /**
     * Copies the response body into [part], emitting whole-percent progress.
     *
     * @param append `true` to keep the bytes already in [part] (a resumed
     *   transfer), `false` to write from the beginning.
     * @param startOffset Bytes already on disk, counted into the progress figure
     *   so a resumed download does not restart its progress bar at zero.
     * @param totalBytes Expected final size, or [UNKNOWN_LENGTH] when the server
     *   did not say.
     */
    private suspend fun streamToPart(
        source: BufferedSource,
        part: File,
        append: Boolean,
        startOffset: Long,
        totalBytes: Long,
        transferred: TransferCounter,
        onProgress: suspend (Int) -> Unit,
    ) {
        val sink = part.sink(append = append).buffer()
        try {
            val buffer = okio.Buffer()
            var written = startOffset
            var lastPercent = -1
            var read: Long
            while (source.read(buffer, BUFFER_BYTES).also { read = it } != -1L) {
                // The read itself is blocking, so cancellation is observed
                // between chunks — bounded by one buffer, and the bytes already
                // written stay on disk for the next attempt.
                currentCoroutineContext().ensureActive()
                sink.write(buffer, read)
                written += read
                transferred.bytes += read
                lastPercent = reportProgress(written, totalBytes, lastPercent, onProgress)
            }
            sink.flush()
        } finally {
            sink.close()
            source.close()
        }
    }

    /**
     * Emits [onProgress] when the whole-percent figure moves.
     *
     * @return The percent last reported, to carry into the next chunk.
     */
    private suspend fun reportProgress(
        written: Long,
        totalBytes: Long,
        lastPercent: Int,
        onProgress: suspend (Int) -> Unit,
    ): Int {
        if (totalBytes <= 0) return lastPercent
        val percent = ((written * PERCENT) / totalBytes).toInt().coerceIn(0, PERCENT.toInt())
        if (percent == lastPercent) return lastPercent
        onProgress(percent)
        return percent
    }

    /**
     * Deletes partial files left for the same target by a *different* source
     * URL. Without this, switching the URL behind a file name would silently
     * accumulate abandoned gigabytes.
     */
    private fun discardStaleParts(target: File, current: File) {
        val siblings = target.parentFile?.listFiles() ?: return
        siblings
            .filter { it.name.startsWith("${target.name}.") && it.name.endsWith(PART_SUFFIX) && it != current }
            .forEach { stale ->
                Timber.i("Discarding stale partial download %s", stale.name)
                stale.delete()
            }
    }

    /**
     * Resolves [fileName] to a download target strictly inside the models
     * directory, or `null` when the name is unsafe. The Discover/install flow
     * passes the Hugging Face `rfilename` verbatim (only filtered by extension),
     * so a hostile listing could contain `../` segments aimed at the SQLCipher
     * DB or other app files.
     *
     * Path separators are **flattened into a single safe file name** rather than
     * dropped to the last segment: collapsing to the basename would make
     * `q4/model.litertlm` and `q8/model.litertlm` resolve to the same target and
     * silently overwrite each other, even though they register as distinct
     * models. Replacing every `/`/`\` with `_` keeps the file inside the models
     * directory (no traversal) while preserving uniqueness across sub-directory
     * variants. The result is still rejected when it degenerates to `.`/`..`/blank,
     * and containment is double-checked via the canonical path.
     *
     * @param fileName The requested file name (possibly attacker-influenced).
     * @return The safe target [File], or `null` if [fileName] is rejected.
     */
    private fun resolveSafeTarget(fileName: String): File? {
        val safeName = fileName.replace('/', '_').replace('\\', '_')
        if (safeName.isBlank() || safeName == "." || safeName == "..") return null
        val dir = context.getExternalFilesDir(null) ?: return null
        val target = File(dir, safeName)
        // The path handed back keeps the form callers store; only the check is canonical.
        return target.takeIf { PathContainment.childOrNull(it, dir) != null }
    }

    /**
     * Bytes written during the current attempt. A plain counter rather than a
     * return value because the interesting case is the *failed* attempt, where
     * the count leaves via an exception path.
     */
    private class TransferCounter(var bytes: Long = 0L)

    /** Stable, filename-safe fingerprint of the source URL. */
    private fun urlKey(url: String): String = Integer.toHexString(url.hashCode())

    /**
     * Whether a request to [url] may carry the saved Hugging Face token.
     *
     * **Why the decision is made here, from the destination.** Two callers decide
     * whether to pass the token at all — the Models screen and the Discover install
     * flow — and both decide it from "is a token saved?", because the token field
     * sits beside a free-text model-URL field. Deciding it on the line that builds
     * the request, from the URL itself, is the only arrangement where no caller can
     * hand the credential to another host.
     *
     * **Why host equality is enough.** Measured on the Hub: a `resolve/main` download
     * answers `302` with a signed URL on a different host (`*.cdn.hf.co`), and the
     * signature authorises that hop. The token is needed on the first request only,
     * and OkHttp drops `Authorization` from any redirect that changes host, port or
     * scheme. A suffix rule would therefore buy nothing, while widening the set of
     * hosts the token can reach. `https` is required so the token never travels in
     * cleartext, even to the right host.
     *
     * @param url The request's own URL.
     * @return `true` only for `https://huggingface.co/…`.
     */
    private fun mayCarryHuggingFaceToken(url: HttpUrl): Boolean = url.isHttps && url.host == HF_TOKEN_HOST

    private companion object {
        /** The one host a saved Hugging Face token is ever sent to. */
        val HF_TOKEN_HOST: String = ModelDiscoveryConstants.HF_BASE_URL.toHttpUrl().host

        /** Chunk read from the network and flushed to disk on each loop. */
        const val BUFFER_BYTES: Long = 65_536L

        /** Suffix marking an in-progress download. */
        const val PART_SUFFIX = ".part"

        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Sentinel for "the server did not report a content length". */
        const val UNKNOWN_LENGTH = -1L

        const val PERCENT = 100L
    }
}
