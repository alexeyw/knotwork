package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.data.tools.local.DelegateTaskTool
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.configuredProviders
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] implementation for the built-in `delegate_task` tool.
 *
 * Parses the JSON arguments (`taskDescription`, optional `targetModel`) and delegates
 * to [DelegateTaskTool], which forwards the prompt to the configured cloud provider
 * and returns the provider's whole response as the tool result.
 *
 * When the model names no `targetModel`, the task goes to the first provider set up on
 * the device ([configuredProviders]) — the default the tool's schema names to the model.
 *
 * @property delegateTaskTool Sends the task to the provider.
 * @property apiKeyRepository Which providers are set up, for the default.
 */
class DelegateTaskExecutor @Inject constructor(
    private val delegateTaskTool: DelegateTaskTool,
    private val apiKeyRepository: ApiKeyRepository,
) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val taskDescription = json.getString("taskDescription")
        // An explicit `null` counts as omitted: `getString` would hand on the text "null".
        val named = json.optString("targetModel").takeUnless { json.isNull("targetModel") || it.isBlank() }
        val targetModel = named
            ?: apiKeyRepository.configuredProviders().firstOrNull()?.id
            ?: return NO_PROVIDER
        return delegateTaskTool.executeDelegation(taskDescription, targetModel)
    }

    companion object {
        const val TOOL_NAME = "delegate_task"

        /** The tool result when the model names no provider and none is set up to default to. */
        const val NO_PROVIDER = "Error: No cloud provider is set up, so there is nowhere to delegate the task."
    }
}
