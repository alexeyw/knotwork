@file:Suppress("MatchingDeclarationName") // File hosts PromptPreviewSheet + its segment and view-state payloads.

package app.knotwork.design.components.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme

/**
 * Height of the loading-spinner container before segments resolve. Off the
 * `KnotworkSpacing` 4 dp scale on purpose — sized to host a centred
 * `CircularProgressIndicator` with a vertical-rhythm match against the
 * surrounding `bodyMedium` line height.
 */
private val LoadingPlaceholderHeight = 120.dp

/**
 * One piece of a rendered prompt.
 *
 * The catalog's own vocabulary: `:app` produces these from whatever its prompt
 * engine returns. Modelled as a sealed type rather than a flag-carrying data
 * class because the three cases render in genuinely different ways — one is
 * plain text, one is tinted, and one is an inline slot with a tooltip.
 */
sealed interface PromptPreviewSegmentUi {

    /** Text that came from the template itself. */
    data class Literal(val text: String) : PromptPreviewSegmentUi

    /** A placeholder the engine substituted, and the value it produced. */
    data class Resolved(val value: String) : PromptPreviewSegmentUi

    /**
     * A placeholder nothing resolved. Almost always a typo in the template,
     * which is why it is rendered as an error rather than left as plain text.
     *
     * @property key The placeholder's name, without the leading `$`.
     */
    data class Unknown(val key: String) : PromptPreviewSegmentUi
}

/**
 * Resolved copy for [PromptPreviewSheet].
 *
 * @property title Sheet title.
 * @property variableNotFoundTooltip Long-press tooltip on an unresolved placeholder.
 */
data class PromptPreviewSheetUi(val title: String, val variableNotFoundTooltip: String)

/**
 * Bottom sheet displaying a prompt with its `$VARIABLES` already substituted.
 *
 * Resolved values are tinted so the reader can see what the engine put in place
 * of each placeholder; unresolved ones are tinted as errors and carry a tooltip,
 * because an unresolved token is nearly always a typo rather than a choice.
 *
 * A thin presentation shell: the caller produces [segments]. `null` means
 * resolution is still in flight and shows a spinner — which matters, because
 * some providers hit the database.
 *
 * @param segments Ordered segments, or `null` while resolution is in flight.
 * @param ui Resolved copy.
 * @param onDismiss Sheet dragged away or scrim tapped.
 * @param modifier Optional modifier applied to the sheet root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPreviewSheet(
    segments: List<PromptPreviewSegmentUi>?,
    ui: PromptPreviewSheetUi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        PromptPreviewSheetBody(segments = segments, ui = ui)
    }
}

/**
 * The sheet's body, without the `ModalBottomSheet` around it.
 *
 * Split for the reason `NodeConfigSheet` gives: a `ModalBottomSheet` does not
 * lay out under Robolectric, so this is what a baseline photographs.
 *
 * @param segments Ordered segments, or `null` for the loading state.
 * @param ui Resolved copy.
 * @param modifier Optional layout modifier.
 */
@Composable
fun PromptPreviewSheetBody(
    segments: List<PromptPreviewSegmentUi>?,
    ui: PromptPreviewSheetUi,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp6, vertical = KnotworkTheme.spacing.sp4)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = ui.title, style = MaterialTheme.typography.titleMedium)
        if (segments == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LoadingPlaceholderHeight)
                    .padding(top = KnotworkTheme.spacing.sp3),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            PreviewBody(
                segments = segments,
                tooltip = ui.variableNotFoundTooltip,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3),
            )
        }
    }
}

/**
 * Renders [segments] as a single flowing [Text] so the preview matches the
 * layout of the final prompt string — line breaks come from the prompt itself,
 * not from segment boundaries. Unresolved placeholders are embedded as
 * `inlineContent` slots so each can host its own tooltip without breaking the
 * inline flow; wrapping treats them as ordinary glyph runs.
 */
@Composable
private fun PreviewBody(segments: List<PromptPreviewSegmentUi>, tooltip: String, modifier: Modifier = Modifier) {
    val resolvedBg = MaterialTheme.colorScheme.tertiaryContainer
    val resolvedFg = MaterialTheme.colorScheme.onTertiaryContainer
    val unknownBg = MaterialTheme.colorScheme.errorContainer
    val unknownFg = MaterialTheme.colorScheme.onErrorContainer
    val textStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Pre-compute the inline-placeholder dimensions for every unresolved segment
    // so the slot inside `Text` reserves exactly the space the literal `$KEY`
    // would occupy. Theme colours participate in the cache key — without them
    // the inline content keeps serving stale colours after a theme switch.
    val inlineContent = remember(segments, textStyle, density, unknownBg, unknownFg, tooltip) {
        buildInlineContent(
            segments = segments,
            textStyle = textStyle,
            measurer = measurer,
            density = density,
            unknownBg = unknownBg,
            unknownFg = unknownFg,
            tooltip = tooltip,
        )
    }

    Text(
        text = buildPreviewAnnotatedString(segments, resolvedBg, resolvedFg),
        style = textStyle,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}

/**
 * Builds the [AnnotatedString] the preview renders. Unresolved segments become
 * inline-content placeholders keyed by [unknownInlineId], so the map can
 * substitute a tooltip-wrapped composable at render time.
 */
private fun buildPreviewAnnotatedString(
    segments: List<PromptPreviewSegmentUi>,
    resolvedBg: Color,
    resolvedFg: Color,
): AnnotatedString = buildAnnotatedString {
    var unknownIndex = 0
    for (segment in segments) {
        when (segment) {
            is PromptPreviewSegmentUi.Literal -> append(segment.text)
            is PromptPreviewSegmentUi.Resolved ->
                withStyle(SpanStyle(color = resolvedFg, background = resolvedBg)) { append(segment.value) }
            is PromptPreviewSegmentUi.Unknown -> {
                appendInlineContent(id = unknownInlineId(unknownIndex), alternateText = "$" + segment.key)
                unknownIndex++
            }
        }
    }
}

/**
 * For every unresolved segment, an [InlineTextContent] whose [Placeholder]
 * matches the rendered size of `$KEY` under [textStyle], so the slot does not
 * disturb the surrounding line layout. The slot wraps a [TooltipBox].
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun buildInlineContent(
    segments: List<PromptPreviewSegmentUi>,
    textStyle: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    unknownBg: Color,
    unknownFg: Color,
    tooltip: String,
): Map<String, InlineTextContent> {
    val map = mutableMapOf<String, InlineTextContent>()
    var unknownIndex = 0
    for (segment in segments) {
        if (segment !is PromptPreviewSegmentUi.Unknown) continue
        val token = "$" + segment.key
        val measured = measurer.measure(AnnotatedString(token), style = textStyle)
        val width = with(density) { measured.size.width.toSp() }
        val height = with(density) { measured.size.height.toSp() }
        map[unknownInlineId(unknownIndex)] = InlineTextContent(
            placeholder = Placeholder(
                width = width,
                height = height,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
            children = { _ ->
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(tooltip) } },
                    state = rememberTooltipState(),
                ) {
                    Box(modifier = Modifier.background(unknownBg), contentAlignment = Alignment.Center) {
                        Text(text = token, style = textStyle.copy(color = unknownFg))
                    }
                }
            },
        )
        unknownIndex++
    }
    return map
}

/**
 * Stable id binding an inline placeholder to its slot. The index disambiguates
 * several unresolved placeholders in one prompt; the key is deliberately **not**
 * part of the id, because two unknowns sharing a key still need separate slots.
 */
private fun unknownInlineId(index: Int): String = "prompt_unknown_$index"
