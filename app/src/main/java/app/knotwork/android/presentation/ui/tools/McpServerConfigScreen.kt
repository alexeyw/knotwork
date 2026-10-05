package app.knotwork.android.presentation.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.presentation.ui.common.TestSubject
import app.knotwork.android.presentation.ui.common.elapsedSecondsOf
import app.knotwork.android.presentation.ui.common.toTestProbeUi
import app.knotwork.design.screens.tools.McpServerConfigCallbacks
import app.knotwork.design.screens.tools.McpServerConfigContent

/**
 * Stateful entry point for the standalone MCP server configuration
 * screen. Both Add (no `originalUrl` nav argument) and Edit
 * (`originalUrl` matches the row being edited) modes are routed
 * through this composable; `McpServerConfigViewModel.form.editingUrl`
 * distinguishes them visually.
 */
@Composable
fun McpServerConfigScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: McpServerConfigViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val test by viewModel.connectionTestState.collectAsStateWithLifecycle()
    val elapsedSeconds by elapsedSecondsOf(test)
    val context = LocalContext.current

    LaunchedEffect(event) {
        if (event is McpServerConfigViewModel.Event.Saved) {
            viewModel.consumeEvent()
            onDone()
        }
    }

    val callbacks = McpServerConfigCallbacks(
        onUrlChange = viewModel::onUrlChange,
        onNameChange = viewModel::onNameChange,
        onTransportSelect = viewModel::onTransportSelect,
        onAuthTypeSelect = viewModel::onAuthTypeSelect,
        onBearerTokenChange = viewModel::onBearerTokenChange,
        onBasicUsernameChange = viewModel::onBasicUsernameChange,
        onBasicPasswordChange = viewModel::onBasicPasswordChange,
        onApiKeyHeaderNameChange = viewModel::onApiKeyHeaderNameChange,
        onApiKeyValueChange = viewModel::onApiKeyValueChange,
        onHeaderAdd = viewModel::onHeaderAdd,
        onHeaderChange = viewModel::onHeaderChange,
        onHeaderRemove = viewModel::onHeaderRemove,
        onSubmit = viewModel::onSubmit,
        onCancel = onCancel,
        onApproveCleartext = viewModel::onApproveCleartext,
        onTestRun = viewModel::onTestConnection,
        onTestCancel = viewModel::onCancelConnectionTest,
    )

    McpServerConfigContent(
        form = form,
        callbacks = callbacks,
        modifier = modifier.testTag(tag = MCP_SERVER_CONFIG_ROOT_TEST_TAG),
        test = test.toTestProbeUi(context, TestSubject.Mcp, elapsedSeconds),
    )
}

/** TestTag applied to the MCP server config screen root. */
internal const val MCP_SERVER_CONFIG_ROOT_TEST_TAG = "mcp_server_config_root"
