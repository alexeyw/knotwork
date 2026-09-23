package app.knotwork.design.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.controls.KnotworkFieldSize
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Standalone editor for the `http_request` domain allowlist — the gesture that
 * opts the device into outbound HTTP. Mirrors the structure of
 * [McpServerConfigContent] (a pushed screen reached from the Tools list), with:
 *
 *  - An **empty state** (globe hero + the tool-is-off explanation + an amber
 *    risk note) when no host is allowed yet, and
 *  - A **populated state** (an info explainer + the host list with per-row
 *    remove + an "exact match" footer) once at least one host exists.
 *
 * The add-a-host field is present in both states; its helper line reflects the
 * host module's [AddHostState] (neutral / normalised preview / invalid /
 * duplicate). The catalog renders only what [state] dictates — all validation,
 * normalisation and persistence live in the host `AllowedDomainsViewModel`.
 *
 * @param state immutable screen state.
 * @param modifier layout modifier from the host.
 * @param callbacks event bundle wired to the host ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowedDomainsContent(
    state: AllowedDomainsViewState,
    modifier: Modifier = Modifier,
    callbacks: AllowedDomainsCallbacks = noopAllowedDomainsCallbacks(),
) {
    var infoVisible by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AllowedDomainsTopBar(
                hostCount = state.hosts.size,
                callbacks = callbacks,
                onInfo = { infoVisible = true },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(state = rememberScrollState())
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            if (state.hosts.isEmpty()) {
                EmptyHero()
                NoteTile(
                    icon = AppIcons.Warn,
                    iconTint = KnotworkTheme.extended.signalWarn,
                    container = KnotworkTheme.extended.signalWarn.copy(alpha = WARN_TILE_ALPHA),
                    border = KnotworkTheme.extended.signalWarn.copy(alpha = WARN_BORDER_ALPHA),
                    title = stringResource(R.string.knotwork_allowed_domains_empty_warn_title),
                    body = stringResource(R.string.knotwork_allowed_domains_empty_warn_body),
                )
                AddHostBlock(state = state, callbacks = callbacks)
            } else {
                NoteTile(
                    icon = AppIcons.Shield,
                    iconTint = KnotworkTheme.extended.onSurfaceMuted,
                    container = KnotworkTheme.extended.surface2,
                    border = MaterialTheme.colorScheme.outline,
                    title = null,
                    body = stringResource(R.string.knotwork_allowed_domains_explainer),
                )
                AddHostBlock(state = state, callbacks = callbacks)
                HostList(hosts = state.hosts, callbacks = callbacks)
            }
        }
    }
    if (infoVisible) {
        AllowedDomainsInfoSheet(onDismiss = { infoVisible = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowedDomainsTopBar(hostCount: Int, callbacks: AllowedDomainsCallbacks, onInfo: () -> Unit) {
    KnotworkTopAppBarShell {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = stringResource(R.string.knotwork_allowed_domains_title),
                        style = KnotworkTextStyles.TitleMd,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.knotwork_allowed_domains_subtitle,
                            hostCount,
                            hostCount,
                        ),
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = callbacks.onBack) {
                    Icon(
                        imageVector = AppIcons.Back,
                        contentDescription = stringResource(R.string.knotwork_allowed_domains_back_cd),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            actions = {
                IconButton(onClick = onInfo) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = stringResource(R.string.knotwork_allowed_domains_info_cd),
                        tint = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowedDomainsInfoSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = KnotworkTheme.spacing.sp4,
                    end = KnotworkTheme.spacing.sp4,
                    bottom = KnotworkTheme.spacing.sp6,
                ),
        ) {
            Text(
                text = stringResource(R.string.knotwork_allowed_domains_info_title),
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.knotwork_allowed_domains_explainer),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            NoteTile(
                icon = AppIcons.Warn,
                iconTint = KnotworkTheme.extended.signalWarn,
                container = KnotworkTheme.extended.signalWarn.copy(alpha = WARN_TILE_ALPHA),
                border = KnotworkTheme.extended.signalWarn.copy(alpha = WARN_BORDER_ALPHA),
                title = stringResource(R.string.knotwork_allowed_domains_empty_warn_title),
                body = stringResource(R.string.knotwork_allowed_domains_empty_warn_body),
            )
            KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_allowed_domains_info_close),
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun EmptyHero() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp6),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(HERO_TILE_SIZE)
                .clip(CircleShape)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(
                imageVector = AppIcons.Globe,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(HERO_ICON_SIZE),
            )
        }
        Text(
            text = stringResource(R.string.knotwork_allowed_domains_empty_title),
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.knotwork_allowed_domains_empty_body),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoteTile(
    icon: ImageVector,
    iconTint: Color,
    container: Color,
    border: Color,
    title: String?,
    body: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = container)
            .border(width = 1.dp, color = border, shape = KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(NOTE_ICON_SIZE),
        )
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            if (title != null) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = body,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun AddHostBlock(state: AllowedDomainsViewState, callbacks: AllowedDomainsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        SectionLabel(text = stringResource(R.string.knotwork_allowed_domains_add_label))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            KnotworkTextField(
                value = state.addInput,
                onValueChange = callbacks.onAddInputChange,
                modifier = Modifier.weight(1f),
                size = KnotworkFieldSize.Md,
                placeholder = stringResource(R.string.knotwork_allowed_domains_add_placeholder),
                isError = state.addState is AddHostState.Invalid || state.addState is AddHostState.Duplicate,
                monospace = true,
                contentDescription = stringResource(R.string.knotwork_allowed_domains_add_label),
            )
            KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_allowed_domains_add_button),
                onClick = callbacks.onAddSubmit,
                size = KnotworkButtonSize.Md,
                enabled = state.addState is AddHostState.NormalizedPreview,
                leadingIcon = AppIcons.Add,
            )
        }
        AddHelperLine(addState = state.addState)
    }
}

