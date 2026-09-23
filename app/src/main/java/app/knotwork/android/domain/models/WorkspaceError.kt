package app.knotwork.android.domain.models

/**
 * Typed failure modes of an [app.knotwork.android.domain.services.AgentWorkspace]
 * operation.
 *
 * The workspace is the jailed file sandbox the agent operates inside. Every
 * operation that can be refused returns one of these cases instead of throwing,
 * so the file tools layered on top (read / write / list, added in later tasks)
 * can map each cause to a precise, LLM-readable observation message without
 * inspecting exception types.
 */
sealed class WorkspaceError {
    /**
     * The requested relative path canonicalises to a location outside the
     * workspace root (e.g. `../../shared_prefs/secure_api_keys.xml`, an absolute
     * path, or a symlink escaping the sandbox). This is the security boundary:
     * such a request is refused before any I/O touches the resolved target.
     */
    data object PathOutsideWorkspace : WorkspaceError()

    /** No file exists at the resolved (in-bounds) path. */
    data object NotFound : WorkspaceError()

    /**
     * A write was attempted at a path that already holds a file while
     * `overwrite` was `false`. Overwrites must be explicit so the agent cannot
     * silently clobber existing content.
     */
    data object AlreadyExists : WorkspaceError()

    /**
     * The write would push the workspace's total occupied size past
     * [app.knotwork.android.domain.repositories.SettingsRepository.workspaceMaxTotalBytes].
     */
    data object QuotaExceeded : WorkspaceError()

    /**
     * A text read was attempted on a file whose bytes are not valid UTF-8 text
     * (binary content). The file remains visible in listings; only its textual
     * read is refused.
     */
    data object NotAText : WorkspaceError()

    /**
     * A single file's size exceeds
     * [app.knotwork.android.domain.repositories.SettingsRepository.workspaceMaxFileSizeBytes],
     * either on write (the new content is too large) or on read (the on-disk
     * file is too large to pull into memory wholesale).
     */
    data object TooLarge : WorkspaceError()

    /**
     * An [app.knotwork.android.domain.services.AgentWorkspace.editText] anchor
     * (`oldText`) does not occur in the target file. An edit is refused unless
     * its anchor addresses an existing fragment, so the agent cannot blindly
     * mutate a file whose content does not match its expectation.
     */
    data object AnchorNotFound : WorkspaceError()

    /**
     * An [app.knotwork.android.domain.services.AgentWorkspace.editText] anchor
     * (`oldText`) occurs more than once in the target file, so the edit target
     * is ambiguous. The caller is told the [count] so it can supply a longer,
     * uniquely-matching anchor.
     *
     * @property count The number of non-overlapping occurrences found.
     */
    data class AnchorNotUnique(val count: Int) : WorkspaceError()

    /**
     * A write (or edit) was attempted at a path that resolves to a directory.
     * Distinct from [AlreadyExists] on purpose: a directory cannot be replaced
     * by a file even with `overwrite`, so surfacing it as [AlreadyExists] would
     * tempt the caller to retry with the overwrite flag forever. The file tool
     * maps this to an error that carries no "retry with overwrite" hint.
     */
    data object IsDirectory : WorkspaceError()

    /**
     * The path is inside the workspace but is not one the workspace will accept:
     * it contains a NUL byte or a path the filesystem itself rejects (for any
     * operation), or — when the operation would **create** a new entry — it breaks
     * a rule of [app.knotwork.android.domain.services.WorkspaceNamePolicy] (a
     * control character or line break, an over-long name or path).
     *
     * Distinct from [PathOutsideWorkspace] on purpose: the path does not escape,
     * so telling the caller it is "outside the workspace" would be false and
     * would not say what to change.
     */
    data object InvalidPath : WorkspaceError()
}
