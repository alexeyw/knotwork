package app.knotwork.design.components.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Form-field wrapper — external caps-label on top, optional inline hint
 * (mono) on the same row, [content] in the middle, optional helper / error /
 * counter row below.
 *
 * This is the canonical "[Field]" pattern:
 * Material3's floating label is intentionally off everywhere in Knotwork
 * because (a) it consumes vertical rhythm on dense NodeConfigSheet rows and
 * (b) the all-caps label is a brand signal repeated across every form.
 *
 * Wrap any input atom (`KnotworkTextField`, `KnotworkTextArea`,
 * `KnotworkPasswordField`, `KnotworkChipsInput`, the catalog dropdown box …)
 * in this composable to inherit consistent spacing and a11y semantics.
 *
 * Spacing contract:
 *  - Label → input gap = [KnotworkFieldDefaults.LabelGap] (8 dp).
 *  - Input → helper gap = [KnotworkFieldDefaults.HelperGap] (4 dp).
 *  - Two adjacent [KnotworkField] siblings in a form should be separated by
 *    [KnotworkFieldDefaults.FieldGap] (12 dp) — caller responsibility.
 *
 * @param label uppercase caps-label rendered above [content]. Empty string
 *  collapses the label row entirely.
 * @param modifier layout modifier on the outer column; defaults to
 *  `Modifier.fillMaxWidth()`.
 * @param hint optional mono inline hint rendered on the right edge of the
 *  label row (e.g. `"$DATE · $TIME"`). Useful for surfacing the active
 *  variable scope without spending another row.
 * @param helper optional helper / counter line rendered below [content].
 *  Replaced by [errorText] when [isError] is `true`.
 * @param isError when `true`, the helper row is forced into the destructive
 *  palette and the label colour shifts to `riskDestructive` so the field's
 *  error state reads even when the input itself is off-screen.
 * @param errorText error message shown in place of [helper] when [isError].
 *  When `null` and [isError] is `true`, [helper] is rendered in the
 *  destructive palette instead.
 * @param content the input atom — typically `KnotworkTextField`,
 *  `KnotworkTextArea`, `KnotworkPasswordField`, or `KnotworkChipsInput`.
 */
@Composable
fun KnotworkField(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    helper: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    content: @Composable () -> Unit,
) {
    val labelColor = if (isError) {
        KnotworkTheme.extended.riskDestructive
    } else {
        KnotworkTheme.extended.onSurfaceMuted
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        if (label.isNotEmpty() || hint != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (label.isNotEmpty()) {
                    Text(
                        text = label.uppercase(),
                        style = KnotworkTextStyles.LabelSm,
                        color = labelColor,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                } else {
                    Spacer(Modifier.weight(1f, fill = true))
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(KnotworkFieldDefaults.LabelGap))
        }

        content()

        val resolvedHelper = errorText?.takeIf { isError } ?: helper
        if (resolvedHelper != null) {
            Spacer(Modifier.height(KnotworkFieldDefaults.HelperGap))
            HelperRow(text = resolvedHelper, isError = isError)
        }
    }
}

/**
 * Helper / counter row under the input. Two-column flex: free-form helper
 * text on the left expands, a right-aligned counter (if present in
 * [text] as `"… · 120/500"`) trails — for now we render a single text run
 * and rely on the caller to bake the counter into [text]. A typed counter
 * API can be split out later when there's more than one call site that
 * needs it.
 */
@Composable
private fun HelperRow(text: String, isError: Boolean) {
    val color = if (isError) {
        KnotworkTheme.extended.riskDestructive
    } else {
        KnotworkTheme.extended.onSurfaceMuted
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = KnotworkTextStyles.LabelSm, color = color)
    }
}
