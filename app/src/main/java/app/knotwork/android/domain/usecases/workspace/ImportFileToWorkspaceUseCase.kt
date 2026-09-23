package app.knotwork.android.domain.usecases.workspace

import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.WorkspaceNamePolicy
import java.io.InputStream
import javax.inject.Inject

/**
 * Imports an external document (chosen via the system file picker) into the
 * workspace root so the agent can read it.
 *
 * The picked name is reduced to a bare basename — imports always land at the
 * workspace root, never in a sub-directory the picker's display name might
 * imply — with control characters replaced, and a blank name falls back to
 * [DEFAULT_NAME]. The actual byte copy, quota and size checks are delegated to
 * [AgentWorkspace.importBytes]; this use case adds the name-collision policy the
 * UI needs:
 *
 *  - [ImportMode.CreateOrFail] — the default first attempt. If the name is free
 *    the file is imported; if it is taken the result is
 *    [app.knotwork.android.domain.models.WorkspaceError.AlreadyExists], which the
 *    ViewModel turns into the "name already exists" dialog.
 *  - [ImportMode.Overwrite] — replace the existing file (the dialog's "Replace").
 *  - [ImportMode.KeepBoth] — import under the next free `name (N).ext` variant
 *    (the dialog's "Keep both"), computed against the current listing.
 *
 * @property workspace The agent's jailed file sandbox.
 */
class ImportFileToWorkspaceUseCase @Inject constructor(private val workspace: AgentWorkspace) {
    /**
     * Imports [source] into the workspace under [suggestedName].
     *
     * @param suggestedName The picker's display name; only its basename is used.
     * @param source The byte stream to import. The caller owns and closes it.
     * @param mode The name-collision policy (see class KDoc).
     * @return [WorkspaceResult.Success] with the resulting [WorkspaceFile], or a
     *   typed failure (`AlreadyExists`, `TooLarge`, `QuotaExceeded`, …).
     */
    suspend operator fun invoke(
        suggestedName: String,
        source: InputStream,
        mode: ImportMode,
    ): WorkspaceResult<WorkspaceFile> {
        val baseName = sanitize(suggestedName)
        return when (mode) {
            ImportMode.CreateOrFail -> workspace.importBytes(baseName, source, overwrite = false)
            ImportMode.Overwrite -> workspace.importBytes(baseName, source, overwrite = true)
            ImportMode.KeepBoth -> {
                val target = when (val listed = workspace.list()) {
                    is WorkspaceResult.Failure -> return listed
                    is WorkspaceResult.Success -> freeName(baseName, listed.value.map { it.relativePath }.toSet())
                }
                workspace.importBytes(target, source, overwrite = false)
            }
        }
    }

    /** Pure helpers shared by the import execution path and the UI's collision preview. */
    companion object {
        /** Fallback name when the picker yields a blank display name. */
        const val DEFAULT_NAME: String = "imported-file"

        /**
         * Reduces a picker display name to a safe, root-level basename: the part after
         * the last separator, trimmed, with every character [WorkspaceNamePolicy]
         * forbids replaced by [WorkspaceNamePolicy.REPLACEMENT]. The display name is
         * whatever the source app's provider reports, and a line break kept in it would
         * forge an entry in the agent's file listing.
         *
         * @param name The picker's display name.
         * @return A basename the workspace accepts as far as characters go (its length
         *   is still checked by the workspace), or [DEFAULT_NAME] when nothing is left.
         */
        fun sanitize(name: String): String {
            val basename = name.substringAfterLast('/').substringAfterLast('\\').trim()
            return WorkspaceNamePolicy.replaceForbidden(basename).ifEmpty { DEFAULT_NAME }
        }

        /**
         * Returns the first `name (N).ext` variant of [baseName] not present in
         * [existing], starting at `(1)`. Returns [baseName] unchanged when it is
         * already free. Shared by the [ImportMode.KeepBoth] execution path and the
         * UI's "Keep both" preview so the two can never disagree.
         */
        fun freeName(baseName: String, existing: Set<String>): String {
            if (baseName !in existing) return baseName
            val dot = baseName.lastIndexOf('.')
            // Treat a leading dot (".gitignore") as having no extension, so the suffix
            // is appended to the whole name rather than splitting the dotfile.
            val hasExt = dot > 0
            val stem = if (hasExt) baseName.substring(0, dot) else baseName
            val ext = if (hasExt) baseName.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$stem ($n)$ext"
                if (candidate !in existing) return candidate
                n++
            }
        }
    }
}

/** Name-collision policy for [ImportFileToWorkspaceUseCase]. */
enum class ImportMode {
    /** Import only if the name is free; otherwise fail with `AlreadyExists`. */
    CreateOrFail,

    /** Replace the existing file at the name. */
    Overwrite,

    /** Import under the next free `name (N).ext` variant. */
    KeepBoth,
}
