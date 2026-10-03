package app.knotwork.design.components.chips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.controls.KnotworkFieldDefaults
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Composite input atom that renders an existing list of values as
 * [KnotworkInputChip]s and offers an inline text input to append new ones.
 *
 * Behaviour:
 *  - [FlowRow] with 6×6 dp spacing — chips wrap onto multiple lines.
 *  - The trailing input shares the same row as the last chip; it commits a
 *    new value on `Enter` (IME `Done`) or `,`. Empty / duplicate values
 *    are ignored.
 *  - When [maxItems] is set and reached, the input is hidden and replaced
 *    by a `"Max N items"` caption so the user knows why the field went
 *    inert.
 *
 * Spec wraps this in a [app.knotwork.design.components.controls.KnotworkField]
 * for caps-label + helper. The atom itself is intentionally
 * border-on-container so it visually reads as a single input row even when
 * chips wrap.
 *
 * @param values Current list of values. The caller owns the source-of-truth.
 * @param onValuesChange Callback emitted with the new list whenever the
 *  user adds or removes a value.
 * @param modifier Layout modifier applied to the outer column.
 * @param placeholder Hint text for the inline text input.
 * @param maxItems Optional cap on the list length; the input goes inert
 *  when reached.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnotworkChipsInput(
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Add and press Enter…",
    maxItems: Int? = null,
) {
    val ext = KnotworkTheme.extended
    var draft by remember { mutableStateOf("") }
    val reachedMax = maxItems != null && values.size >= maxItems

    fun commit() {
        val trimmed = draft.trim().trimEnd(',').trim()
        if (trimmed.isEmpty()) return
        if (trimmed in values) {
            draft = ""
            return
        }
        if (maxItems != null && values.size >= maxItems) return
        onValuesChange(values + trimmed)
        draft = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = KnotworkFieldDefaults.HeightSm)
                .clip(KnotworkTheme.shapes.sm)
                .background(ext.surface1, KnotworkTheme.shapes.sm)
                .border(
                    KnotworkFieldDefaults.BorderDefault,
                    MaterialTheme.colorScheme.outline,
                    KnotworkTheme.shapes.sm,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for ((index, value) in values.withIndex()) {
                    KnotworkInputChip(
                        label = value,
                        onRemove = {
                            onValuesChange(values.toMutableList().also { it.removeAt(index) })
                        },
                    )
                }
                if (!reachedMax) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { next ->
                            if (next.endsWith(',')) {
                                draft = next.dropLast(1)
                                commit()
                            } else {
                                draft = next
                            }
                        },
                        textStyle = KnotworkTextStyles.BodyBase.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .widthIn(min = InputMinWidth)
                            .defaultMinSize(minHeight = ChipRowHeight),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier.defaultMinSize(minHeight = ChipRowHeight),
                                contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = KnotworkTextStyles.BodyBase,
                                        color = ext.onSurfaceDim,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
        if (reachedMax) {
            // `reachedMax` already implies `maxItems != null`; assert to give the smart-cast a hand.
            val cap = checkNotNull(maxItems)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Max $cap items",
                style = KnotworkTextStyles.LabelSm,
                color = ext.onSurfaceMuted,
            )
        }
    }
}

private val InputMinWidth = 80.dp
private val ChipRowHeight = 32.dp
