package app.knotwork.design.screens.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.misc.KnotworkTestProbeRow
import app.knotwork.design.components.misc.KnotworkWarningBanner
import app.knotwork.design.components.misc.TestProbeUi
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkHelpEntry
import app.knotwork.design.screens.settings.KnotworkHintPanel
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

@Composable
private fun FormSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = modifier,
    )
}

/**
 * One text field of the MCP server form.
 *
 * @param secret `true` for a credential — a token, a password, an API key or a
 *   header value: the text is masked, and the keyboard is told it is a password,
 *   so it neither suggests nor learns it. Masking alone changes only what is drawn.
 */
@Composable
private fun OutlinedFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .border(
                width = 1.dp,
                color = if (isError) KnotworkTheme.extended.signalError else KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = KnotworkTextStyles.MonoBase.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = if (secret) {
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                )
            } else {
                KeyboardOptions.Default
            },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = KnotworkTextStyles.MonoBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun AuthChip(option: McpAuthSelector, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.outlineStrong
    val labelColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceMuted
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .border(width = 1.dp, color = borderColor, shape = KnotworkTheme.shapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = option.label,
            style = KnotworkTextStyles.LabelMd,
            color = labelColor,
        )
    }
}

@Composable
private fun AuthFields(form: AddMcpServerForm, callbacks: McpServerConfigCallbacks) {
    when (form.authType) {
        McpAuthSelector.NONE -> Unit
        McpAuthSelector.BEARER -> OutlinedFormTextField(
            value = form.bearerToken,
            onValueChange = callbacks.onBearerTokenChange,
            placeholder = stringResource(R.string.knotwork_tools_form_auth_bearer_placeholder),
            isError = false,
            secret = true,
        )
        McpAuthSelector.BASIC -> {
            OutlinedFormTextField(
                value = form.basicUsername,
                onValueChange = callbacks.onBasicUsernameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_basic_user_placeholder),
                isError = false,
            )
            OutlinedFormTextField(
                value = form.basicPassword,
                onValueChange = callbacks.onBasicPasswordChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_basic_pass_placeholder),
                isError = false,
                secret = true,
            )
        }
        McpAuthSelector.API_KEY -> {
            OutlinedFormTextField(
                value = form.apiKeyHeaderName,
                onValueChange = callbacks.onApiKeyHeaderNameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_apikey_name_placeholder),
                isError = false,
            )
            OutlinedFormTextField(
                value = form.apiKeyValue,
                onValueChange = callbacks.onApiKeyValueChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_apikey_value_placeholder),
                isError = false,
                secret = true,
            )
        }
    }
}

@Composable
private fun TransportChip(option: McpTransportOption, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.outlineStrong
    val labelColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceMuted
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .border(width = 1.dp, color = borderColor, shape = KnotworkTheme.shapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = option.label,
            style = KnotworkTextStyles.LabelMd,
            color = labelColor,
        )
    }
}

