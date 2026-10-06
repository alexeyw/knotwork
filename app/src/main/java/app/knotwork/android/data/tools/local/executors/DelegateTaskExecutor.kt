package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.data.tools.local.DelegateTaskTool
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.LocalToolExecutor
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] implementation for the built-in `delegate_task` tool.
 *
 * Parses the JSON arguments (`taskDescription`, optional `targetModel`) and delegates
 * to [DelegateTaskTool], which forwards the prompt to the configured cloud provider
 * and returns the provider's whole response as the tool result.
 */
class DelegateTaskExecutor @Inject constructor(private val delegateTaskTool: DelegateTaskTool) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val taskDescription = json.getString("taskDescription")
        val targetModel = if (json.has("targetModel")) json.getString("targetModel") else CloudProvider.ANTHROPIC.id
        return delegateTaskTool.executeDelegation(taskDescription, targetModel)
    }

    companion object {
        const val TOOL_NAME = "delegate_task"
    }
}
