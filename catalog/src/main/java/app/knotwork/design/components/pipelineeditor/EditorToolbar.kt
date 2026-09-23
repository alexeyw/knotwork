package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkIconButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Single-line toolbar height (no subtitle). Mirrors Material `TopAppBar` minimum,
 * trimmed so the canvas owns its own bar — `LargeTopAppBar` would steal the run
 * banner's vertical space.
 */
private val ToolbarHeightSingle = 56.dp

/**
 * Two-line toolbar height (title + subtitle). Tuned so the subtitle (`BodySm`) sits
 * flush against the title baseline without crowding the navigation icon.
 */
private val ToolbarHeightWithSubtitle = 64.dp

/** Diameter of the unsaved-changes dot beside the subtitle. */
private val UnsavedDotSize = 6.dp

/**
 * Pipeline-editor top toolbar — `[← back] [Title + Subtitle stack] [Primary action] [Overflow]`.
 *
 * `Undo / Redo / Delete / Auto-layout` live in the overflow menu rather than
 * as permanent icon buttons so the toolbar stays uncluttered across every
 * state (Editing /
 * Validating / Running / Done / Overview). The caller owns the overflow
 * `DropdownMenu` — this composable just invokes [onOverflow] when the icon is
 * tapped.
 *
 * **Stateless** — every action surfaces as a lambda. The name field is fully
 * controlled via [name] / [onNameChange]; the subtitle is whatever string the
 * caller computes from `validationErrors` / `nodes.size` / etc.
 *
 * The toolbar carries no run control: the editor composes pipelines, it does not
 * execute them. Runs are started from chat, where the console reports them.
 *
 * @param name current pipeline name shown in the inline editor.
 * @param onNameChange invoked with each keystroke in the name field.
 * @param onNavigateUp invoked when the leading back icon is tapped.
 * @param onOverflow invoked when the trailing overflow icon is tapped.
 * @param subtitle optional secondary line below the pipeline name — drives the
 *   bar's vertical sizing (56 dp without, 64 dp with). Typically
 *   `"Editing · N nodes · M edges"` / `"Overview · 0.42× · 11 nodes"` / etc.
 * @param modifier optional layout modifier applied to the bar root.
 */
@Composable
fun EditorToolbar(
    name: String,
    onNameChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    unsavedChanges: Boolean = false,
) {
    val barHeight = if (subtitle == null) ToolbarHeightSingle else ToolbarHeightWithSubtitle
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = KnotworkTheme.elevation.el1,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            KnotworkIconButton(
                onClick = onNavigateUp,
                contentDescription = stringResource(R.string.knotwork_editor_action_navigate_up),
                icon = AppIcons.Back,
            )
            TitleStack(
                name = name,
                onNameChange = onNameChange,
                subtitle = subtitle,
                unsavedChanges = unsavedChanges,
                modifier = Modifier.weight(1f),
            )
            KnotworkIconButton(
                onClick = onOverflow,
                contentDescription = stringResource(R.string.knotwork_editor_action_overflow),
                icon = AppIcons.More,
            )
        }
    }
}

/**
 * Title + optional subtitle stack rendered between the back icon and the
 * overflow icon. The pipeline name remains a [BasicTextField]
 * (inline-editable); the subtitle is a read-only [BodySm] beneath it.
 *
 * The field is laid out with a min-height equal to a single line of `TitleLg` so
 * the row collapses gracefully when [subtitle] is `null` and grows to two lines
 * when present without the title visibly jumping.
 */
@Composable
private fun TitleStack(
    name: String,
    onNameChange: (String) -> Unit,
    subtitle: String?,
    unsavedChanges: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(modifier = Modifier.defaultMinSize(minHeight = 28.dp)) {
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = KnotworkTextStyles.TitleLg.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { /* canvas commits on submit */ }),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .background(color = KnotworkTheme.extended.surface1, shape = KnotworkTheme.shapes.sm)
                            .padding(
                                horizontal = KnotworkTheme.spacing.sp2,
                                vertical = KnotworkTheme.spacing.sp1,
                            ),
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                text = stringResource(R.string.knotwork_editor_name_placeholder),
                                style = KnotworkTextStyles.TitleLg,
                                color = KnotworkTheme.extended.onSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (subtitle != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp2),
            ) {
                // A dot rather than a word, and beside the count rather than in
                // place of it: the subtitle is the one line that survives every
                // editor state, so the marker has to coexist with what is
                // already there. The text after it carries the meaning for
                // anyone who cannot see the colour.
                if (unsavedChanges) {
                    Box(
                        modifier = Modifier
                            .size(UnsavedDotSize)
                            .background(color = KnotworkTheme.extended.signalWarn, shape = CircleShape),
                    )
                    Text(
                        text = stringResource(R.string.knotwork_editor_unsaved_changes),
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.signalWarn,
                        maxLines = 1,
                    )
                }
                Text(
                    text = subtitle,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Light-theme preview — editing state, primary `Run`. */
@Preview(name = "EditorToolbar — light editing", showBackground = true, widthDp = 720)
@Composable
private fun EditorToolbarLightEditingPreview() {
    KnotworkTheme(darkTheme = false) {
        EditorToolbar(
            name = "Weekly digest",
            onNameChange = {},
            onNavigateUp = {},
            onOverflow = {},
            subtitle = "Editing · 4 nodes · 3 edges",
        )
    }
}

/** Dark-theme preview — long name, overview subtitle. */
@Preview(name = "EditorToolbar — dark overview", showBackground = true, widthDp = 720)
@Composable
private fun EditorToolbarDarkOverviewPreview() {
    KnotworkTheme(darkTheme = true) {
        EditorToolbar(
            name = "research-deepdive",
            onNameChange = {},
            onNavigateUp = {},
            onOverflow = {},
            subtitle = "Overview · 0.42× · 11 nodes",
        )
    }
}

/** Preview — invalid state: subtitle reflects issues. */
@Preview(name = "EditorToolbar — issues", showBackground = true, widthDp = 720)
@Composable
private fun EditorToolbarIssuesPreview() {
    KnotworkTheme(darkTheme = false) {
        EditorToolbar(
            name = "research-deepdive",
            onNameChange = {},
            onNavigateUp = {},
            onOverflow = {},
            subtitle = "2 issues · can't save",
        )
    }
}
