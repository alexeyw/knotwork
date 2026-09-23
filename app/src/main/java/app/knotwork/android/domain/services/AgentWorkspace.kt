package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.models.WorkspaceTextPreview
import app.knotwork.android.domain.models.WorkspaceUsage
import java.io.InputStream
import java.io.OutputStream

/**
 * The agent's jailed file sandbox: a single, size-bounded directory the agent
 * may read from and write to, and from which it can never escape.
 *
 * This is the foundation every file tool builds on. The agent has no general
 * filesystem access — only this contract — so the entire trust boundary for
 * agent-driven file I/O is enforced here:
 *
 *  - **Containment.** Every path is resolved through [resolve], the single
 *    canonicalisation gate. A relative path that canonicalises outside the
 *    workspace root (`../` traversal, an absolute path, a symlink escaping the
 *    sandbox) is refused with [WorkspaceError.PathOutsideWorkspace] before any
 *    I/O reaches the target. A path the filesystem cannot take (a NUL byte, or
 *    one it rejects), and a new entry whose path breaks [WorkspaceNamePolicy],
 *    is refused with [WorkspaceError.InvalidPath].
 *  - **Quotas.** Writes are checked against a per-file size limit
 *    ([WorkspaceError.TooLarge]), a workspace-wide total-size limit and a ceiling
 *    on the number of files and directories (both [WorkspaceError.QuotaExceeded])
 *    so a looping pipeline cannot exhaust device storage. A directory never
 *    outlives its contents.
 *  - **Text-only surface (for now).** Only UTF-8 text is read and written.
 *    Binary files remain visible in [list] but cannot be text-read
 *    ([WorkspaceError.NotAText]).
 *
 * All operations are `suspend` (the implementation performs blocking I/O on a
 * background dispatcher) and never throw for an expected, refusable condition:
 * they return a [WorkspaceResult.Failure] carrying a typed [WorkspaceError].
 *
 * Implementations are expected to be safe for concurrent use.
 */
interface AgentWorkspace {
    /**
     * Resolves a workspace-relative path to its metadata, enforcing the
     * containment boundary.
     *
     * This is the **single point of path validation**: every other method
     * funnels through the same canonicalisation. The target need not exist —
     * resolving a not-yet-created path (e.g. a future write target) succeeds as
     * long as it stays inside the workspace; a non-existent in-bounds path is
     * reported with [WorkspaceFile.sizeBytes] `0` and [WorkspaceFile.lastModified]
     * `0`.
     *
     * @param relativePath Path relative to the workspace root. A leading slash,
     *   an absolute path, or any `../` sequence that escapes the root after
     *   canonicalisation is refused.
     * @return [WorkspaceResult.Success] with the resolved [WorkspaceFile], or
     *   [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace].
     */
    suspend fun resolve(relativePath: String): WorkspaceResult<WorkspaceFile>

    /**
     * Reads a workspace file as UTF-8 text.
     *
     * @param relativePath Path of the file to read, relative to the workspace
     *   root.
     * @return [WorkspaceResult.Success] with the file's full text, or
     *   [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace]
     *   (escapes the sandbox), [WorkspaceError.NotFound] (no such file),
     *   [WorkspaceError.NotAText] (binary content) or [WorkspaceError.TooLarge]
     *   (exceeds the per-file size limit).
     */
    suspend fun readText(relativePath: String): WorkspaceResult<String>

    /**
     * Writes UTF-8 [content] to a workspace file, creating parent directories as
     * needed.
     *
     * Quotas are checked **before** any bytes are written: the new content must
     * fit the per-file size limit, and the resulting workspace total (accounting
     * for the size of any file being replaced) must fit the workspace-wide
     * limit.
     *
     * @param relativePath Path of the file to write, relative to the workspace
     *   root.
     * @param content Text to write, encoded as UTF-8.
     * @param overwrite When `false` (the default), writing over an existing file
     *   is refused with [WorkspaceError.AlreadyExists]; when `true`, the existing
     *   file is replaced.
     * @return [WorkspaceResult.Success] with the resulting [WorkspaceFile]
     *   metadata, or [WorkspaceResult.Failure] with
     *   [WorkspaceError.PathOutsideWorkspace], [WorkspaceError.InvalidPath] (a
     *   new file's path breaks [WorkspaceNamePolicy]),
     *   [WorkspaceError.AlreadyExists] (a file is already there and `overwrite`
     *   is `false`), [WorkspaceError.IsDirectory] (the path is a directory),
     *   [WorkspaceError.TooLarge] or [WorkspaceError.QuotaExceeded] (the bytes or
     *   the entries would exceed their ceiling).
     */
    suspend fun writeText(
        relativePath: String,
        content: String,
        overwrite: Boolean = false,
    ): WorkspaceResult<WorkspaceFile>

