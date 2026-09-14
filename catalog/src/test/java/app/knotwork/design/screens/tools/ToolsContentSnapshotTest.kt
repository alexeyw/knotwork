package app.knotwork.design.screens.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ToolsContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tools_empty_light() = snapshot(name = "empty", dark = false) {
        ToolsContent(state = ToolsPreview.empty())
    }

    @Test
    fun tools_empty_dark() = snapshot(name = "empty", dark = true) {
        ToolsContent(state = ToolsPreview.empty())
    }

    @Test
    fun tools_default_light() = snapshot(name = "default", dark = false) {
        ToolsContent(state = ToolsPreview.default())
    }

    @Test
    fun tools_default_dark() = snapshot(name = "default", dark = true) {
        ToolsContent(state = ToolsPreview.default())
    }

    @Test
    fun tools_default_expanded_light() = snapshot(name = "default_expanded", dark = false) {
        ToolsContent(state = ToolsPreview.defaultExpanded())
    }

    @Test
    fun tools_default_expanded_dark() = snapshot(name = "default_expanded", dark = true) {
        ToolsContent(state = ToolsPreview.defaultExpanded())
    }

    @Test
    fun tools_default_syncing_light() = snapshot(name = "default_syncing", dark = false) {
        ToolsContent(state = ToolsPreview.defaultSyncing())
    }

    @Test
    fun tools_default_syncing_dark() = snapshot(name = "default_syncing", dark = true) {
        ToolsContent(state = ToolsPreview.defaultSyncing())
    }

    @Test
    fun tools_default_disconnected_light() = snapshot(name = "default_disconnected", dark = false) {
        ToolsContent(state = ToolsPreview.defaultDisconnected())
    }

    @Test
    fun tools_default_disconnected_dark() = snapshot(name = "default_disconnected", dark = true) {
        ToolsContent(state = ToolsPreview.defaultDisconnected())
    }

    @Test
    fun tools_loading_light() = snapshot(name = "loading", dark = false) {
        ToolsContent(state = ToolsPreview.loading())
    }

    @Test
    fun tools_loading_dark() = snapshot(name = "loading", dark = true) {
        ToolsContent(state = ToolsPreview.loading())
    }

    @Test
    fun tools_error_light() = snapshot(name = "error", dark = false) {
        ToolsContent(state = ToolsPreview.error())
    }

    @Test
    fun tools_error_dark() = snapshot(name = "error", dark = true) {
        ToolsContent(state = ToolsPreview.error())
    }

    @Test
    fun tool_detail_default_light() = snapshot(name = "tool_detail_default", dark = false) {
        ToolDetailContent(state = ToolsPreview.toolDetailDefault())
    }

    @Test
    fun tool_detail_default_dark() = snapshot(name = "tool_detail_default", dark = true) {
        ToolDetailContent(state = ToolsPreview.toolDetailDefault())
    }

    // The two risk branches are different controls, not two values of one — a
    // built-in states its level, an MCP tool offers the choice — so each needs
    // its own frame.
    @Test
    fun tool_detail_fixed_risk_light() = snapshot(name = "tool_detail_fixed_risk", dark = false) {
        ToolDetailContent(state = ToolsPreview.toolDetailFixedRisk())
    }

    @Test
    fun tool_detail_fixed_risk_dark() = snapshot(name = "tool_detail_fixed_risk", dark = true) {
        ToolDetailContent(state = ToolsPreview.toolDetailFixedRisk())
    }

    @Test
    fun tool_detail_schema_error_light() = snapshot(name = "tool_detail_schema_error", dark = false) {
        ToolDetailContent(state = ToolsPreview.toolDetailSchemaError())
    }

    @Test
    fun tool_detail_schema_error_dark() = snapshot(name = "tool_detail_schema_error", dark = true) {
        ToolDetailContent(state = ToolsPreview.toolDetailSchemaError())
    }

    @Test
    fun add_mcp_default_light() = snapshot(name = "add_mcp_default", dark = false) {
        McpServerConfigContent(form = ToolsPreview.addMcpDefault())
    }

    @Test
    fun add_mcp_default_dark() = snapshot(name = "add_mcp_default", dark = true) {
        McpServerConfigContent(form = ToolsPreview.addMcpDefault())
    }

    /**
     * The empty form, which is where the placeholder actually shows.
     *
     * The `add_mcp_default` boards above all carry a typed URL, so the
     * placeholder — the string that sent the first external tester looking for a
     * port number he had no way to know — was in no baseline at all.
     */
    @Test
    fun add_mcp_empty_light() = snapshot(name = "add_mcp_empty", dark = false) {
        McpServerConfigContent(form = AddMcpServerForm())
    }

    @Test
    fun add_mcp_invalid_light() = snapshot(name = "add_mcp_invalid", dark = false) {
        McpServerConfigContent(form = ToolsPreview.addMcpInvalid())
    }

    @Test
    fun edit_mcp_with_headers_light() = snapshot(name = "edit_mcp_with_headers", dark = false) {
        McpServerConfigContent(form = ToolsPreview.editMcpWithHeaders())
    }

    @Test
    fun edit_mcp_with_headers_dark() = snapshot(name = "edit_mcp_with_headers", dark = true) {
        McpServerConfigContent(form = ToolsPreview.editMcpWithHeaders())
    }

    @Test
    fun tools_colliding_tool_names_light() = snapshot(name = "colliding_tool_names", dark = false) {
        ToolsContent(state = ToolsPreview.defaultCollidingNames())
    }

    @Test
    fun tools_colliding_tool_names_dark() = snapshot(name = "colliding_tool_names", dark = true) {
        ToolsContent(state = ToolsPreview.defaultCollidingNames())
    }

    @Test
    fun add_mcp_cleartext_consent_light() = snapshot(name = "add_mcp_cleartext_consent", dark = false) {
        McpServerConfigContent(form = ToolsPreview.addMcpNeedingCleartextConsent())
    }

    @Test
    fun add_mcp_cleartext_consent_dark() = snapshot(name = "add_mcp_cleartext_consent", dark = true) {
        McpServerConfigContent(form = ToolsPreview.addMcpNeedingCleartextConsent())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/tools_${name}_$themeTag.png",
        )
    }
}

