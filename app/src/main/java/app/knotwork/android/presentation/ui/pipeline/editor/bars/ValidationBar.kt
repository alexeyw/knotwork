package app.knotwork.android.presentation.ui.pipeline.editor.bars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.presentation.ui.pipeline.editor.core.focusableNodeId
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

private val BAR_MAX_HEIGHT = 220.dp
private val BAR_MIN_HEIGHT = 36.dp

/**
 * Bottom bar listing every [PipelineValidationError] for the active pipeline.
 *
 * The bar provides:
 *  - a header banner showing the issue count + `Auto-fix` action
 *  - per-row severity icons (blocker vs. warning)
 *  - a trailing `Go ↗` button that focuses the offending node when one exists
 *
 * Rendered as a `LazyColumn` capped at [BAR_MAX_HEIGHT] so the canvas keeps the
 * lion's share of the screen even on long error lists.
 *
 * @param graph the live pipeline graph — used by [focusableNodeId] to resolve
 *   each error to a target node id when one exists.
 * @param errors validation errors from `PipelineGraph.validate()` via the ViewModel.
 * @param errorLabels human-readable copies for each error (resolved by the
 *   caller using the existing
 *   `OrchestratorErrorText.forValidation` so wording stays
 *   single-sourced).
 * @param nodeLookup maps `nodeId → display label` for inline node attribution
 *   on the row.
 * @param onFocusNode invoked when the user taps a row or its `Go ↗` button.
 * @param onAutoFix invoked when the user taps the header `Auto-fix` action.
 *   Caller runs `ValidationAutoFix.apply` and replaces the pipeline.
 * @param modifier optional modifier applied to the bar root.
 */
@Composable
internal fun ValidationBar(
    graph: PipelineGraph,
    errors: List<PipelineValidationError>,
    errorLabels: List<String>,
    nodeLookup: (String) -> String?,
    onFocusNode: (String) -> Unit,
    onAutoFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (errors.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = BAR_MIN_HEIGHT)
                .background(KnotworkTheme.extended.surface1)
                .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pipeline_editor_validation_clean),
                style = KnotworkTextStyles.LabelMd,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        return
    }
    val autoFixApplicable = errors.any { isAutoFixable(it) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = BAR_MAX_HEIGHT)
            .background(KnotworkTheme.extended.surface1),
    ) {
        HeaderBanner(
            issueCount = errors.size,
            autoFixApplicable = autoFixApplicable,
            onAutoFix = onAutoFix,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = errors.zip(errorLabels)) { (error, label) ->
                val focusId = error.focusableNodeId(graph)
                ValidationRow(
                    label = label,
                    severity = severityOf(error),
                    nodeLabel = focusId?.let(nodeLookup),
                    canFocus = focusId != null,
                    onGo = { focusId?.let(onFocusNode) },
                )
            }
        }
    }
}

@Composable
private fun HeaderBanner(issueCount: Int, autoFixApplicable: Boolean, onAutoFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = pluralStringResource(
                id = R.plurals.pipeline_editor_validation_count,
                count = issueCount,
                issueCount,
            ),
            style = KnotworkTextStyles.LabelMd,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        KnotworkTextButton(
            text = stringResource(R.string.pipeline_editor_validation_auto_fix),
            onClick = onAutoFix,
            enabled = autoFixApplicable,
        )
    }
}

@Composable
private fun ValidationRow(label: String, severity: Severity, nodeLabel: String?, canFocus: Boolean, onGo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = severity.icon,
            contentDescription = null,
            tint = severity.tint(),
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = KnotworkTextStyles.BodySm,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (nodeLabel != null) {
                Text(
                    text = nodeLabel,
                    style = KnotworkTextStyles.LabelSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        TextButton(onClick = onGo, enabled = canFocus) {
            Text(text = stringResource(R.string.pipeline_editor_validation_go))
            Icon(
                imageVector = AppIcons.ArrowR,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = KnotworkTheme.spacing.sp1)
                    .size(14.dp),
            )
        }
    }
}

/** Per-row severity classification — drives the icon and tint. */
private enum class Severity(val icon: ImageVector) {
    Blocker(AppIcons.X),
    Warning(AppIcons.Warn),
    ;

    @Composable
    fun tint() = when (this) {
        Blocker -> MaterialTheme.colorScheme.error
        Warning -> KnotworkTheme.extended.signalWarn
    }
}

/**
 * Classifies [error] as `Blocker` (hard gate, the run won't start) or `Warning`
 * (recoverable, the run might still produce something useful).
 */
private fun severityOf(error: PipelineValidationError): Severity = when (error) {
    PipelineValidationError.MissingInput,
    PipelineValidationError.MissingOutput,
    PipelineValidationError.MultipleInputs,
    PipelineValidationError.MultipleOutputs,
    PipelineValidationError.HasCycles,
    // A broken composition (missing/dangling target, cycle, over-depth) cannot run.
    is PipelineValidationError.MissingTargetPipeline,
    is PipelineValidationError.TargetPipelineNotFound,
    is PipelineValidationError.PipelineCycle,
    is PipelineValidationError.PipelineNestingTooDeep,
    // A SKILL node with no / dangling skill has no instruction to run.
    is PipelineValidationError.MissingSkill,
    is PipelineValidationError.SkillNotFound,
    -> Severity.Blocker
    PipelineValidationError.DisconnectedInput,
    PipelineValidationError.DisconnectedOutput,
    PipelineValidationError.UnreachableNode,
    PipelineValidationError.DeadEndNode,
    is PipelineValidationError.NodeEmptyContext,
    -> Severity.Warning
}

/**
 * Convenience predicate matching [app.knotwork.android.presentation.ui.pipeline.editor.core.ValidationAutoFix]
 * — used to enable/disable the header `Auto-fix` button before the user taps it.
 */
private fun isAutoFixable(error: PipelineValidationError): Boolean = when (error) {
    PipelineValidationError.MissingInput,
    PipelineValidationError.MissingOutput,
    PipelineValidationError.MultipleInputs,
    PipelineValidationError.MultipleOutputs,
    PipelineValidationError.DisconnectedInput,
    PipelineValidationError.DisconnectedOutput,
    -> true
    PipelineValidationError.HasCycles,
    PipelineValidationError.UnreachableNode,
    PipelineValidationError.DeadEndNode,
    is PipelineValidationError.NodeEmptyContext,
    // Composition errors need the user to re-pick a target / restructure — no recipe.
    is PipelineValidationError.MissingTargetPipeline,
    is PipelineValidationError.TargetPipelineNotFound,
    is PipelineValidationError.PipelineCycle,
    is PipelineValidationError.PipelineNestingTooDeep,
    // A SKILL node needs the user to pick a valid skill — no auto-fix recipe.
    is PipelineValidationError.MissingSkill,
    is PipelineValidationError.SkillNotFound,
    -> false
}
