package app.knotwork.design.components.console

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Characters of a hash shown on a row; tapping copies all of them. */
internal const val SHORT_HASH_LENGTH = 8

/** Characters per group of a full hash. */
private const val HASH_GROUP_LENGTH = 8

/** Groups per line of a full hash at ordinary font scales. */
private const val HASH_GROUPS_PER_LINE = 4

/** Groups per line of a full hash from this font scale up. */
private const val HASH_GROUPS_PER_LINE_LARGE = 2

/** Font scale from which full hashes drop to fewer groups per line and field labels stack. */
internal const val LARGE_FONT_SCALE = 1.5f

/** Visual height of a hash chip; its touch box is the 48 dp minimum. */
private val HashChipHeight = 26.dp

/** Size of a chip's copy glyph. */
private val HashChipGlyph = 11.dp

/** Minimum touch target. */
internal val RunTouchTarget = 48.dp

/**
 * A full SHA-256 in groups of eight, at most four groups a line (two at large font
 * scales) — never ellipsised, never scrolled sideways, and never a group broken
 * across lines: where four do not fit, fewer go on the line. The gaps are spacing,
 * not characters, so what is copied is the 64 characters alone.
 *
 * TalkBack never reads the hash out: it hears what the hash is, its length and
 * its first eight characters in pairs.
 *
 * @param sha256 The hash.
 * @param what What the hash is, for TalkBack ("Run digest").
 * @param color The text colour.
 * @param modifier Optional layout modifier.
 */
@Composable
internal fun FullHash(sha256: String, what: String, color: Color, modifier: Modifier = Modifier) {
    val perLine = if (KnotworkTheme.a11y.fontScale() >= LARGE_FONT_SCALE) {
        HASH_GROUPS_PER_LINE_LARGE
    } else {
        HASH_GROUPS_PER_LINE
    }
    val description = stringResource(R.string.knotwork_run_hash_full_a11y, what, pairs(sha256.take(SHORT_HASH_LENGTH)))
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        maxItemsInEachRow = perLine,
    ) {
        sha256.chunked(HASH_GROUP_LENGTH).forEach { group ->
            Text(text = group, style = KnotworkTextStyles.MonoSm, color = color, softWrap = false)
        }
    }
}

/**
 * A node's input or output hash on a console row: its first eight characters in a
 * small outlined chip; a tap copies all 64.
 *
 * @param kind Input or output.
 * @param sha256 The full hash.
 * @param onCopy Invoked on a tap.
 * @param modifier Optional layout modifier.
 */
@Composable
internal fun ConsoleHashChip(kind: HashKind, sha256: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val fg = KnotworkTheme.extended.consoleFg
    val short = sha256.take(SHORT_HASH_LENGTH)
    val label = when (kind) {
        HashKind.INPUT -> stringResource(R.string.knotwork_run_hash_in, short)
        HashKind.OUTPUT -> stringResource(R.string.knotwork_run_hash_out, short)
    }
    val what = when (kind) {
        HashKind.INPUT -> stringResource(R.string.knotwork_run_hash_input)
        HashKind.OUTPUT -> stringResource(R.string.knotwork_run_hash_output)
    }
    val description = stringResource(R.string.knotwork_run_hash_short_a11y, what, spaced(short))
    HashChip(
        text = label,
        textColor = fg,
        borderColor = fg.copy(alpha = RUN_HAIRLINE_ALPHA),
        glyphColor = fg.copy(alpha = RUN_FAINT_ALPHA),
        description = description,
        onCopy = onCopy,
        modifier = modifier,
    )
}

/**
 * A copyable short-hash chip: the shared shape of the console chips and the
 * recorded / replayed hashes of a diverged visit.
 *
 * @param text What the chip shows.
 * @param textColor Its text colour.
 * @param borderColor Its outline colour.
 * @param glyphColor Its copy glyph's colour.
 * @param description What TalkBack reads.
 * @param onCopy Invoked on a tap.
 * @param modifier Optional layout modifier.
 */
@Composable
internal fun HashChip(
    text: String,
    textColor: Color,
    borderColor: Color,
    glyphColor: Color,
    description: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = RunTouchTarget)
            // Its own TalkBack stop: a console row merges what it holds, and a chip
            // merged into its row could not be activated on its own.
            .semantics(mergeDescendants = true) {}
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onCopy()
                    true
                }
            }
            .clickable(onClick = onCopy),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier
                .heightIn(min = HashChipHeight)
                .clip(KnotworkTheme.shapes.sm)
                .border(width = 1.dp, color = borderColor, shape = KnotworkTheme.shapes.sm)
                .padding(horizontal = KnotworkTheme.spacing.sp2),
        ) {
            Text(text = text, style = KnotworkTextStyles.MonoSm, color = textColor)
            Icon(
                imageVector = AppIcons.Copy,
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(HashChipGlyph),
            )
        }
    }
}

/** [text]'s characters in pairs (`9c 41 e0 7d`), for TalkBack. */
internal fun pairs(text: String): String = text.chunked(2).joinToString(separator = " ")

/** [text]'s characters one by one (`c 9 0 b`), for TalkBack. */
internal fun spaced(text: String): String = text.toList().joinToString(separator = " ")

/** Alpha of the console's hairlines over its background. */
internal const val RUN_HAIRLINE_ALPHA = 0.15f

/** Alpha of faint console text and glyphs. */
internal const val RUN_FAINT_ALPHA = 0.42f

/** Alpha of dim console text. */
internal const val RUN_DIM_ALPHA = 0.66f
