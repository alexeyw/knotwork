package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.design.components.controls.KnotworkFieldSize
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Overlay search bar pinned to the top of the canvas while
 * `EditorState.searchOpen` is `true`. Lets the user filter nodes by label /
 * type as they type; pressing Enter (`ImeAction.Search`) jumps to the first
 * match.
 *
 * Uses the Knotwork [KnotworkTextField] search variant — pill shape,
 * `surface2` container, leading [AppIcons.Search] glyph baked into the field
 * rather than rendered as a sibling. Trailing `×` stays as a standalone
 * [IconButton] because it closes the overlay entirely rather than clearing the
 * query.
 *
 * @param query current query string.
 * @param matchCount number of nodes matching [query] in the live graph.
 * @param onQueryChange invoked on each keystroke.
 * @param onSubmit invoked on `Enter` — caller centres the canvas on the first
 *   matching node.
 * @param onClose invoked when the `×` button is tapped.
 * @param modifier optional layout modifier (typically `.align(TopStart)`).
 */
@Composable
internal fun FilterBar(
    query: String,
    matchCount: Int,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        color = KnotworkTheme.extended.surface1,
        tonalElevation = KnotworkTheme.elevation.el2,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            KnotworkTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                size = KnotworkFieldSize.Md,
                search = true,
                placeholder = stringResource(R.string.pipeline_editor_search_placeholder),
                leadingIcon = AppIcons.Search,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                contentDescription = stringResource(R.string.pipeline_editor_search_placeholder),
            )
            if (query.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        id = R.plurals.pipeline_editor_search_count,
                        count = matchCount,
                        matchCount,
                    ),
                    style = KnotworkTextStyles.LabelSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.pipeline_editor_search_close),
                )
            }
        }
    }
}
