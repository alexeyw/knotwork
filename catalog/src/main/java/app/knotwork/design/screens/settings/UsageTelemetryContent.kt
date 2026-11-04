package app.knotwork.design.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * A single label → value statistic row on the Usage statistics screen.
 *
 * @property label Left-aligned descriptor (e.g. an outcome, a pipeline name).
 * @property value Right-aligned mono value (e.g. `"12 (75%)"`).
 */
data class UsageStatRow(val label: String, val value: String)

/**
 * Immutable state of the Usage statistics screen — the privacy-preserving,
 * fully on-device usage dashboard under Settings → Privacy.
 *
 * All dynamic figures arrive pre-formatted from the presentation layer (which
 * owns the localized labels and percentages) so this catalog surface stays a
 * dumb renderer.
 *
 * @property recordingEnabled Whether local recording is currently on (opt-in toggle).
 * @property isEmpty Whether nothing has been recorded yet (drives the empty state).
 * @property runsHeadline Pre-formatted total-runs headline (e.g. `"12 total"`).
 * @property outcomes Per-outcome rows (completed / failed / cancelled / interrupted).
 * @property pipelines Per-pipeline run rows; may be empty.
 * @property triggersHeadline Pre-formatted total trigger-firings headline.
 * @property triggers Per-kind trigger-firing rows; may be empty.
 * @property activeDays Active-day rows (count, first, last).
 * @property retention Weekly-retention rows (this week vs the week before,
 *   pipelines used, streak, breaks, first week after install).
 * @property onboarding Install → first-value rows (total, model download, total
 *   excluding the download). Empty until the journey reached first value — the
 *   section is then omitted entirely rather than rendered as a row of dashes.
 */
data class UsageTelemetryViewState(
    val recordingEnabled: Boolean,
    val isEmpty: Boolean,
    val runsHeadline: String,
    val outcomes: List<UsageStatRow>,
    val pipelines: List<UsageStatRow>,
    val triggersHeadline: String,
    val triggers: List<UsageStatRow>,
    val activeDays: List<UsageStatRow>,
    val retention: List<UsageStatRow> = emptyList(),
    val onboarding: List<UsageStatRow> = emptyList(),
)

/**
 * Interaction callbacks for [UsageTelemetryContent].
 *
 * @property onBack Up navigation.
 * @property onToggleRecording Flip the local-recording opt-in.
 * @property onShare Share the statistics as plain text.
 * @property onExportJson Export the statistics as a JSON file.
 * @property onReset Clear all recorded statistics.
 */
data class UsageTelemetryCallbacks(
    val onBack: () -> Unit = {},
    val onToggleRecording: (Boolean) -> Unit = {},
    val onShare: () -> Unit = {},
    val onExportJson: () -> Unit = {},
    val onReset: () -> Unit = {},
)

/** Default no-op callbacks for previews / snapshot fixtures. */
fun noopUsageTelemetryCallbacks(): UsageTelemetryCallbacks = UsageTelemetryCallbacks()

/**
 * The Usage statistics screen body: an opt-in toggle, an explicit on-device
 * guarantee banner, the recorded figures, and the voluntary export / reset
 * actions.
 *
 * @param state Immutable screen state.
 * @param modifier Layout modifier.
 * @param callbacks Interaction callbacks.
 */
@Composable
fun UsageTelemetryContent(
    state: UsageTelemetryViewState,
    modifier: Modifier = Modifier,
    callbacks: UsageTelemetryCallbacks = noopUsageTelemetryCallbacks(),
) {
    CategoryScaffold(
        title = stringResource(R.string.knotwork_usage_title),
        subtitle = stringResource(R.string.knotwork_usage_subtitle),
        onBack = callbacks.onBack,
        modifier = modifier,
    ) {
        IconToggleRow(
            icon = AppIcons.Gauge,
            title = stringResource(R.string.knotwork_usage_record_label),
            state = stringResource(R.string.knotwork_usage_record_hint),
            checked = state.recordingEnabled,
            onCheckedChange = callbacks.onToggleRecording,
        )
        LocalOnlyBanner()
        if (state.isEmpty) {
            Text(
                text = stringResource(R.string.knotwork_usage_empty),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            StatSection(
                label = stringResource(R.string.knotwork_usage_section_runs),
                headline = state.runsHeadline,
                rows = state.outcomes,
            )
            StatSection(
                label = stringResource(R.string.knotwork_usage_section_pipelines),
                headline = null,
                rows = state.pipelines,
            )
            StatSection(
                label = stringResource(R.string.knotwork_usage_section_triggers),
                headline = state.triggersHeadline,
                rows = state.triggers,
            )
            StatSection(
                label = stringResource(R.string.knotwork_usage_section_active_days),
                headline = null,
                rows = state.activeDays,
            )
            if (state.retention.isNotEmpty()) {
                StatSection(
                    label = stringResource(R.string.knotwork_usage_section_retention),
                    headline = null,
                    rows = state.retention,
                )
            }
            if (state.onboarding.isNotEmpty()) {
                StatSection(
                    label = stringResource(R.string.knotwork_usage_section_onboarding),
                    headline = null,
                    rows = state.onboarding,
                )
            }
        }
        UsageActions(callbacks = callbacks)
    }
}

/** The explicit "nothing leaves this device" guarantee banner. */
@Composable
private fun LocalOnlyBanner() {
    Surface(
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.surface1,
        border = BorderStroke(SectionCardBorder, KnotworkTheme.extended.divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp3),
        ) {
            androidx.compose.material3.Icon(
                imageVector = AppIcons.Shield,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalSuccess,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            )
            Text(
                text = stringResource(R.string.knotwork_usage_local_only),
                style = KnotworkTextStyles.BodySm,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A labelled section: uppercase label, an optional headline, then the stat rows. */
@Composable
private fun StatSection(label: String, headline: String?, rows: List<UsageStatRow>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        SettingsSectionLabel(text = label)
        if (headline != null) {
            Text(
                text = headline,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.knotwork_usage_none),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        } else {
            rows.forEach { row -> StatRow(row) }
        }
    }
}

/** A single label (left) → mono value (right) row. */
@Composable
private fun StatRow(row: UsageStatRow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
        Text(
            text = row.label,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.value,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/** The Share / Export / Reset action cluster. */
@Composable
private fun UsageActions(callbacks: UsageTelemetryCallbacks) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_usage_action_share),
                onClick = callbacks.onShare,
                size = KnotworkButtonSize.Sm,
                modifier = Modifier.weight(1f),
            )
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_usage_action_export),
                onClick = callbacks.onExportJson,
                size = KnotworkButtonSize.Sm,
                modifier = Modifier.weight(1f),
            )
        }
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_usage_action_reset),
            onClick = callbacks.onReset,
            destructive = true,
        )
    }
}
