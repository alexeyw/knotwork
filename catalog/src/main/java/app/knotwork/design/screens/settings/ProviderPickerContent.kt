package app.knotwork.design.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The list of cloud providers a user can configure.
 *
 * A full screen rather than a bottom sheet so the predictive-back gesture works
 * without anchored-draggable plumbing.
 *
 * Rows arrive as resolved strings rather than as a provider enum: `:app` owns
 * which providers exist, and adding one must not reach this module. They come in
 * the order every provider surface uses, grouped into hosted providers and
 * servers the user runs.
 *
 * @param state Title and the groups of rows to list.
 * @param modifier Layout modifier from the caller.
 * @param onPick A row was tapped, by its id.
 * @param onBack Pop back to Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerContent(
    state: ProviderPickerViewState,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.testTag(PROVIDER_PICKER_ROOT_TEST_TAG),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title,
                            style = KnotworkTextStyles.TitleMd,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = state.backContentDescription,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = KnotworkTheme.spacing.sp4),
        ) {
            state.groups.forEach { group ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = KnotworkTheme.spacing.sp4)
                        .padding(top = KnotworkTheme.spacing.sp4, bottom = KnotworkTheme.spacing.sp1)
                        .semantics { heading() },
                ) { SettingsSectionLabel(text = group.title) }
                group.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = KnotworkTheme.extended.divider)
                    ProviderPickerRow(row = row, addedLabel = state.addedLabel, onClick = { onPick(row.id) })
                }
            }
        }
    }
}

/**
 * One tappable provider row: its name, an optional description, and the *added* marker when the
 * provider is already set up.
 *
 * @param row The provider.
 * @param addedLabel The marker text.
 * @param onClick Row tapped.
 */
@Composable
private fun ProviderPickerRow(row: ProviderPickerRowUi, addedLabel: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .testTag(PROVIDER_PICKER_ROW_TAG_PREFIX + row.id),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            row.description?.let {
                Text(text = it, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
            }
        }
        if (row.added) {
            Text(text = addedLabel, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
        }
        Icon(imageVector = AppIcons.ArrowR, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
    }
}

/** Test-tag prefix of a picker row; the row's id follows. */
const val PROVIDER_PICKER_ROW_TAG_PREFIX: String = "provider_picker_row_"

private val ROW_MIN_HEIGHT = 52.dp

/** Root test tag of the provider picker surface. */
const val PROVIDER_PICKER_ROOT_TEST_TAG: String = "provider_picker_root"
