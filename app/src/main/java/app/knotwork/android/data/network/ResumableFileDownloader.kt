package app.knotwork.android.data.network

import android.content.Context
import app.knotwork.android.data.local.PathContainment
import app.knotwork.android.domain.constants.ModelDiscoveryConstants
import app.knotwork.android.domain.repositories.NetworkActivityTracker
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
import okhttp3.Response
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
 *  - **Bytes land in a `.part` file, never at the final name.** A finished
 *    transfer is handed back as [Outcome.Downloaded] — still the part file — and
 *    reaches the final name only through the rename the installer makes once the
 *    file has been checked (`DownloadedModelInstaller`). An interrupted transfer,
 *    or one that fails its check, can never be mistaken for an installed model.
 *  - **The part file is keyed by the source URL.** Resuming across a *different*
 *    URL that happens to use the same file name would splice two files together
 *    into a bundle that looks complete and is unusable; a URL that does not
 *    match simply starts fresh. One URL can still serve two revisions of a file —
 *    a Hugging Face `resolve/main` link does — which is what the check of the
 *    published SHA-256 catches.
 *
 * **What the source publishes about the file.** Hugging Face answers a download
 * from its own host with a redirect to its CDN, and that answer carries the file's
 * SHA-256 (`X-Linked-Etag`, for a file stored in LFS) and length
 * (`X-Linked-Size`) — measured equal to the `lfs.sha256` its API reports, on a
 * resume as well. The downloader reads them only from the hops Hugging Face
 * itself answered, for a request it sent to `https://huggingface.co` (the rule the
 * token follows), and hands the hash back with the file.
 *
 * @property context Application context, used to resolve the models directory.
 * @property client Shared OkHttp client.
 * @property networkActivityTracker Told when a request is sent and again as bytes arrive,
 *   so the More tab's privacy indicator reads "online" for the whole of a transfer that
 *   can run for many minutes, not only for its first one.
 */
class ResumableFileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val networkActivityTracker: NetworkActivityTracker,
) {

    /** Terminal result of one download attempt. */
    sealed interface Outcome {

        /**
         * Every byte of the file is on disk, still under its partial name.
         *
         * Not installed yet: the caller checks the file against [publishedSha256] and
         * only then moves it to [targetPath], or deletes it.
         *
         * @property partPath Absolute path of the complete part file.
         * @property targetPath Absolute path the file takes once it is installed —
         *   inside the models directory, whatever the requested name.
         * @property publishedSha256 The SHA-256 Hugging Face published for the file,
         *   lowercase hex, or `null` when the source published none (another host, a
         *   file outside LFS, or a Hub answer without the header).
         * @property bytesTransferred Bytes this attempt added to the part file — `0`
         *   when it found the part already complete. Like [Failure.bytesTransferred], it
         *   tells the caller whether this attempt made progress, should it still fail.
         */
        data class Downloaded(
            val partPath: String,
            val targetPath: String,
            val publishedSha256: String?,
            val bytesTransferred: Long = 0L,
        ) : Outcome

        /**
         * The attempt did not finish.
         *
         * @property message The transport's own failure text, or blank when it has none — the
         *   download worker words that case.
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
     * @return [Outcome.Downloaded] with the complete part file and the hash its
     *   source published, or [Outcome.Failure]. Any partial bytes are left on disk
     *   for the next attempt.
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
            // The transport's own text, or nothing: the worker words a failure without one.
            Outcome.Failure(e.message.orEmpty(), bytesTransferred = transferred.bytes)
        }
    }

    /**
     * Performs one request/stream cycle.
     *
     * @param allowResume `false` on the single retry taken when the server
     *   rejects our range (`416`) for a partial file that is not the whole file —
     *   it is then unusable, and retrying with resume enabled would loop.
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

        networkActivityTracker.recordOutbound()
        return client.newCall(request).execute().use { response ->
            val published = publishedFileOf(response, httpUrl)
            when {
                response.code == HTTP_RANGE_NOT_SATISFIABLE && allowResume -> {
                    // A part exactly as long as the published file is the whole file:
                    // an earlier attempt finished the transfer and stopped before the
                    // file was checked. It goes to the check, not back to the network.
                    if (alreadyOnDisk > 0 && published?.sizeBytes == alreadyOnDisk) {
                        onProgress(PERCENT.toInt())
                        return downloaded(part, target, published, transferred)
                    }
                    // Otherwise the partial file is at least as long as the resource
                    // the server is offering — it is stale or corrupt, not a prefix.
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
            downloaded(part, target, published, transferred)
        }
    }

    /** The complete [part], with what its source published about it and what this attempt fetched. */
    private fun downloaded(
        part: File,
        target: File,
        published: PublishedFile?,
        transferred: TransferCounter,
    ): Outcome.Downloaded = Outcome.Downloaded(
        partPath = part.absolutePath,
        targetPath = target.absolutePath,
        publishedSha256 = published?.sha256,
        bytesTransferred = transferred.bytes,
    )

    /**
     * Reads what Hugging Face published about the file from its own answer to the
     * download request.
     *
     * Only for a request to Hugging Face itself ([isHuggingFace], decided from the URL
     * this downloader requested — the token's rule), and only from the hops Hugging Face
     * answered: the first one, and any redirect it made within its own origin before
     * handing over to its CDN. A header on a CDN hop is never read — the CDN is what the
     * hash vouches against.
     *
     * @param response The final response of the call, carrying its redirects as
     *   [Response.priorResponse].
     * @param requested The URL this downloader requested.
     * @return The published hash and length, or `null` when the request was not to
     *   Hugging Face or no hop of its own carried them.
     */
    private fun publishedFileOf(response: Response, requested: HttpUrl): PublishedFile? {
        if (!isHuggingFace(requested)) return null
        val hops = generateSequence(response) { it.priorResponse }.toList().asReversed()
        val origin = hops.first().request.url
        val hubHop = hops
            .takeWhile { it.request.url.sameOriginAs(origin) }
            .lastOrNull { it.header(HEADER_LINKED_ETAG) != null }
            ?: return null
        return PublishedFile(
            sha256 = hubHop.header(HEADER_LINKED_ETAG)?.let(::sha256FromEtag),
            sizeBytes = hubHop.header(HEADER_LINKED_SIZE)?.toLongOrNull(),
        )
    }

    /** Whether this URL has the same scheme, host and port as [other]. */
    private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    /**
     * What the source published about a file.
     *
     * @property sha256 The file's SHA-256, lowercase hex, or `null` when the published
     *   value is not one (a file outside LFS is named by its 40-hex git blob id).
     * @property sizeBytes The file's length, or `null` when not published.
     */
    private data class PublishedFile(val sha256: String?, val sizeBytes: Long?)

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
                networkActivityTracker.recordOutbound()
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
    private fun mayCarryHuggingFaceToken(url: HttpUrl): Boolean = isHuggingFace(url)

    /**
     * Whether [url] is Hugging Face itself, over `https` — the one host whose answer is
     * trusted to name the file's hash, and the one host the token is sent to.
     */
    private fun isHuggingFace(url: HttpUrl): Boolean = url.isHttps && url.host == HF_TOKEN_HOST

    private companion object {
        /** The one host a saved Hugging Face token is ever sent to. */
        val HF_TOKEN_HOST: String = ModelDiscoveryConstants.HF_BASE_URL.toHttpUrl().host

        /** Chunk read from the network and flushed to disk on each loop. */
        const val BUFFER_BYTES: Long = 65_536L

        /** Suffix marking an in-progress download. */
        const val PART_SUFFIX = ".part"

        /** Hugging Face's name for the linked file's ETag — its SHA-256 for a file in LFS. */
        const val HEADER_LINKED_ETAG = "X-Linked-Etag"

        /** Hugging Face's name for the linked file's length. */
        const val HEADER_LINKED_SIZE = "X-Linked-Size"

        /** Hex digits in a SHA-256. */
        const val SHA256_HEX_LENGTH = 64

        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Sentinel for "the server did not report a content length". */
        const val UNKNOWN_LENGTH = -1L

        const val PERCENT = 100L

        /**
         * The SHA-256 an ETag value names, or `null` when it names none.
         *
         * Hugging Face quotes the value (`"181938…"`); a weak marker (`W/`) is dropped as
         * well. Only 64 hex digits are a SHA-256 — a file outside LFS carries its 40-hex
         * git blob id here, which is not the hash of its bytes.
         *
         * @param etag The raw header value.
         * @return The SHA-256 in lowercase hex, or `null`.
         */
        fun sha256FromEtag(etag: String): String? {
            val value = etag.trim().removePrefix("W/").trim('"').lowercase()
            val isSha256 = value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }
            return value.takeIf { isSha256 }
        }
    }
}