@Composable
private fun AddHelperLine(addState: AddHostState) {
    when (addState) {
        is AddHostState.NormalizedPreview -> HelperRow(
            icon = AppIcons.Check,
            tint = KnotworkTheme.extended.signalSuccess,
            text = stringResource(R.string.knotwork_allowed_domains_add_normalized, addState.normalized),
            textColor = MaterialTheme.colorScheme.onSurface,
        )

        is AddHostState.Invalid -> HelperRow(
            icon = AppIcons.AlertCircle,
            tint = KnotworkTheme.extended.signalError,
            text = stringResource(R.string.knotwork_allowed_domains_add_invalid),
            textColor = KnotworkTheme.extended.signalError,
        )

        is AddHostState.Duplicate -> HelperRow(
            icon = AppIcons.Info,
            tint = KnotworkTheme.extended.signalWarn,
            text = stringResource(R.string.knotwork_allowed_domains_add_duplicate, addState.existing),
            textColor = KnotworkTheme.extended.signalWarn,
        )

        AddHostState.Idle -> Text(
            text = stringResource(R.string.knotwork_allowed_domains_add_helper),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun HelperRow(icon: ImageVector, tint: Color, text: String, textColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(HELPER_ICON_SIZE),
        )
        Text(text = text, style = KnotworkTextStyles.BodySm, color = textColor)
    }
}

@Composable
private fun HostList(hosts: List<String>, callbacks: AllowedDomainsCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        SectionLabel(
            text = pluralStringResource(
                R.plurals.knotwork_allowed_domains_list_label,
                hosts.size,
                hosts.size,
            ),
        )
        hosts.forEach { host ->
            HostRow(host = host, callbacks = callbacks)
            HorizontalDivider(color = KnotworkTheme.extended.divider)
        }
        Text(
            text = stringResource(R.string.knotwork_allowed_domains_list_footer),
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun HostRow(host: String, callbacks: AllowedDomainsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KnotworkTheme.spacing.sp3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ROW_TILE_SIZE)
                .clip(KnotworkTheme.shapes.sm)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(
                imageVector = AppIcons.Globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(ROW_ICON_SIZE),
            )
        }
        Text(
            text = host,
            style = KnotworkTextStyles.MonoBase.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { callbacks.onRemoveHost(host) }) {
            Icon(
                imageVector = AppIcons.Trash,
                contentDescription = stringResource(R.string.knotwork_allowed_domains_remove_cd, host),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

private val HERO_TILE_SIZE = 64.dp
private val HERO_ICON_SIZE = 28.dp
private val NOTE_ICON_SIZE = 18.dp
private val HELPER_ICON_SIZE = 16.dp
private val ROW_TILE_SIZE = 40.dp
private val ROW_ICON_SIZE = 18.dp
private const val WARN_TILE_ALPHA = 0.14f
private const val WARN_BORDER_ALPHA = 0.5f