    /**
     * Appends UTF-8 [content] to the end of a workspace file, creating it (and any
     * parent directories) when it does not yet exist.
     *
     * The read-existing → concatenate → write is performed atomically under the
     * workspace's own lock, so concurrent appends can never interleave or lose an
     * entry, and the rewritten content is subject to the same per-file and total-size
     * quotas as [writeText]. Unlike [writeText] there is no `overwrite` flag: appending
     * is always additive and never refuses on an existing file — exactly the
     * "accumulate into a daily log / report" shape callers need.
     *
     * @param relativePath Path of the file to append to, relative to the workspace
     *   root.
     * @param content Text to append, encoded as UTF-8.
     * @return [WorkspaceResult.Success] with the resulting [WorkspaceFile] metadata,
     *   or [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace],
     *   [WorkspaceError.InvalidPath] (a new file's path breaks [WorkspaceNamePolicy]),
     *   [WorkspaceError.IsDirectory] (the path is a directory),
     *   [WorkspaceError.NotAText] (existing content is binary),
     *   [WorkspaceError.TooLarge] or [WorkspaceError.QuotaExceeded].
     */
    suspend fun appendText(relativePath: String, content: String): WorkspaceResult<WorkspaceFile>

    /**
     * Replaces the single occurrence of [oldText] with [newText] in an existing
     * workspace file, anchoring the edit on a uniquely-matching fragment.
     *
     * The whole read-modify-write is performed atomically under the workspace's
     * own lock so a concurrent operation can never observe (or interleave with)
     * a half-applied edit. The anchor must address exactly one fragment:
     *
     *  - It must occur **exactly once** in the file — zero matches is
     *    [WorkspaceError.AnchorNotFound], more than one is
     *    [WorkspaceError.AnchorNotUnique] (carrying the count so the caller can
     *    request a longer anchor). Matching is literal and non-overlapping.
     *  - An empty [newText] deletes the matched fragment.
     *
     * The rewritten content is subject to the same per-file and total-size
     * quotas as [writeText].
     *
     * @param relativePath Path of the file to edit, relative to the workspace
     *   root.
     * @param oldText The unique anchor fragment to replace. Must be non-empty.
     * @param newText The replacement text; may be empty to delete the fragment.
     * @return [WorkspaceResult.Success] with the resulting [WorkspaceFile]
     *   metadata, or [WorkspaceResult.Failure] with
     *   [WorkspaceError.PathOutsideWorkspace], [WorkspaceError.NotFound],
     *   [WorkspaceError.NotAText], [WorkspaceError.AnchorNotFound],
     *   [WorkspaceError.AnchorNotUnique], [WorkspaceError.TooLarge] or
     *   [WorkspaceError.QuotaExceeded].
     */
    suspend fun editText(relativePath: String, oldText: String, newText: String): WorkspaceResult<WorkspaceFile>

    /**
     * Deletes a regular file from the workspace.
     *
     * Deletion is irreversible, so this is the workspace's most destructive
     * operation; the file tool layered on top routes it through the strictest
     * Human-in-the-Loop confirmation path. Only regular files are deletable: a
     * path that resolves to a directory (or to nothing) is reported as
     * [WorkspaceError.NotFound], never silently traversed. The directories above
     * the file that the delete leaves empty are removed with it, and so is every
     * copy of the file [stageForShare] made.
     *
     * @param relativePath Path of the file to delete, relative to the workspace
     *   root.
     * @return [WorkspaceResult.Success] with [Unit] when the file was removed,
     *   or [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace]
     *   (escapes the sandbox) or [WorkspaceError.NotFound] (no such file).
     */
    suspend fun delete(relativePath: String): WorkspaceResult<Unit>

    /**
     * Lists every file in the workspace, recursively.
     *
     * Returns a stable, path-sorted list of [WorkspaceFile] entries for the
     * regular files in the tree (directories are traversed but not emitted as
     * entries; a symbolic link is never followed). Binary files are included with
     * [WorkspaceFile.isText] `false`.
     * An empty (or not-yet-created) workspace yields an empty list.
     *
     * @return [WorkspaceResult.Success] with the listing. Listing the root never
     *   fails the containment check, so a [WorkspaceResult.Failure] only arises
     *   from an unexpected I/O fault.
     */
    suspend fun list(): WorkspaceResult<List<WorkspaceFile>>