internal object ToolsPreview {

    private fun builtIns(): List<BuiltInToolRow> = listOf(
        BuiltInToolRow(
            id = "search_tool",
            name = "search_tool",
            description = "Wikipedia lookup · returns concise summary",
            risk = BuiltInToolRisk.ReadOnly,
            enabled = true,
        ),
        BuiltInToolRow(
            id = "schedule_task",
            name = "schedule_task",
            description = "Run a task later · WorkManager · one-off or recurring",
            risk = BuiltInToolRisk.Sensitive,
            enabled = true,
        ),
        BuiltInToolRow(
            id = "delegate_task",
            name = "delegate_task",
            description = "Hand subtask to cloud LLM · stores result in memory",
            risk = BuiltInToolRisk.Destructive,
            enabled = false,
        ),
        BuiltInToolRow(
            id = "http_request",
            name = "http_request",
            description = "Fetch a URL on an allowed host · per-method HITL",
            risk = BuiltInToolRisk.Sensitive,
            enabled = true,
            allowedDomainsCount = 5,
        ),
    )

    private fun servers(): List<McpServerRow> = listOf(
        McpServerRow(
            id = "mcp://arxiv-search.local:7411",
            url = "mcp://arxiv-search.local:7411",
            toolCount = 3,
            latencyLabel = "42 ms",
            state = McpConnectionState.Connected,
        ),
        McpServerRow(
            id = "https://mcp.example.com/agents",
            url = "https://mcp.example.com/agents",
            toolCount = 8,
            latencyLabel = "318 ms",
            state = McpConnectionState.Connected,
        ),
        McpServerRow(
            id = "mcp://files.local:7410",
            url = "mcp://files.local:7410",
            toolCount = 2,
            latencyLabel = "disabled",
            state = McpConnectionState.Disabled,
        ),
    )

    private fun nestedTools(): List<McpToolEntry> = listOf(
        McpToolEntry(
            id = "mcp:abc12345:arxiv.search",
            name = "arxiv.search",
            description = "Search arXiv abstracts by query · returns the top 5 hits",
            risk = BuiltInToolRisk.ReadOnly,
            enabled = true,
        ),
        McpToolEntry(
            id = "mcp:abc12345:arxiv.fetch",
            name = "arxiv.fetch",
            description = "Download an arXiv PDF by id · stores into memory",
            risk = BuiltInToolRisk.Sensitive,
            enabled = false,
        ),
    )

    /**
     * Two MCP tools whose names share a long prefix — the collision reported in
     * the directed MCP run. Ellipsised to one line they rendered identically,
     * and each row carries its own enable toggle, so the only way to tell them
     * apart was the description.
     */
    private fun collidingNameTools(): List<McpToolEntry> = listOf(
        McpToolEntry(
            id = "mcp:abc12345:get-resource-repository-contents",
            name = "get-resource-repository-contents",
            description = "Read a file from the repository",
            risk = BuiltInToolRisk.ReadOnly,
            enabled = true,
        ),
        McpToolEntry(
            id = "mcp:abc12345:get-resource-repository-metadata",
            name = "get-resource-repository-metadata",
            description = "Read the repository description and topics",
            risk = BuiltInToolRisk.ReadOnly,
            enabled = true,
        ),
    )