@Composable
private fun HeaderRow(
    row: McpHeaderRow,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedFormTextField(
            value = row.key,
            onValueChange = onKeyChange,
            placeholder = stringResource(R.string.knotwork_tools_form_header_key_placeholder),
            isError = false,
            modifier = Modifier.weight(1f),
        )
        OutlinedFormTextField(
            value = row.value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.knotwork_tools_form_header_value_placeholder),
            isError = false,
            modifier = Modifier.weight(1f),
            // Stored encrypted whole, as the form invites an Authorization row.
            secret = true,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = AppIcons.Trash,
                contentDescription = stringResource(R.string.knotwork_tools_form_header_remove_cd),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/**
 * Full-screen MCP-server configuration surface. Hosts the rich form
 * (URL, optional display name, transport selector, repeating headers)
 * for both Add (`form.editingUrl == null`) and Edit
 * (`form.editingUrl == <original URL>`) flows.
 *
 * The host composable (app-layer screen) owns the [AddMcpServerForm]
 * state and translates submissions into persistence calls; this
 * composable renders the chrome and dispatches per-field callbacks.
 *
 * @param form The values being edited.
 * @param modifier Layout modifier from the caller.
 * @param callbacks Per-field edits, the buttons and the test row.
 * @param test The *Test connection* row, which checks the values above without saving them;
 *   `null` hides it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpServerConfigContent(
    form: AddMcpServerForm,
    modifier: Modifier = Modifier,
    callbacks: McpServerConfigCallbacks = noopMcpServerConfigCallbacks(),
    test: TestProbeUi? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Text(
                            text = if (form.isEdit) {
                                stringResource(R.string.knotwork_tools_form_title_edit)
                            } else {
                                stringResource(R.string.knotwork_tools_form_title_add)
                            },
                            style = KnotworkTextStyles.TitleMd,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = callbacks.onCancel) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(R.string.knotwork_tools_add_form_cancel),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(state = rememberScrollState())
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            // The placeholder used to read `https://… or mcp://host:port`, which
            // sent the first external tester looking for a port number he had no
            // way to know. It is now one real address, and the question it kept
            // raising is answered by the hint rather than by the field.
            var addressHintOpen by remember { mutableStateOf(false) }
            val addressLabel = stringResource(R.string.knotwork_tools_add_form_header)
            // FlowRow, not Row: an unweighted label measured against the full
            // width leaves the 28 dp glyph nothing, which is the same squeeze
            // the settings approval row had to be moved away from.
            // `Row` with both children centred on the same axis. The FlowRow
            // used here first left the glyph sitting below the label's baseline:
            // the label is 11 sp mono and the glyph's target is 28 dp, so
            // aligning the *line boxes* puts them visibly out of line. Weighting
            // the label instead keeps the glyph beside it and still lets it wrap.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormSectionLabel(text = addressLabel, modifier = Modifier.weight(1f, fill = false))
                KnotworkHelpEntry(
                    settingName = addressLabel,
                    expanded = addressHintOpen,
                    onToggle = { addressHintOpen = !addressHintOpen },
                )
            }
            OutlinedFormTextField(
                value = form.url,
                onValueChange = callbacks.onUrlChange,
                placeholder = stringResource(R.string.knotwork_tools_add_form_placeholder),
                isError = form.urlError != null,
            )
            KnotworkHintPanel(
                visible = addressHintOpen,
                text = stringResource(R.string.knotwork_tools_add_form_address_hint),
            )
            if (form.urlError != null) {
                Text(
                    text = form.urlError,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.signalError,
                )
            }
            // Unencrypted traffic to a private address is refused until the user
            // approves this exact origin. Shown inline rather than as a dialog so
            // it is visible while the address is still being typed.
            form.cleartextConsentOrigin?.let { origin ->
                KnotworkWarningBanner(
                    text = stringResource(R.string.knotwork_tools_cleartext_consent_body, origin),
                    actionLabel = stringResource(R.string.knotwork_tools_cleartext_consent_action),
                    onAction = callbacks.onApproveCleartext,
                    testTag = MCP_CLEARTEXT_CONSENT_TAG,
                )
            }

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_name_label))
            OutlinedFormTextField(
                value = form.name,
                onValueChange = callbacks.onNameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_name_placeholder),
                isError = false,
            )

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_transport_label))
            Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                // Render the selectable options plus, if the form was loaded
                // with a non-selectable choice (e.g. an older "Streamable HTTP"
                // pick), surface that chip too so the user can see what's
                // persisted instead of being silently downgraded.
                val visible = McpTransportOption.entries.filter { it.selectable || it == form.transport }
                visible.forEach { option ->
                    TransportChip(
                        option = option,
                        selected = form.transport == option,
                        onClick = { callbacks.onTransportSelect(option) },
                    )
                }
            }

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_auth_label))
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(state = rememberScrollState()),
            ) {
                McpAuthSelector.entries.forEach { option ->
                    AuthChip(
                        option = option,
                        selected = form.authType == option,
                        onClick = { callbacks.onAuthTypeSelect(option) },
                    )
                }
            }
            AuthFields(form = form, callbacks = callbacks)

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_headers_label))
            form.headers.forEachIndexed { index, row ->
                HeaderRow(
                    row = row,
                    onKeyChange = { newKey -> callbacks.onHeaderChange(index, newKey, row.value) },
                    onValueChange = { newValue -> callbacks.onHeaderChange(index, row.key, newValue) },
                    onRemove = { callbacks.onHeaderRemove(index) },
                )
            }
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_tools_form_headers_add),
                onClick = callbacks.onHeaderAdd,
            )

            // After the last field the check reads (the credentials and headers) and above the
            // buttons, because those save and the test does not.
            test?.let { row ->
                KnotworkTestProbeRow(state = row, onRun = callbacks.onTestRun, onCancel = callbacks.onTestCancel)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                KnotworkTextButton(
                    text = stringResource(R.string.knotwork_tools_add_form_cancel),
                    onClick = callbacks.onCancel,
                )
                Spacer(modifier = Modifier.size(KnotworkTheme.spacing.sp2))
                KnotworkPrimaryButton(
                    text = if (form.isEdit) {
                        stringResource(R.string.knotwork_tools_form_submit_save)
                    } else {
                        stringResource(R.string.knotwork_tools_add_form_submit)
                    },
                    onClick = callbacks.onSubmit,
                    enabled = form.canSubmit,
                )
            }
        }
    }
}

/** Test tag for the unencrypted-connection consent banner in the MCP server form. */
const val MCP_CLEARTEXT_CONSENT_TAG: String = "mcp_cleartext_consent_banner"
