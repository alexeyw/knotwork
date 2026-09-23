package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.services.WorkspaceNamePolicy

/**
 * Observation text shared by the workspace file tools where the wording must not
 * drift between them.
 */
internal object WorkspaceToolMessages {

    /**
     * The observation for [app.knotwork.android.domain.models.WorkspaceError.InvalidPath].
     * It names the rules rather than echoing the path back: the refused path may
     * itself carry the control characters the rule is about, and repeating them
     * would put them into the transcript.
     */
    val INVALID_PATH: String =
        "Error: the path is not a valid workspace path. A name may not contain control characters or " +
            "line breaks, one name is limited to ${WorkspaceNamePolicy.MAX_NAME_BYTES} bytes and the whole " +
            "path to ${WorkspaceNamePolicy.MAX_PATH_BYTES} bytes (UTF-8)."
}