    fun defaultCollidingNames(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = servers().mapIndexed { index, row ->
            if (index == 0) row.copy(tools = collidingNameTools(), expanded = true) else row
        },
    )

    fun empty(): ToolsViewState = ToolsViewState(visualState = ToolsVisualState.Empty)

    fun loading(): ToolsViewState = ToolsViewState(visualState = ToolsVisualState.Loading)

    fun default(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = servers(),
    )

    /**
     * Default surface with the first MCP server row expanded, surfacing its
     * nested [McpToolEntry] rows underneath the server header (per the
     * `screens/README.md §C4` Connected state — "rows render normally").
     */
    fun defaultExpanded(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = servers().mapIndexed { index, row ->
            if (index == 0) row.copy(tools = nestedTools(), expanded = true) else row
        },
    )

    /**
     * Default surface with one server in the [McpConnectionState.Syncing]
     * state — leading dot rendered in `MaterialTheme.colorScheme.primary`,
     * subtitle reflects the fetch-in-flight label.
     */
    fun defaultSyncing(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = listOf(
            McpServerRow(
                id = "mcp://arxiv-search.local:7411",
                url = "mcp://arxiv-search.local:7411",
                toolCount = 3,
                latencyLabel = "syncing…",
                state = McpConnectionState.Syncing,
            ),
            servers()[1],
            servers()[2],
        ),
    )

    /**
     * Default surface with one server in [McpConnectionState.Disconnected]
     * — server row and its nested tools (when expanded) render at 60 %
     * opacity per `screens/README.md §C4` Disconnected state.
     */
    fun defaultDisconnected(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = listOf(
            McpServerRow(
                id = "mcp://arxiv-search.local:7411",
                url = "mcp://arxiv-search.local:7411",
                toolCount = 3,
                latencyLabel = "unreachable",
                state = McpConnectionState.Disconnected,
                tools = nestedTools(),
                expanded = true,
            ),
            servers()[1],
            servers()[2],
        ),
    )

    /**
     * Built-in tools present, no MCP servers yet. Not [ToolsVisualState.Empty]:
     * the built-in group is never empty, so the screen is a normal Default
     * surface with one empty group in it.
     */
    fun noMcpServers(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Default,
        builtInTools = builtIns(),
        mcpServers = emptyList(),
    )

    fun error(): ToolsViewState = ToolsViewState(
        visualState = ToolsVisualState.Error,
        errorMessage = "Tool discovery handshake failed: connection refused.",
    )

    fun toolDetailDefault(): ToolDetailViewState = ToolDetailViewState(
        visualState = ToolDetailVisualState.Default,
        toolName = "shell.run",
        description = "Run a shell command on the MCP server.",
        serverDisplayName = "example.com",
        schemaJson = """{
  "name": "shell.run",
  "type": "object",
  "properties": {
    "command": { "type": "string" }
  }
}""",
        lastUsed = "2 hours ago",
        enabled = true,
        risk = ToolRiskUi.Editable(BuiltInToolRisk.Sensitive),
    )

    /** A built-in tool: the level is stated, and the app owns it. */
    fun toolDetailFixedRisk(): ToolDetailViewState = toolDetailDefault().copy(
        toolName = "read_file",
        serverDisplayName = "Local tool",
        risk = ToolRiskUi.Fixed(BuiltInToolRisk.ReadOnly),
    )

    fun toolDetailSchemaError(): ToolDetailViewState = ToolDetailViewState(
        visualState = ToolDetailVisualState.SchemaError,
        toolName = "broken.tool",
        description = "Server returned malformed JSON-Schema.",
        serverDisplayName = "example.com",
        schemaJson = null,
        lastUsed = null,
        enabled = false,
        risk = ToolRiskUi.Editable(BuiltInToolRisk.Destructive),
    )

    fun addMcpDefault(): AddMcpServerForm = AddMcpServerForm(url = "https://server.example.com/mcp")

    /**
     * A LAN address typed over plain HTTP: allowed, but only once the user has
     * agreed to it for this exact origin, so the form shows the consent notice
     * and refuses to save until it is resolved.
     */
    fun addMcpNeedingCleartextConsent(): AddMcpServerForm = AddMcpServerForm(
        url = "http://192.168.1.42:8080/sse",
        cleartextConsentOrigin = "http://192.168.1.42:8080",
    )

    fun addMcpInvalid(): AddMcpServerForm = AddMcpServerForm(
        url = "server.example.com",
        urlError = "URL must start with http:// or https://.",
    )

    fun editMcpWithHeaders(): AddMcpServerForm = AddMcpServerForm(
        url = "https://hf.example/mcp",
        name = "HuggingFace MCP",
        transport = McpTransportOption.StreamableHttp,
        headers = listOf(McpHeaderRow(key = "Authorization", value = "Bearer hf_secret")),
        editingUrl = "https://hf.example/mcp",
    )
}
