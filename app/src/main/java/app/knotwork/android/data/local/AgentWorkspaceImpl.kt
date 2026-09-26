package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.data.local.WorkspaceTree.Tally
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.models.WorkspaceTextPreview
import app.knotwork.android.domain.models.WorkspaceUsage
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.WorkspaceNamePolicy
import app.knotwork.android.domain.services.WorkspaceTextEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filesystem-backed [AgentWorkspace] rooted at `files/agent_workspace/` inside
 * the app's private storage ([Context.filesDir]).
 *
 * The directory is created lazily on first access. All blocking I/O runs on
 * [Dispatchers.IO]. Quota limits are read fresh from [SettingsRepository] on
 * each write so a settings change takes effect immediately.
 *
 * **Containment** is enforced in exactly one place — [canonicalResolve] — which
 * every public method funnels through. Path canonicalisation collapses `..`
 * segments and resolves symlinks, and the result is accepted only if it stays
 * at or under the canonicalised root (compared with a trailing [File.separator]
 * so a sibling directory such as `agent_workspace_evil` cannot pass the prefix
 * check). This is the project's path-traversal mitigation, per the official
 * Android guidance. The same gate refuses a path the filesystem cannot take (a
 * NUL byte, or one it rejects outright) with [WorkspaceError.InvalidPath] rather
 * than letting the exception escape, and a write that would **create** an entry
 * checks its path against [WorkspaceNamePolicy].
 *
 * **What the quota counts.** Two resources, both bounded: the bytes of the files
 * (against the settings' total) and the number of entries, files and directories
 * together (against [maxEntries]) — a directory costs no bytes and a tiny file
 * almost none, so bytes alone would not bound a write loop. Both are counted over
 * the same walk [list] uses ([WorkspaceTree.realEntries]), which never follows a
 * symbolic link, so neither can reach outside the root or loop. Directories never
 * outlive their contents: a delete removes the ancestors it empties, and each
 * recount removes any empty directory left from before.
 *
 * **Concurrency.** Mutating operations serialise on [mutex], which also guards
 * the cached counts ([cachedTally]). The cache is valid because the workspace is
 * the only writer of its directory; it is recomputed by walking the tree whenever
 * it is unset and updated in place after each write.
 *
 * **Share copies.** [stageForShare] copies a file into the share staging
 * ([WorkspaceShareCopies]) under the same [mutex], and [delete] removes those
 * copies with the file, so no copy of a deleted file survives in the cache.
 *
 * @property context Application context, used to locate [Context.filesDir] (the
 *   workspace) and [Context.cacheDir] (the share staging).
 * @property settingsRepository Source of the per-file and total-size quotas.
 * @property maxEntries Ceiling on the number of entries — files and directories
 *   together — the workspace may hold; [DEFAULT_MAX_ENTRIES] in the app, lowered
 *   by tests so the boundary can be reached cheaply.
 */
@Singleton
class AgentWorkspaceImpl internal constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val maxEntries: Int,
) : AgentWorkspace {

    /**
     * The constructor Hilt uses: the entry ceiling is the fixed [DEFAULT_MAX_ENTRIES].
     *
     * @param context Application context, used to locate the workspace and its share staging.
     * @param settingsRepository Source of the per-file and total-size quotas.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ) : this(context, settingsRepository, DEFAULT_MAX_ENTRIES)

    private val mutex = Mutex()

    /** Copies staged for the share sheet; cleaned up with the files they copy. */
    private val shareCopies = WorkspaceShareCopies(
        root = { File(context.cacheDir, TransientCacheDirectory.WORKSPACE_SHARE.dirName) },
    )

    @Volatile
    private var cachedTally: Tally? = null

    override suspend fun resolve(relativePath: String): WorkspaceResult<WorkspaceFile> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> WorkspaceResult.Success(toWorkspaceFile(resolved.value))
        }
    }

    override suspend fun readText(relativePath: String): WorkspaceResult<String> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> readTextResolved(resolved.value)
        }
    }

    override suspend fun writeText(
        relativePath: String,
        content: String,
        overwrite: Boolean,
    ): WorkspaceResult<WorkspaceFile> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> mutex.withLock { writeTextLocked(resolved.value, content, overwrite) }
        }
    }

    override suspend fun appendText(relativePath: String, content: String): WorkspaceResult<WorkspaceFile> =
        withContext(Dispatchers.IO) {
            when (val resolved = canonicalResolve(relativePath)) {
                is WorkspaceResult.Failure -> resolved
                is WorkspaceResult.Success -> mutex.withLock { appendTextLocked(resolved.value, content) }
            }
        }

    override suspend fun editText(
        relativePath: String,
        oldText: String,
        newText: String,
    ): WorkspaceResult<WorkspaceFile> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> mutex.withLock { editTextLocked(resolved.value, oldText, newText) }
        }
    }

    override suspend fun delete(relativePath: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> mutex.withLock { deleteLocked(resolved.value) }
        }
    }

    override suspend fun list(): WorkspaceResult<List<WorkspaceFile>> = withContext(Dispatchers.IO) {
        val root = rootDir()
        // The same walk the quota counts, so the two can never disagree about what is in
        // the workspace. Transient atomic-write scratch files are filtered so a crashed
        // write's leftover never surfaces to the agent.
        val files = WorkspaceTree.realEntries(root)
            .filter { it.isFile && !isScratchFile(it) }
            .map { toWorkspaceFile(it, root) }
            .sortedBy { it.relativePath }
            .toList()
        WorkspaceResult.Success(files)
    }

    override suspend fun usage(): WorkspaceResult<WorkspaceUsage> = withContext(Dispatchers.IO) {
        val limit = settingsRepository.workspaceMaxTotalBytes.first()
        val used = mutex.withLock { tallyLocked().bytes }
        WorkspaceResult.Success(WorkspaceUsage(usedBytes = used, limitBytes = limit))
    }

    override suspend fun readTextPreview(relativePath: String, maxBytes: Int): WorkspaceResult<WorkspaceTextPreview> =
        withContext(Dispatchers.IO) {
            when (val resolved = canonicalResolve(relativePath)) {
                is WorkspaceResult.Failure -> resolved
                is WorkspaceResult.Success -> previewResolved(resolved.value, maxBytes)
            }
        }

    override suspend fun importBytes(
        relativePath: String,
        source: InputStream,
        overwrite: Boolean,
    ): WorkspaceResult<WorkspaceFile> = withContext(Dispatchers.IO) {
        // Read the stream up to one byte past the per-file ceiling: if the source is
        // larger we can report TooLarge without ever holding more than the limit (+1)
        // in memory. The quota-checked atomic write is then identical to writeText.
        val limit = maxFileSizeBytes()
        val bytes = readBounded(source, limit) ?: return@withContext WorkspaceResult.Failure(WorkspaceError.TooLarge)
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> mutex.withLock { writeBytesLocked(resolved.value, bytes, overwrite) }
        }
    }

    override suspend fun exportTo(relativePath: String, sink: OutputStream): WorkspaceResult<Unit> =
        withContext(Dispatchers.IO) {
            when (val resolved = canonicalResolve(relativePath)) {
                is WorkspaceResult.Failure -> resolved
                is WorkspaceResult.Success -> exportResolved(resolved.value, sink)
            }
        }

    override suspend fun eraseAll(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Not rootDir(): that would recreate the directory it is asked to remove.
            val root = File(context.filesDir, WORKSPACE_DIR_NAME)
            val erased = root.deleteRecursively()
            cachedTally = null
            erased
        }
    }

    override suspend fun stageForShare(relativePath: String): WorkspaceResult<String> = withContext(Dispatchers.IO) {
        when (val resolved = canonicalResolve(relativePath)) {
            is WorkspaceResult.Failure -> resolved
            is WorkspaceResult.Success -> mutex.withLock { stageForShareLocked(resolved.value) }
        }
    }

    /**
     * Copies an already-resolved [target] into the share staging. Under [mutex],
     * so a concurrent delete either sees the copy (and removes it) or runs first
     * (and staging finds no file).
     */
    private fun stageForShareLocked(target: File): WorkspaceResult<String> {
        if (!target.isFile) return WorkspaceResult.Failure(WorkspaceError.NotFound)
        val staged = shareCopies.stage(target, WorkspaceTree.relativePath(target, rootDir()))
        return WorkspaceResult.Success(staged.absolutePath)
    }

    /**
     * Reads a bounded leading slice of an already-resolved (in-bounds) [target]
     * for preview, enforcing existence and text-ness but never the size cap.
     */
    private fun previewResolved(target: File, maxBytes: Int): WorkspaceResult<WorkspaceTextPreview> {
        if (!target.isFile) return WorkspaceResult.Failure(WorkspaceError.NotFound)
        val totalBytes = target.length()
        val slice = target.inputStream().use { input -> input.readNBytes(maxBytes) }
        if (slice.any { it == 0.toByte() }) return WorkspaceResult.Failure(WorkspaceError.NotAText)
        val fileLongerThanSlice = totalBytes > slice.size.toLong()
        // Decode the full slice first; only when the budget cut the file mid-character do
        // we tolerate dropping up to a few trailing bytes (an incomplete final char).
        // Trimming unconditionally would slice a valid trailing character on a
        // boundary-aligned window and wrongly report text as binary.
        val maxTrim = if (fileLongerThanSlice) MAX_UTF8_TAIL_BYTES else 0
        val text = (0..maxTrim).firstNotNullOfOrNull { trim -> decodeUtf8OrNull(slice, slice.size - trim) }
            ?: return WorkspaceResult.Failure(WorkspaceError.NotAText)
        return WorkspaceResult.Success(
            WorkspaceTextPreview(text = text, totalBytes = totalBytes, truncated = fileLongerThanSlice),
        )
    }

    /**
     * Streams an already-resolved (in-bounds) regular [target] to [sink]. Only
     * regular files are exportable; a directory or missing path is [WorkspaceError.NotFound].
     */
    private fun exportResolved(target: File, sink: OutputStream): WorkspaceResult<Unit> {
        if (!target.isFile) return WorkspaceResult.Failure(WorkspaceError.NotFound)
        target.inputStream().use { input -> input.copyTo(sink) }
        return WorkspaceResult.Success(Unit)
    }

    /**
     * Reads [input] fully into a byte array, refusing to allocate more than
     * [limit] bytes. Returns the bytes when the stream fits within [limit], or
     * `null` when it would exceed it (so the caller maps that to [WorkspaceError.TooLarge]).
     */
    private fun readBounded(input: InputStream, limit: Long): ByteArray? {
        // Read one byte past the limit: if anything beyond `limit` exists, the
        // result is larger than `limit` and we bail without buffering the whole thing.
        val capped = input.readNBytes((limit + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return if (capped.size.toLong() > limit) null else capped
    }

    /**
     * The single canonicalisation gate. Refuses a NUL byte and rejects absolute
     * paths outright, then canonicalises `root/relativePath` and accepts it only if
     * it stays inside the canonical root.
     *
     * @param relativePath The caller-supplied workspace-relative path.
     * @return [WorkspaceResult.Success] with the canonical [File] (which may not
     *   yet exist), or [WorkspaceResult.Failure] with
     *   [WorkspaceError.PathOutsideWorkspace], [WorkspaceError.InvalidPath] (a NUL
     *   byte, or a path the filesystem refuses to canonicalise) or
     *   [WorkspaceError.NotFound] (the reserved scratch suffix).
     */
    private fun canonicalResolve(relativePath: String): WorkspaceResult<File> {
        val lexical = when {
            // No existing name can hold a NUL, and `java.io.File` answers one by throwing
            // (or, below the JDK, by cutting the string short): refuse it before either.
            relativePath.indexOf(NUL) >= 0 -> WorkspaceError.InvalidPath
            File(relativePath).isAbsolute -> WorkspaceError.PathOutsideWorkspace
            else -> null
        }
        if (lexical != null) return WorkspaceResult.Failure(lexical)
        val root = rootDir()
        val target = canonicalOrNull(File(root, relativePath))
            ?: return WorkspaceResult.Failure(WorkspaceError.InvalidPath)
        val refusal = when {
            !isInsideRoot(target, root) -> WorkspaceError.PathOutsideWorkspace
            // Reject the reserved atomic-write scratch suffix: such files are hidden from
            // listings and excluded from quota accounting, so letting the agent address one
            // would let it write storage the quota never counts (and that it could never see
            // or clean up). Reported as NotFound so the artifact stays invisible.
            isScratchFile(target) -> WorkspaceError.NotFound
            // Checked on the path as requested, not on the canonical one: where the
            // JVM cannot encode a character in a file name (a lone surrogate, or
            // anything outside ASCII under a POSIX locale) canonicalising already
            // replaced it with `?`, and the name rules below would pass the
            // replacement. Only for an entry about to be created — one that exists,
            // however it is named, stays readable and deletable.
            !target.exists() && WorkspaceNamePolicy.hasForbiddenCharacter(relativePath) -> WorkspaceError.InvalidPath
            else -> null
        }
        return refusal?.let { WorkspaceResult.Failure(it) } ?: WorkspaceResult.Success(target)
    }

    /**
     * Canonicalises [file], or returns `null` when the filesystem rejects the path
     * itself — the interface promises a typed refusal, not an exception.
     *
     * Only the exception's type is logged. A warning reaches crash reports once the
     * user opts in, the path is caller-supplied, and a platform's exception message
     * may quote it.
     */
    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (e: IOException) {
        Timber.w("Workspace path could not be canonicalised (%s)", e.javaClass.simpleName)
        null
    }

    /**
     * Containment predicate of [canonicalResolve]: a canonicalised path is
     * in-bounds when it is the root itself or sits under it — [PathContainment]'s
     * test, whose trailing [File.separator] stops a sibling directory such as
     * `agent_workspace_evil` from passing.
     *
     * @param canonical An already-canonicalised candidate path.
     * @param root The canonicalised workspace root.
     */
    private fun isInsideRoot(canonical: File, root: File): Boolean = PathContainment.isSelfOrInside(canonical, root)

    /**
     * Reads an already-resolved (in-bounds) [target] as UTF-8 text, enforcing
     * existence, the per-file size cap and text-ness.
     */
    private suspend fun readTextResolved(target: File): WorkspaceResult<String> = when {
        !target.isFile -> WorkspaceResult.Failure(WorkspaceError.NotFound)
        target.length() > maxFileSizeBytes() -> WorkspaceResult.Failure(WorkspaceError.TooLarge)
        else -> {
            val bytes = target.readBytes()
            if (isUtf8Text(bytes)) {
                WorkspaceResult.Success(bytes.toString(Charsets.UTF_8))
            } else {
                WorkspaceResult.Failure(WorkspaceError.NotAText)
            }
        }
    }

    /**
     * Performs the quota-checked write of an already-resolved (in-bounds)
     * [target]. Must be called while holding [mutex].
     */
    private suspend fun writeTextLocked(
        target: File,
        content: String,
        overwrite: Boolean,
    ): WorkspaceResult<WorkspaceFile> = writeBytesLocked(target, content.toByteArray(Charsets.UTF_8), overwrite)

    /**
     * Performs the quota-checked atomic write of [newBytes] to an already-resolved
     * (in-bounds) [target]. Shared by [writeTextLocked] (UTF-8 text) and
     * [importBytes] (verbatim bytes). Must be called while holding [mutex].
     */
    private suspend fun writeBytesLocked(
        target: File,
        newBytes: ByteArray,
        overwrite: Boolean,
    ): WorkspaceResult<WorkspaceFile> {
        val root = rootDir()
        val exists = target.isFile
        writeRefusal(target, root, exists, overwrite, newBytes.size.toLong())
            ?.let { return WorkspaceResult.Failure(it) }

        val tally = tallyLocked()
        val newEntries = if (exists) 0 else 1 + WorkspaceTree.missingDirectoryCount(target, root)
        val existingSize = if (exists) target.length() else 0L
        val projectedBytes = tally.bytes - existingSize + newBytes.size
        val overQuota = tally.entries + newEntries > maxEntries ||
            projectedBytes > settingsRepository.workspaceMaxTotalBytes.first()
        if (overQuota) return WorkspaceResult.Failure(WorkspaceError.QuotaExceeded)

        // Invalidate the cache before the risky write: if the write throws partway
        // (disk full, parent turned into a file) the next read recomputes from disk —
        // removing any directory the failed write created — instead of trusting a
        // stale count. The cache is set only on full success.
        cachedTally = null
        target.parentFile?.mkdirs()
        writeAtomically(target, newBytes)
        cachedTally = Tally(bytes = projectedBytes, entries = tally.entries + newEntries)
        return WorkspaceResult.Success(toWorkspaceFile(target, root))
    }

    /**
     * Decides whether a write of [size] bytes to [target] is refused before the
     * quota is consulted, and why.
     *
     * @param target The resolved (in-bounds) destination.
     * @param root The canonical workspace root.
     * @param exists Whether [target] is an existing regular file.
     * @param overwrite Whether the caller allows replacing an existing file.
     * @param size The size of the new content, in bytes.
     * @return The refusal, or `null` when the write may proceed to the quota check.
     */
    private suspend fun writeRefusal(
        target: File,
        root: File,
        exists: Boolean,
        overwrite: Boolean,
        size: Long,
    ): WorkspaceError? = when {
        // A directory can never be replaced by a file, even with `overwrite`. Report it
        // distinctly so the tool does not hand back a "retry with overwrite" hint that
        // would loop forever (the overwrite flag is checked only for an existing file).
        target.isDirectory -> WorkspaceError.IsDirectory
        exists && !overwrite -> WorkspaceError.AlreadyExists
        // A new entry must have a name the workspace may create. An existing file is
        // replaced in place, whatever its name, so one made before the rules stays usable.
        !exists && WorkspaceNamePolicy.violationOf(WorkspaceTree.relativePath(target, root)) != null ->
            WorkspaceError.InvalidPath
        // A directory level that is a file (`notes.md/x.txt` over `notes.md`) is a
        // path the filesystem rejects with ENOTDIR — refused here, typed, instead of
        // the write throwing on its scratch file.
        !exists && WorkspaceTree.runsThroughFile(target, root) -> WorkspaceError.InvalidPath
        size > maxFileSizeBytes() -> WorkspaceError.TooLarge
        else -> null
    }

    /**
     * Writes [bytes] to [target] atomically: the content is staged into a
     * sibling scratch file and then [renamed][File.renameTo] onto [target].
     * Because both paths sit in the same directory (one filesystem), the rename
     * is a single atomic replace — a crash at any point leaves [target] either
     * fully old or fully new, never half-written, and the `finally` clause drops
     * any partial scratch file so no garbage is left behind.
     *
     * @param target The destination file (may or may not already exist).
     * @param bytes The full content to write.
     * @throws IOException When staging or the rename fails.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val scratch = File(target.parentFile, target.name + RESERVED_TMP_SUFFIX)
        try {
            scratch.writeBytes(bytes)
            if (!scratch.renameTo(target)) {
                throw IOException("Atomic rename failed for ${target.path}")
            }
        } finally {
            // On success the scratch no longer exists (it became [target]); on any
            // failure this removes the partial stage so a crash never leaves a stray file.
            if (scratch.exists()) scratch.delete()
        }
    }

    /**
     * Performs the read-existing → concatenate → atomic-rewrite append of an
     * already-resolved (in-bounds) [target]. Must be called while holding [mutex]
     * so the read and the rewrite cannot be interleaved with another mutation
     * (two concurrent appends would otherwise lose an entry). A missing file is
     * treated as empty existing content, so the first append creates the file;
     * the rewrite funnels through [writeTextLocked] and is therefore quota-checked.
     */
    private suspend fun appendTextLocked(target: File, content: String): WorkspaceResult<WorkspaceFile> {
        val existing = if (target.isFile) {
            when (val read = readTextResolved(target)) {
                is WorkspaceResult.Failure -> return read
                is WorkspaceResult.Success -> read.value
            }
        } else {
            ""
        }
        return writeTextLocked(target, existing + content, overwrite = true)
    }

    /**
     * Performs the anchored find-replace edit of an already-resolved (in-bounds)
     * [target]. Must be called while holding [mutex] so the read and the rewrite
     * cannot be interleaved with another mutation.
     */
    private suspend fun editTextLocked(
        target: File,
        oldText: String,
        newText: String,
    ): WorkspaceResult<WorkspaceFile> {
        val content = when (val read = readTextResolved(target)) {
            is WorkspaceResult.Failure -> return read
            is WorkspaceResult.Success -> read.value
        }
        return when (val outcome = WorkspaceTextEdit.apply(content, oldText, newText)) {
            WorkspaceTextEdit.Outcome.AnchorNotFound -> WorkspaceResult.Failure(WorkspaceError.AnchorNotFound)
            is WorkspaceTextEdit.Outcome.AnchorNotUnique ->
                WorkspaceResult.Failure(WorkspaceError.AnchorNotUnique(outcome.count))
            // Re-route through the quota-checked atomic write so an edit cannot
            // grow the workspace past its limits and is applied in one replace.
            is WorkspaceTextEdit.Outcome.Replaced ->
                writeTextLocked(target, outcome.newContent, overwrite = true)
        }
    }

    /**
     * Deletes an already-resolved (in-bounds) [target] if it is a regular file,
     * then the directories above it that the delete left empty, keeping the cached
     * counts in step. Must be called while holding [mutex].
     */
    private fun deleteLocked(target: File): WorkspaceResult<Unit> {
        if (!target.isFile) return WorkspaceResult.Failure(WorkspaceError.NotFound)
        val size = target.length()
        val previous = tallyLocked()
        // Invalidate before the mutation: if delete reports failure the next read
        // recomputes from disk rather than trusting a counter that may be wrong.
        cachedTally = null
        if (!target.delete() || target.exists()) {
            return WorkspaceResult.Failure(WorkspaceError.NotFound)
        }
        val root = rootDir()
        val removedDirectories = WorkspaceTree.removeEmptiedAncestors(target, root)
        cachedTally = Tally(bytes = previous.bytes - size, entries = previous.entries - 1 - removedDirectories)
        // "Delete" means every copy too: a share staged earlier must not keep the
        // file's content after the file itself is gone.
        shareCopies.discard(WorkspaceTree.relativePath(target, root))
        return WorkspaceResult.Success(Unit)
    }

    /** Returns the per-file size ceiling from settings. */
    private suspend fun maxFileSizeBytes(): Long = settingsRepository.workspaceMaxFileSizeBytes.first()

    /**
     * Returns what the workspace holds, using the cached value when present and
     * otherwise measuring the tree — which also removes the empty directories it
     * finds ([WorkspaceTree.measureAndPrune]). Must be called while holding [mutex].
     */
    private fun tallyLocked(): Tally =
        cachedTally ?: WorkspaceTree.measureAndPrune(rootDir(), ::isScratchFile).also { cachedTally = it }

    /**
     * Reports whether [file] is a transient atomic-write scratch file. Such a
     * file is excluded from listings and quota accounting because it only ever
     * exists for the brief window between staging and rename (or as the residue
     * of a crashed write, which the next write cleans up).
     */
    private fun isScratchFile(file: File): Boolean = file.name.endsWith(RESERVED_TMP_SUFFIX)

    /** Locates the workspace root, creating it on first use, and canonicalises it. */
    private fun rootDir(): File {
        val dir = File(context.filesDir, WORKSPACE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir.canonicalFile
    }

    /** Maps a canonical workspace [File] to its [WorkspaceFile] metadata snapshot. */
    private fun toWorkspaceFile(file: File, root: File = rootDir()): WorkspaceFile {
        val relativePath = WorkspaceTree.relativePath(file, root)
        val isFile = file.isFile
        return WorkspaceFile(
            relativePath = relativePath,
            sizeBytes = if (isFile) file.length() else 0L,
            lastModified = file.lastModified(),
            isDirectory = file.isDirectory,
            isText = isFile && looksLikeText(file),
        )
    }

    /**
     * Reports whether [file] is text by sniffing only a bounded prefix — listing
     * and resolving must not pull whole files into memory just to set the
     * advisory `isText` flag (a full UTF-8 validation still happens in [readText],
     * which is the authoritative path). If the sniff buffer fills, the last few
     * bytes are dropped so a multi-byte UTF-8 sequence straddling the boundary is
     * not misread as malformed.
     */
    private fun looksLikeText(file: File): Boolean = try {
        file.inputStream().use { input ->
            // Use readNBytes, not read(buffer): a single read() may return fewer bytes
            // than requested (a short read) even when more exist. readNBytes fills the
            // window up to the file's end deterministically.
            val sniff = input.readNBytes(TEXT_SNIFF_BYTES)
            prefixLooksLikeUtf8(sniff, windowMayBeTruncated = sniff.size == TEXT_SNIFF_BYTES)
        }
    } catch (e: IOException) {
        // An unreadable file is treated as non-text rather than crashing a listing.
        Timber.w(e, "Failed to sample file for text detection: %s", file.path)
        false
    }

    /**
     * Heuristic UTF-8 text check over a sniffed [prefix]: empty counts as text, a NUL
     * byte marks it binary, otherwise the prefix must decode as strict UTF-8.
     *
     * The full prefix is decoded **first**, so a window that ends exactly on a
     * character boundary is never misjudged. Only when the sniff window may have cut a
     * trailing multi-byte character ([windowMayBeTruncated], i.e. the file is longer
     * than the window) are up to [MAX_UTF8_TAIL_BYTES] trailing bytes tolerated — an
     * incomplete final character must not flag otherwise-valid non-ASCII text (e.g.
     * Cyrillic Markdown/CSV) as binary.
     */
    private fun prefixLooksLikeUtf8(prefix: ByteArray, windowMayBeTruncated: Boolean): Boolean {
        if (prefix.isEmpty()) return true
        if (prefix.any { it == 0.toByte() }) return false
        val maxTrim = if (windowMayBeTruncated) MAX_UTF8_TAIL_BYTES else 0
        return (0..maxTrim).any { trim -> decodeUtf8OrNull(prefix, prefix.size - trim) != null }
    }

    /**
     * Full-content UTF-8 text check (the authoritative path for [readText]): empty
     * content counts as text, a NUL byte marks it binary, and otherwise the whole
     * buffer must decode strictly with no tolerance for a truncated tail.
     */
    private fun isUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.any { it == 0.toByte() }) return false
        return decodeUtf8OrNull(bytes, bytes.size) != null
    }

    /**
     * Strictly decodes the first [length] bytes of [bytes] as UTF-8, returning the
     * decoded text, or `null` if it contains a malformed or unmappable sequence.
     */
    private fun decodeUtf8OrNull(bytes: ByteArray, length: Int): String? {
        if (length <= 0) return ""
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    internal companion object {
        /**
         * How many entries — files and directories together — the workspace may
         * hold. The byte quotas cannot see an entry's own cost: a directory counts
         * zero bytes and a one-byte file still occupies a filesystem block, so
         * without a count a write loop of tiny files or nested directories is
         * unbounded. At a 4 KB block this bounds what the byte quota does not see
         * to about 40 MB.
         */
        private const val DEFAULT_MAX_ENTRIES: Int = 10_000

        /**
         * Name of the workspace directory inside [Context.filesDir]. Internal so the
         * storage-inventory guard can check the backup rules exclude it by this name.
         */
        internal const val WORKSPACE_DIR_NAME = "agent_workspace"

        /** The NUL character, which no filesystem name can hold. */
        private val NUL: Char = Char(0)

        /**
         * Suffix of the sibling scratch file used to stage an atomic write before
         * the rename. Reserved: files ending in it are hidden from listings and
         * quota accounting, so the agent is never expected to create one itself.
         */
        private const val RESERVED_TMP_SUFFIX = ".knotwork-tmp"

        /** Number of leading bytes sampled to classify a file as text or binary. */
        private const val TEXT_SNIFF_BYTES = 8 * 1024

        /**
         * Maximum length of a UTF-8 code point. Dropped from the tail of a filled
         * sniff buffer so a sequence split across the boundary is not misjudged.
         */
        private const val MAX_UTF8_TAIL_BYTES = 3
    }
}
