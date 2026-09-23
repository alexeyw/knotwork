package app.knotwork.android.domain.usecases.workspace

import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.services.AgentWorkspace
import javax.inject.Inject

/**
 * Stages a copy of a workspace file for the system share sheet — the Files
 * screen's **Share** action.
 *
 * The receiving app is handed the copy, never the workspace directory, and the
 * copy's lifetime belongs to the workspace ([AgentWorkspace.stageForShare]):
 * deleting the file deletes its copies, and a newer share does not remove an
 * older share's copy while its receiver may still be reading it.
 *
 * @property workspace The agent's jailed file sandbox.
 */
class StageWorkspaceFileForShareUseCase @Inject constructor(private val workspace: AgentWorkspace) {
    /**
     * Stages the file at [relativePath].
     *
     * @param relativePath Path of the file to share, relative to the workspace root.
     * @return [WorkspaceResult.Success] with the copy's absolute path, or a typed
     *   failure (missing file, or a path that escapes the sandbox).
     */
    suspend operator fun invoke(relativePath: String): WorkspaceResult<String> = workspace.stageForShare(relativePath)
}