    /**
     * Reports how much of the workspace's storage budget is currently consumed.
     *
     * This is the read-side view of the same total-size ceiling that [writeText]
     * and [importBytes] enforce, surfaced so a UI can render a quota indicator.
     *
     * @return [WorkspaceResult.Success] with the [WorkspaceUsage] snapshot.
     *   Computing usage never crosses the containment boundary, so a
     *   [WorkspaceResult.Failure] only arises from an unexpected I/O fault.
     */
    suspend fun usage(): WorkspaceResult<WorkspaceUsage>

    /**
     * Reads a bounded leading slice of a workspace text file for on-screen
     * preview.
     *
     * Unlike [readText], this never fails with [WorkspaceError.TooLarge]: a file
     * larger than [maxBytes] is returned truncated (cut at a UTF-8 character
     * boundary) with [WorkspaceTextPreview.truncated] set, so the UI can always
     * show a leading view of any text file. Binary content is still refused with
     * [WorkspaceError.NotAText].
     *
     * @param relativePath Path of the file to preview, relative to the workspace
     *   root.
     * @param maxBytes Maximum number of leading bytes to read. Must be positive.
     * @return [WorkspaceResult.Success] with the [WorkspaceTextPreview], or
     *   [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace],
     *   [WorkspaceError.NotFound] or [WorkspaceError.NotAText].
     */
    suspend fun readTextPreview(relativePath: String, maxBytes: Int): WorkspaceResult<WorkspaceTextPreview>

    /**
     * Imports an external document into the workspace by streaming [source] to
     * the file at [relativePath].
     *
     * This is the binary-faithful counterpart of [writeText]: it copies the raw
     * bytes verbatim (so a non-text file the user wants to hand the agent is not
     * corrupted) while enforcing exactly the same guarantees — containment, the
     * per-file size limit ([WorkspaceError.TooLarge]), the workspace-wide quota
     * ([WorkspaceError.QuotaExceeded]), and an explicit [overwrite] flag
     * ([WorkspaceError.AlreadyExists]). The write is atomic. The caller owns
     * [source] and is responsible for closing it.
     *
     * @param relativePath Destination path, relative to the workspace root.
     * @param source The byte stream to import; read fully but not closed here.
     * @param overwrite When `false` (the default), writing over an existing file
     *   is refused with [WorkspaceError.AlreadyExists]; when `true`, it is
     *   replaced.
     * @return [WorkspaceResult.Success] with the resulting [WorkspaceFile]
     *   metadata, or [WorkspaceResult.Failure] with
     *   [WorkspaceError.PathOutsideWorkspace], [WorkspaceError.InvalidPath],
     *   [WorkspaceError.AlreadyExists], [WorkspaceError.IsDirectory],
     *   [WorkspaceError.TooLarge] or [WorkspaceError.QuotaExceeded].
     */
    suspend fun importBytes(
        relativePath: String,
        source: InputStream,
        overwrite: Boolean = false,
    ): WorkspaceResult<WorkspaceFile>

    /**
     * Exports a workspace file by streaming its full contents to [sink].
     *
     * Unlike [readText], this carries no per-file size cap and no text
     * constraint: a binary file or one larger than the read limit can still be
     * exported, because the bytes flow straight to the caller's stream rather
     * than into memory and the local model's context. The caller owns [sink] and
     * is responsible for closing it.
     *
     * @param relativePath Path of the file to export, relative to the workspace
     *   root.
     * @param sink The destination stream to write the file's bytes to; written
     *   but not closed here.
     * @return [WorkspaceResult.Success] with [Unit] when the file was streamed
     *   out, or [WorkspaceResult.Failure] with
     *   [WorkspaceError.PathOutsideWorkspace] or [WorkspaceError.NotFound].
     */
    suspend fun exportTo(relativePath: String, sink: OutputStream): WorkspaceResult<Unit>

    /**
     * Stages a copy of the file at [relativePath] for the system share sheet and
     * returns where the copy is.
     *
     * The share sheet is handed the copy, never the workspace. The copy is the
     * workspace's to clean up: [delete] removes every copy of the file it deletes,
     * and a copy older than an hour is removed by the next staging (and by the
     * daily maintenance pass) — never sooner, so staging one share does not take
     * another share's copy from an app still reading it.
     *
     * @param relativePath Path of the file to share, relative to the workspace root.
     * @return [WorkspaceResult.Success] with the copy's absolute path, or
     *   [WorkspaceResult.Failure] with [WorkspaceError.PathOutsideWorkspace] or
     *   [WorkspaceError.NotFound].
     */
    suspend fun stageForShare(relativePath: String): WorkspaceResult<String>
}
