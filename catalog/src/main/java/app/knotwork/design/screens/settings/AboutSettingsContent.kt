package app.knotwork.design.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * About category sub-screen. Basic tier surfaces the read-only identity card and
 * the version/licenses link; the Advanced disclosure holds the destructive
 * "Reset all settings" action.
 *
 * @param state Immutable About state.
 * @param modifier Layout modifier.
 * @param callbacks Interaction callbacks.
 */
@Composable
fun AboutSettingsContent(
    state: AboutSettingsViewState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = noopSettingsCallbacks(),
    advancedExpanded: Boolean = false,
) {
    CategoryScaffold(
        title = stringResource(R.string.knotwork_settings_cat_about_title),
        subtitle = "",
        onBack = callbacks.onBack,
        modifier = modifier,
        overlay = {
            if (state.destructiveAction != null) {
                DestructiveDialogHost(payload = state.destructiveAction, callbacks = callbacks)
            }
        },
    ) {
        SettingsAnchor(anchorKey = SettingsRowAnchors.IDENTITY) {
            IdentityCard(state.identity)
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.LINK_LICENSES) {
            NavLinkRow(
                icon = AppIcons.Info,
                title = stringResource(R.string.knotwork_settings_about_version_title),
                state = state.versionLine,
                onClick = callbacks.onOpenLicenses,
            )
        }
        AdvancedDisclosure(initiallyExpanded = advancedExpanded) {
            SettingsAnchor(anchorKey = SettingsRowAnchors.RESET) {
                ResetRow(onResetClick = callbacks.onResetSettingsClick)
            }
        }
    }
}

@Composable
private fun IdentityCard(state: IdentityCardState?) {
    Surface(
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.surface1,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state == null) {
            StripedPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IDENTITY_LOADING_HEIGHT)
                    .padding(KnotworkTheme.spacing.sp4),
            )
            return@Surface
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(IDENTITY_AVATAR_SIZE)
                    .clip(KnotworkTheme.shapes.full)
                    .background(color = KnotworkTheme.extended.surface3),
            ) {
                Text(
                    text = state.avatarInitials,
                    style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.displayName,
                    style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.metaLine,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun ResetRow(onResetClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.knotwork_settings_reset_button),
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.knotwork_settings_reset_hint),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_settings_reset_button),
            onClick = onResetClick,
            size = KnotworkButtonSize.Sm,
            destructive = true,
        )
    }
}

private val IDENTITY_LOADING_HEIGHT = 72.dp
private val IDENTITY_AVATAR_SIZE = 48.dp
